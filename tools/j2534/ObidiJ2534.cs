// OBIDI J2534 Probe — опрос машины через любой адаптер SAE J2534 (Scanmatik SM2 PRO и др.), только чтение.
//
// Источники (ничего не придумано):
//  * Константы, структуры и сигнатуры — J2534_v0404.h из драйвера comma.ai panda (MIT):
//    github.com/commaai/chffrplus/panda/drivers/windows/pandaJ2534DLL/J2534_v0404.h
//  * Поиск драйвера: HKLM\SOFTWARE\PassThruSupport.04.04\<устройство>\FunctionLibrary (Opus IVS, Quantex);
//    API задан для 32-битной Windows (документация Qt PassThruCAN) — основная сборка x86.
//  * J1962_PINS = 0xPPSS: PP — контакт K-линии, SS — контакт L (0 — нет) (тот же заголовок).
//  * Возможности SM2 PRO: K/L на контактах 1–3 и 6–15, CAN 6/14, 3/11, 12/13 (руководство Scanmatik J2534/RP1210/DPDU).
//  * Адреса K-line — список нашего сканера ModuleMap.klineAddresses (app/.../ObdDecoder.kt).
//
// Безопасность: запросы только на чтение (01/03/07/09/0A, 10 01, 19 02 FF, 18 xx FF 00, 22 F1 90/87).
// K-линия на «чужие» контакты включается, только если на этой паре не замечен живой CAN
// (сигнал K-линии на линии CAN мешает шине).
//
// Сборка: build.bat (компилятор C# из .NET Framework 4.8, есть в Windows 10/11).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32;

namespace Obidi
{
    static class J
    {
        // протоколы (J2534-1 v04.04 / J2534-2)
        public const uint ISO9141 = 0x03, ISO14230 = 0x04, CAN = 0x05, ISO15765 = 0x06;
        public const uint ISO9141_PS = 0x8002, ISO14230_PS = 0x8003, CAN_PS = 0x8004, ISO15765_PS = 0x8005;
        // флаги подключения и сообщений
        public const uint CAN_29BIT_ID = 0x0100, ISO15765_FRAME_PAD = 0x0040;
        // фильтры
        public const uint PASS_FILTER = 1, FLOW_CONTROL_FILTER = 3;
        // IOCTL
        public const uint GET_CONFIG = 0x01, SET_CONFIG = 0x02, READ_VBATT = 0x03, FIVE_BAUD_INIT = 0x04, FAST_INIT = 0x05,
            CLEAR_RX_BUFFER = 0x08, CLEAR_MSG_FILTERS = 0x0A;
        // параметры конфигурации
        public const uint DATA_RATE = 0x01, LOOPBACK = 0x03, J1962_PINS = 0x8001;
        // коды ошибок
        public const int STATUS_NOERROR = 0, ERR_NOT_SUPPORTED = 1, ERR_TIMEOUT = 9, ERR_BUFFER_EMPTY = 0x10, ERR_DEVICE_IN_USE = 0x0E;

        public static readonly string[] ErrNames = {
            "STATUS_NOERROR", "ERR_NOT_SUPPORTED", "ERR_INVALID_CHANNEL_ID", "ERR_INVALID_PROTOCOL_ID", "ERR_NULL_PARAMETER",
            "ERR_INVALID_IOCTL_VALUE", "ERR_INVALID_FLAGS", "ERR_FAILED", "ERR_DEVICE_NOT_CONNECTED", "ERR_TIMEOUT",
            "ERR_INVALID_MSG", "ERR_INVALID_TIME_INTERVAL", "ERR_EXCEEDED_LIMIT", "ERR_INVALID_MSG_ID", "ERR_DEVICE_IN_USE",
            "ERR_INVALID_IOCTL_ID", "ERR_BUFFER_EMPTY", "ERR_BUFFER_FULL", "ERR_BUFFER_OVERFLOW", "ERR_PIN_INVALID",
            "ERR_CHANNEL_IN_USE", "ERR_MSG_PROTOCOL_ID", "ERR_INVALID_FILTER_ID", "ERR_NO_FLOW_CONTROL", "ERR_NOT_UNIQUE",
            "ERR_INVALID_BAUDRATE", "ERR_INVALID_DEVICE_ID" };

        public static string Name(int code) { return code >= 0 && code < ErrNames.Length ? ErrNames[code] : "0x" + code.ToString("X"); }

        // PASSTHRU_MSG: шесть unsigned long (4 байта в Windows и x86, и x64) + Data[4128]
        public const int MSG_SIZE = 24 + 4128;
    }

    // ---------- вызовы DLL (__stdcall) ----------
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtOpen(IntPtr name, out uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtClose(uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtConnect(uint deviceId, uint protocol, uint flags, uint baud, out uint channelId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtDisconnect(uint channelId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtReadMsgs(uint channelId, IntPtr msgs, ref uint num, uint timeout);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtWriteMsgs(uint channelId, IntPtr msgs, ref uint num, uint timeout);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtStartMsgFilter(uint channelId, uint type, IntPtr mask, IntPtr pattern, IntPtr flowControl, out uint filterId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtIoctl(uint channelId, uint ioctl, IntPtr input, IntPtr output);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet = CharSet.Ansi)] delegate int PtReadVersion(uint deviceId, StringBuilder fw, StringBuilder dll, StringBuilder api);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet = CharSet.Ansi)] delegate int PtGetLastError(StringBuilder err);

    static class Native
    {
        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] public static extern IntPtr LoadLibrary(string path);
        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Ansi)] public static extern IntPtr GetProcAddress(IntPtr h, string name);
    }

    /// <summary>Сообщение J2534 в неуправляемой памяти: поля пишем по смещениям, без автоматического маршалинга.</summary>
    sealed class Msg : IDisposable
    {
        public readonly IntPtr P;
        public Msg() { P = Marshal.AllocHGlobal(J.MSG_SIZE); Clear(); }
        public void Clear() { for (int i = 0; i < J.MSG_SIZE; i += 4) Marshal.WriteInt32(P, i, 0); }
        public uint Protocol { get { return (uint)Marshal.ReadInt32(P, 0); } set { Marshal.WriteInt32(P, 0, (int)value); } }
        public uint RxStatus { get { return (uint)Marshal.ReadInt32(P, 4); } }
        public uint TxFlags { set { Marshal.WriteInt32(P, 8, (int)value); } }
        public uint Timestamp { get { return (uint)Marshal.ReadInt32(P, 12); } }
        public int Size { get { return Marshal.ReadInt32(P, 16); } set { Marshal.WriteInt32(P, 16, value); } }
        public byte[] Data
        {
            get { int n = Math.Max(0, Math.Min(Size, 4128)); var b = new byte[n]; Marshal.Copy(P + 24, b, 0, n); return b; }
            set { Marshal.Copy(value, 0, P + 24, value.Length); Size = value.Length; }
        }
        public void Dispose() { Marshal.FreeHGlobal(P); }
    }

    sealed class Device
    {
        public string Name, Vendor, Library, View;
    }

    static class Log
    {
        static StreamWriter file;
        public static string PathName;
        public static void Open(string dir)
        {
            PathName = Path.Combine(dir, "obidi-j2534-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt");
            file = new StreamWriter(PathName, false, new UTF8Encoding(false));
            file.AutoFlush = true;
        }
        public static void W(string s) { string line = DateTime.Now.ToString("HH:mm:ss.fff") + "  " + s; Console.WriteLine(s); if (file != null) file.WriteLine(line); }
        public static void Raw(string s) { if (file != null) file.WriteLine(DateTime.Now.ToString("HH:mm:ss.fff") + "  " + s); }
        public static void Close() { if (file != null) file.Close(); }
    }

    sealed class Pt
    {
        public PtOpen Open; public PtClose Close; public PtConnect Connect; public PtDisconnect Disconnect;
        public PtReadMsgs Read; public PtWriteMsgs Write; public PtStartMsgFilter Filter; public PtIoctl Ioctl;
        public PtReadVersion Version; public PtGetLastError LastError;

        static T F<T>(IntPtr h, string name) where T : class
        {
            IntPtr p = Native.GetProcAddress(h, name);
            if (p == IntPtr.Zero) throw new Exception("в DLL нет функции " + name);
            return Marshal.GetDelegateForFunctionPointer(p, typeof(T)) as T;
        }

        public static Pt Load(string path)
        {
            IntPtr h = Native.LoadLibrary(path);
            if (h == IntPtr.Zero) throw new Exception("не загрузилась DLL " + path + " (код Windows " + Marshal.GetLastWin32Error() + ")");
            var p = new Pt();
            p.Open = F<PtOpen>(h, "PassThruOpen"); p.Close = F<PtClose>(h, "PassThruClose");
            p.Connect = F<PtConnect>(h, "PassThruConnect"); p.Disconnect = F<PtDisconnect>(h, "PassThruDisconnect");
            p.Read = F<PtReadMsgs>(h, "PassThruReadMsgs"); p.Write = F<PtWriteMsgs>(h, "PassThruWriteMsgs");
            p.Filter = F<PtStartMsgFilter>(h, "PassThruStartMsgFilter"); p.Ioctl = F<PtIoctl>(h, "PassThruIoctl");
            p.Version = F<PtReadVersion>(h, "PassThruReadVersion"); p.LastError = F<PtGetLastError>(h, "PassThruGetLastError");
            return p;
        }

        /// <summary>Код ответа словами + текст PassThruGetLastError при ошибке.</summary>
        public string Why(int code)
        {
            if (code == J.STATUS_NOERROR) return "OK";
            var sb = new StringBuilder(256);
            try { LastError(sb); } catch { }
            return J.Name(code) + (sb.Length > 0 ? " (" + sb + ")" : "");
        }
    }

    static class Program
    {
        static Pt pt;
        static uint dev;
        // ISO 15765-4: CAN-ID в первых четырёх байтах сообщения, старшим вперёд
        static byte[] Id(uint id) { return new byte[] { (byte)(id >> 24), (byte)(id >> 16), (byte)(id >> 8), (byte)id }; }
        static uint IdOf(byte[] d) { return d.Length < 4 ? 0 : ((uint)d[0] << 24) | ((uint)d[1] << 16) | ((uint)d[2] << 8) | d[3]; }
        static string Hex(byte[] b, int from) { var sb = new StringBuilder(); for (int i = from; i < b.Length; i++) { if (sb.Length > 0) sb.Append(' '); sb.Append(b[i].ToString("X2")); } return sb.ToString(); }
        static byte[] Cat(byte[] a, params byte[] b) { var r = new byte[a.Length + b.Length]; a.CopyTo(r, 0); b.CopyTo(r, a.Length); return r; }

        // ---------- итоги для сводки ----------
        static readonly List<string> Summary = new List<string>();
        static readonly HashSet<int> BusyPins = new HashSet<int>();

        static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            string dir = AppDomain.CurrentDomain.BaseDirectory;
            try { Log.Open(dir); } catch { Log.Open(Path.GetTempPath()); }
            try
            {
                return Run(args);
            }
            catch (Exception e)
            {
                Log.W("ОШИБКА: " + e.Message);
                Log.Raw(e.ToString());
                return 1;
            }
            finally
            {
                Log.W("");
                Log.W("Лог сохранён: " + Log.PathName);
                Log.Close();
                Console.WriteLine("Нажмите Enter, чтобы закрыть окно.");
                try { Console.ReadLine(); } catch { }
            }
        }

        static int Run(string[] args)
        {
            Log.W("OBIDI J2534 Probe 1.3 — " + (Environment.Is64BitProcess ? "x64" : "x86"));
            Log.W("Только чтение. Двигатель заглушен, зажигание включено, программа Сканматик закрыта.");
            Log.W("");

            var all = FindDevices();
            if (all.Count == 0) { Log.W("Не найдено ни одного адаптера J2534 в реестре (PassThruSupport.04.04). Установите программу Сканматик."); return 2; }
            for (int i = 0; i < all.Count; i++) Log.W(string.Format("[{0}] {1} — {2} ({3}): {4}", i + 1, all[i].Name, all[i].Vendor, all[i].View, all[i].Library));
            // У Сканматика в J2534 несколько подключений: «SM2 USB», «SM3 USB» и беспроводные, созданные в
            // «SM2 J2534-RP1210 Configurator» (руководство Scanmatik J2534: настройки J2534 и программы Сканматик не связаны).
            // Подключение по Bluetooth через «SM2 USB» не пойдёт — поэтому при нескольких вариантах спрашиваем.
            Device d = null;
            if (all.Count == 1) d = all[0];
            if (d == null)
            {
                Log.W("Адаптер по кабелю USB — выберите «SM2 USB». По Bluetooth — сначала добавьте беспроводное подключение");
                Log.W("в «SM2 J2534-RP1210 Configurator» (меню Пуск → Scanmatik), затем выберите его здесь.");
                Console.Write("Номер адаптера: ");
                int k; if (!int.TryParse(Console.ReadLine(), out k) || k < 1 || k > all.Count) { Log.W("Не выбран адаптер."); return 2; }
                d = all[k - 1];
            }
            Log.W("Выбран: " + d.Name + " → " + d.Library);

            int bits = PeBits(d.Library);
            int mine = Environment.Is64BitProcess ? 64 : 32;
            if (bits != 0 && bits != mine)
            {
                Log.W(string.Format("DLL адаптера {0}-битная, а эта программа {1}-битная. Запустите ObidiJ2534-{2}.exe.", bits, mine, bits == 64 ? "x64" : "x86"));
                return 3;
            }

            Console.Write("Двигатель заглушен, зажигание включено? Enter — начать: ");
            Console.ReadLine();

            pt = Pt.Load(d.Library);
            int r = pt.Open(IntPtr.Zero, out dev);
            Log.W("PassThruOpen: " + pt.Why(r));
            if (r != J.STATUS_NOERROR)
            {
                if (r == J.ERR_DEVICE_IN_USE) Log.W("Адаптер занят — закройте программу Сканматик и запустите снова.");
                return 4;
            }
            try
            {
                var fw = new StringBuilder(256); var dl = new StringBuilder(256); var api = new StringBuilder(256);
                r = pt.Version(dev, fw, dl, api);
                Log.W(string.Format("Версии: прошивка {0}, DLL {1}, API {2} ({3})", fw, dl, api, pt.Why(r)));

                Battery(0);
                var can11 = ObdCan(false);
                var can29 = ObdCan(true);
                var found11 = SweepCan(false);
                var found29 = SweepCan(true);
                foreach (var kv in found11) ReadModule(kv.Key, kv.Value, false);
                foreach (var kv in found29) ReadModule(kv.Key, kv.Value, true);
                ProbeOtherCan();
                SweepKline();

                Log.W("");
                Log.W("===== ИТОГ =====");
                Log.W(can11 || can29 ? "OBD по CAN: машина отвечает" : "OBD по CAN: нет ответа (машина не на CAN или шина спит)");
                foreach (var s in Summary) Log.W(s);
                if (BusyPins.Count > 0) Log.W("Контакты с живым CAN (K-линию на них не включали): " + string.Join(", ", BusyPins));
            }
            finally
            {
                r = pt.Close(dev);
                Log.Raw("PassThruClose: " + pt.Why(r));
            }
            return 0;
        }

        // ---------- реестр ----------
        static List<Device> FindDevices()
        {
            var list = new List<Device>();
            foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
            {
                try
                {
                    using (var hk = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (var root = hk.OpenSubKey(@"SOFTWARE\PassThruSupport.04.04"))
                    {
                        if (root == null) continue;
                        foreach (var sub in root.GetSubKeyNames())
                        {
                            using (var k = root.OpenSubKey(sub))
                            {
                                if (k == null) continue;
                                var lib = k.GetValue("FunctionLibrary") as string;
                                if (string.IsNullOrEmpty(lib)) continue;
                                bool dup = false;
                                foreach (var x in list) if (string.Equals(x.Library, lib, StringComparison.OrdinalIgnoreCase)) dup = true;
                                if (dup) continue;
                                var dv = new Device();
                                dv.Name = (k.GetValue("Name") as string) ?? sub;
                                dv.Vendor = (k.GetValue("Vendor") as string) ?? "";
                                dv.Library = lib;
                                dv.View = view == RegistryView.Registry32 ? "32-бит реестр" : "64-бит реестр";
                                list.Add(dv);
                            }
                        }
                    }
                }
                catch (Exception e) { Log.Raw("реестр " + view + ": " + e.Message); }
            }
            return list;
        }

        /// <summary>Разрядность DLL по заголовку PE: 0x14C — x86, 0x8664 — x64. 0 — не удалось прочитать.</summary>
        static int PeBits(string path)
        {
            try
            {
                using (var f = new BinaryReader(File.OpenRead(path)))
                {
                    f.BaseStream.Seek(0x3C, SeekOrigin.Begin);
                    int pe = f.ReadInt32();
                    f.BaseStream.Seek(pe + 4, SeekOrigin.Begin);
                    ushort machine = f.ReadUInt16();
                    return machine == 0x14C ? 32 : machine == 0x8664 ? 64 : 0;
                }
            }
            catch { return 0; }
        }

        // ---------- общие операции ----------
        static uint Connect(uint protocol, uint flags, uint baud, string what)
        {
            uint ch;
            int r = pt.Connect(dev, protocol, flags, baud, out ch);
            Log.Raw(string.Format("Connect {0} (proto 0x{1:X}, flags 0x{2:X}, {3}): {4}", what, protocol, flags, baud, pt.Why(r)));
            return r == J.STATUS_NOERROR ? ch : uint.MaxValue;
        }

        static void Disconnect(uint ch) { if (ch != uint.MaxValue) pt.Disconnect(ch); }

        static int SetConfig(uint ch, uint param, uint value)
        {
            IntPtr cfg = Marshal.AllocHGlobal(8);
            IntPtr list = Marshal.AllocHGlobal(IntPtr.Size * 2);
            try
            {
                Marshal.WriteInt32(cfg, 0, (int)param); Marshal.WriteInt32(cfg, 4, (int)value);
                // SCONFIG_LIST { unsigned long NumOfParams; SCONFIG* ConfigPtr; } — указатель выровнен по своему размеру
                Marshal.WriteInt32(list, 0, 1);
                Marshal.WriteIntPtr(list, IntPtr.Size, cfg);
                return pt.Ioctl(ch, J.SET_CONFIG, list, IntPtr.Zero);
            }
            finally { Marshal.FreeHGlobal(cfg); Marshal.FreeHGlobal(list); }
        }

        static void Battery(uint ch)
        {
            IntPtr v = Marshal.AllocHGlobal(4);
            try
            {
                // какой идентификатор ждёт READ_VBATT, у производителей по-разному: сначала устройство, потом канал
                int r = pt.Ioctl(dev, J.READ_VBATT, IntPtr.Zero, v);
                if (r != J.STATUS_NOERROR && ch != 0 && ch != uint.MaxValue) r = pt.Ioctl(ch, J.READ_VBATT, IntPtr.Zero, v);
                if (r == J.STATUS_NOERROR) Log.W(string.Format("Напряжение на колодке: {0:0.00} В", Marshal.ReadInt32(v) / 1000.0));
                else Log.W("Напряжение: " + pt.Why(r));
            }
            finally { Marshal.FreeHGlobal(v); }
        }

        static void Filter(uint ch, uint type, byte[] mask, byte[] pattern, byte[] flow, uint protocol, uint flags)
        {
            using (var m = new Msg()) using (var p = new Msg()) using (var f = new Msg())
            {
                m.Protocol = protocol; m.TxFlags = flags; m.Data = mask;
                p.Protocol = protocol; p.TxFlags = flags; p.Data = pattern;
                IntPtr fp = IntPtr.Zero;
                if (flow != null) { f.Protocol = protocol; f.TxFlags = flags; f.Data = flow; fp = f.P; }
                uint id;
                int r = pt.Filter(ch, type, m.P, p.P, fp, out id);
                if (r != J.STATUS_NOERROR) Log.Raw("Filter " + Hex(pattern, 0) + ": " + pt.Why(r));
            }
        }

        static int Send(uint ch, uint protocol, uint flags, byte[] data)
        {
            using (var m = new Msg())
            {
                m.Protocol = protocol; m.TxFlags = flags; m.Data = data;
                uint n = 1;
                int r = pt.Write(ch, m.P, ref n, 200);
                Log.Raw(">> " + Hex(data, 0) + (r == J.STATUS_NOERROR ? "" : "  " + pt.Why(r)));
                return r;
            }
        }

        /// <summary>Читать сообщения, пока они идут; window — сколько ждать тишины.</summary>
        static List<byte[]> Receive(uint ch, int windowMs, int totalMs)
        {
            var got = new List<byte[]>();
            var sw = Stopwatch.StartNew();
            using (var m = new Msg())
            {
                while (sw.ElapsedMilliseconds < totalMs)
                {
                    m.Clear();
                    uint n = 1;
                    int r = pt.Read(ch, m.P, ref n, (uint)windowMs);
                    if (r == J.STATUS_NOERROR && n > 0)
                    {
                        // эхо своей передачи (TX_MSG_TYPE) и пустые индикации пропускаем
                        if ((m.RxStatus & 0x0001) != 0 || m.Size == 0) continue;
                        var d = m.Data;
                        got.Add(d);
                        Log.Raw("<< " + Hex(d, 0) + string.Format("  [rx 0x{0:X}, t {1}]", m.RxStatus, m.Timestamp));
                    }
                    else break;
                }
            }
            return got;
        }

        // ---------- OBD по CAN (ISO 15765-4), физические запросы к 7E0–7E7 / 18DA10F1–18DA17F1 ----------
        static bool ObdCan(bool bits29)
        {
            Log.W("");
            Log.W(bits29 ? "== OBD по CAN 29 бит, 500 кбит/с ==" : "== OBD по CAN 11 бит, 500 кбит/с ==");
            uint flags = bits29 ? J.CAN_29BIT_ID : 0;
            uint ch = Connect(J.ISO15765, flags, 500000, "ISO15765");
            if (ch == uint.MaxValue) { Log.W("канал не открылся"); return false; }
            bool any = false;
            try
            {
                for (int i = 0; i < 8; i++)
                {
                    uint req = bits29 ? 0x18DA10F1u + (uint)(i << 8) : 0x7E0u + (uint)i;
                    uint resp = bits29 ? 0x18DAF110u + (uint)i : 0x7E8u + (uint)i;
                    Filter(ch, J.FLOW_CONTROL_FILTER, Id(bits29 ? 0x1FFFFFFFu : 0x7FFu), Id(resp), Id(req), J.ISO15765, flags);
                }
                for (int i = 0; i < 8; i++)
                {
                    uint req = bits29 ? 0x18DA10F1u + (uint)(i << 8) : 0x7E0u + (uint)i;
                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                    Send(ch, J.ISO15765, flags | J.ISO15765_FRAME_PAD, Cat(Id(req), 0x01, 0x00));
                    var got = Receive(ch, 120, 400);
                    bool ok = false;
                    foreach (var d in got) if (d.Length >= 6 && d[4] == 0x41) ok = true;
                    if (!ok) continue;
                    any = true;
                    Log.W(string.Format("Блок {0:X}: отвечает на OBD", req));
                    foreach (var q in new[] { new byte[] { 0x09, 0x02 }, new byte[] { 0x03 }, new byte[] { 0x07 }, new byte[] { 0x0A } })
                    {
                        pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                        Send(ch, J.ISO15765, flags | J.ISO15765_FRAME_PAD, Cat(Id(req), q));
                        foreach (var d in Receive(ch, 200, 1500))
                        {
                            if (d.Length < 5) continue;
                            byte svc = d[4];
                            if (svc == 0x49 && d.Length > 7) Log.W("  VIN: " + Ascii(d, 7));
                            else if (svc == 0x43 || svc == 0x47 || svc == 0x4A)
                                Log.W(string.Format("  режим {0:X2}: {1}", svc - 0x40, ObdCodes(d, 5)));
                            else Log.W("  " + Hex(d, 4));
                        }
                    }
                    Summary.Add(string.Format("OBD-блок {0:X} ({1})", req, bits29 ? "29 бит" : "11 бит"));
                }
                if (!any) Log.W("нет ответа");
            }
            finally { Disconnect(ch); }
            return any;
        }

        static string Ascii(byte[] d, int from)
        {
            var sb = new StringBuilder();
            for (int i = from; i < d.Length; i++) if (d[i] >= 0x20 && d[i] < 0x7F) sb.Append((char)d[i]);
            return sb.ToString();
        }

        /// <summary>Коды режимов 03/07/0A: по два байта (SAE J2012: старшие два бита — P/C/B/U).</summary>
        static string ObdCodes(byte[] d, int from)
        {
            var codes = new List<string>();
            // в CAN после 43 идёт число кодов
            for (int i = from + 1; i + 1 < d.Length; i += 2)
            {
                if (d[i] == 0 && d[i + 1] == 0) continue;
                codes.Add(Dtc2(d[i], d[i + 1]));
            }
            return codes.Count == 0 ? "кодов нет" : string.Join(", ", codes);
        }

        static string Dtc2(byte a, byte b) { return "PCBU"[a >> 6] + ((a >> 4) & 3).ToString() + (a & 0xF).ToString("X") + b.ToString("X2"); }

        // ---------- перебор блоков по сырому CAN: зонд 10 01 (смена сессии на обычную, без последствий) ----------
        static Dictionary<uint, uint> SweepCan(bool bits29)
        {
            Log.W("");
            Log.W(bits29 ? "== Поиск блоков CAN 29 бит (18DAxxF1) ==" : "== Поиск блоков CAN 11 бит (700–7FF) ==");
            var found = new Dictionary<uint, uint>();
            uint flags = bits29 ? J.CAN_29BIT_ID : 0;
            uint ch = Connect(J.CAN, flags, 500000, "CAN");
            if (ch == uint.MaxValue) { Log.W("канал не открылся"); return found; }
            try
            {
                // принимаем только ответы диагностики: 11 бит — 7xx, 29 бит — 18DAF1xx
                if (bits29) Filter(ch, J.PASS_FILTER, Id(0x1FFFFF00), Id(0x18DAF100), null, J.CAN, flags);
                else Filter(ch, J.PASS_FILTER, Id(0x700), Id(0x700), null, J.CAN, flags);
                var ids = new List<uint>();
                if (bits29) { for (uint x = 0; x <= 0xFF; x++) if (x != 0xF1 && x != 0x33) ids.Add(0x18DA00F1u | (x << 8)); }
                else { for (uint x = 0x700; x <= 0x7FF; x++) if (x != 0x7DF) ids.Add(x); }
                foreach (var req in ids)
                {
                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                    Send(ch, J.CAN, flags, Cat(Id(req), 0x02, 0x10, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00));
                    foreach (var d in Receive(ch, 40, 120))
                    {
                        if (d.Length < 7) continue;
                        uint rid = IdOf(d);
                        if (rid == req) continue;
                        // одиночный кадр: 50 01 (принял) или 7F 10 xx (отказал, но блок есть)
                        bool sf = (d[4] >> 4) == 0;
                        bool yes = sf && (d[5] == 0x50 || (d[5] == 0x7F && d[6] == 0x10));
                        if (yes && !found.ContainsKey(req))
                        {
                            found[req] = rid;
                            Log.W(string.Format("Блок {0:X} → отвечает с {1:X}: {2}", req, rid, Hex(d, 4)));
                        }
                    }
                }
                if (found.Count == 0) Log.W("ни один блок не ответил");
            }
            finally { Disconnect(ch); }
            return found;
        }

        // ---------- память ошибок найденного блока: UDS 19 02 FF, иначе KWP 18 02 FF 00; VIN и номер блока ----------
        static void ReadModule(uint req, uint resp, bool bits29)
        {
            Log.W("");
            Log.W(string.Format("== Блок {0:X} (ответ {1:X}) ==", req, resp));
            uint flags = bits29 ? J.CAN_29BIT_ID : 0;
            uint ch = Connect(J.ISO15765, flags, 500000, "ISO15765");
            if (ch == uint.MaxValue) return;
            try
            {
                Filter(ch, J.FLOW_CONTROL_FILTER, Id(bits29 ? 0x1FFFFFFFu : 0x7FFu), Id(resp), Id(req), J.ISO15765, flags);
                string codes = null;
                foreach (var q in new[] { new byte[] { 0x19, 0x02, 0xFF }, new byte[] { 0x18, 0x02, 0xFF, 0x00 }, new byte[] { 0x18, 0x00, 0xFF, 0x00 } })
                {
                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                    Send(ch, J.ISO15765, flags | J.ISO15765_FRAME_PAD, Cat(Id(req), q));
                    var got = Receive(ch, 300, 2500);
                    // 7F xx 78 — «подожди»: дослушиваем до 5 с
                    foreach (var d in got.ToArray())
                        if (d.Length >= 7 && d[4] == 0x7F && d[6] == 0x78) got.AddRange(Receive(ch, 1000, 5000));
                    // каждый вариант запроса — в журнал отдельно: на Elantra HD 2009 блок 7D4 на 18 02 FF 00 отдал 1 код,
                    // а Сканматик в ABS/ESP показал 4 — сравниваем ответы на разные группы статуса, а не берём первый
                    foreach (var d in got)
                    {
                        if (d.Length < 5) continue;
                        string c = null;
                        if (d[4] == 0x59) c = UdsCodes(d);
                        if (d[4] == 0x58) c = KwpCodes(d);
                        if (c == null) continue;
                        Log.W(string.Format("  {0}: {1}", Hex(q, 0), c));
                        if (codes == null || c.Length > codes.Length) codes = c;
                    }
                }
                Log.W("  ошибки: " + (codes ?? "память не прочитана"));
                foreach (var did in new[] { new byte[] { 0x22, 0xF1, 0x90 }, new byte[] { 0x22, 0xF1, 0x87 } })
                {
                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                    Send(ch, J.ISO15765, flags | J.ISO15765_FRAME_PAD, Cat(Id(req), did));
                    foreach (var d in Receive(ch, 200, 1200))
                        if (d.Length > 7 && d[4] == 0x62) Log.W(string.Format("  {0:X2}{1:X2}: {2}", did[1], did[2], Ascii(d, 7)));
                }
                Summary.Add(string.Format("Блок {0:X}: {1}", req, codes ?? "отвечает, память не прочитана"));
            }
            finally { Disconnect(ch); }
        }

        /// <summary>UDS 59 02: маска, затем записи по 4 байта (3 байта кода + статус). Статус без битов 0,1,2,3,5,7 — тест не завершён, не ошибка.</summary>
        static string UdsCodes(byte[] d)
        {
            var list = new List<string>();
            for (int i = 7; i + 3 < d.Length; i += 4)
            {
                byte st = d[i + 3];
                string code = Dtc2(d[i], d[i + 1]) + "-" + d[i + 2].ToString("X2");
                if ((st & 0xAF) == 0) continue;
                list.Add(code + " (статус " + st.ToString("X2") + ((st & 0x01) != 0 ? ", активна" : "") + ")");
            }
            return list.Count == 0 ? "кодов нет" : string.Join(", ", list);
        }

        /// <summary>KWP 58 n: записи по 3 байта (2 байта кода + статус).</summary>
        static string KwpCodes(byte[] d)
        {
            var list = new List<string>();
            for (int i = 6; i + 2 < d.Length; i += 3)
            {
                if (d[i] == 0 && d[i + 1] == 0) continue;
                list.Add(Dtc2(d[i], d[i + 1]) + " / " + ((d[i] << 8) | d[i + 1]).ToString("X4") + " (статус " + d[i + 2].ToString("X2") + ")");
            }
            return list.Count == 0 ? "кодов нет" : string.Join(", ", list);
        }

        // ---------- есть ли живой CAN на парах 3/11 и 12/13: только слушаем, ничего не передаём ----------
        static void ProbeOtherCan()
        {
            Log.W("");
            Log.W("== Проверка CAN на контактах 3/11 и 12/13 (только приём) ==");
            int[][] pairs = { new[] { 3, 11 }, new[] { 12, 13 } };
            foreach (var pr in pairs)
            {
                foreach (uint baud in new uint[] { 500000, 125000 })
                {
                    uint ch = Connect(J.CAN_PS, 0, baud, "CAN_PS");
                    if (ch == uint.MaxValue) { Log.W(string.Format("  {0}/{1}: канал CAN_PS не открылся", pr[0], pr[1])); break; }
                    try
                    {
                        int r = SetConfig(ch, J.J1962_PINS, (uint)((pr[0] << 8) | pr[1]));
                        if (r != J.STATUS_NOERROR) { Log.W(string.Format("  {0}/{1}: J1962_PINS {2}", pr[0], pr[1], pt.Why(r))); break; }
                        SetConfig(ch, J.DATA_RATE, baud);
                        Filter(ch, J.PASS_FILTER, Id(0), Id(0), null, J.CAN_PS, 0);
                        var got = Receive(ch, 300, 600);
                        if (got.Count > 0)
                        {
                            BusyPins.Add(pr[0]); BusyPins.Add(pr[1]);
                            Log.W(string.Format("  {0}/{1}: живой CAN {2} кбит/с ({3} кадров)", pr[0], pr[1], baud / 1000, got.Count));
                            Summary.Add(string.Format("CAN на контактах {0}/{1}, {2} кбит/с", pr[0], pr[1], baud / 1000));
                            break;
                        }
                        Log.W(string.Format("  {0}/{1} {2} кбит/с: тихо", pr[0], pr[1], baud / 1000));
                    }
                    finally { Disconnect(ch); }
                }
            }
        }

        // ---------- K-line на каждом контакте: быстрый init к адресам нашего сканера и 5-бод init к 0x33 ----------
        static readonly int[] KlineAddrs = { 0x10, 0x11, 0x20, 0x28, 0x27, 0x58, 0x30, 0x70, 0xA0, 0x36, 0x29, 0x60, 0x6A, 0x17, 0x18, 0x40, 0x12 };
        static readonly int[] KlinePins = { 7, 1, 3, 8, 9, 11, 12, 13 };

        static void SweepKline()
        {
            foreach (int pin in KlinePins)
            {
                Log.W("");
                if (BusyPins.Contains(pin)) { Log.W(string.Format("== K-line на контакте {0}: пропуск — на нём CAN ==", pin)); continue; }
                Log.W(string.Format("== K-line на контакте {0} ==", pin));
                uint ch = Connect(J.ISO14230_PS, 0, 10400, "ISO14230_PS");
                if (ch == uint.MaxValue) { Log.W("  канал ISO14230_PS не открылся — адаптер не поддерживает выбор контактов J2534-2"); return; }
                bool anyFast = false;
                try
                {
                    int r = SetConfig(ch, J.J1962_PINS, (uint)(pin << 8));
                    if (r != J.STATUS_NOERROR) { Log.W("  J1962_PINS: " + pt.Why(r)); continue; }
                    // без фильтра PASS адаптер отбрасывает все принятые сообщения (J2534-1; так и в открытой реализации
                    // panda J2534Connection.cpp). Ответ на FAST_INIT идёт через выход ioctl и приходит и без фильтра,
                    // поэтому в версии 1.1 ABS на контакте 8 «ответил», а его коды не дошли. Маска 00 — пропускать всё.
                    Filter(ch, J.PASS_FILTER, new byte[] { 0x00 }, new byte[] { 0x00 }, null, J.ISO14230_PS, 0);
                    foreach (int a in KlineAddrs)
                    {
                        using (var input = new Msg()) using (var output = new Msg())
                        {
                            // StartCommunication KWP2000: формат 81 (физический адрес, 1 байт данных), адрес, тестер F1, сервис 81.
                            // Контрольную сумму добавляет адаптер (ISO9141_NO_CHECKSUM не задан).
                            input.Protocol = J.ISO14230_PS; input.Data = new byte[] { 0x81, (byte)a, 0xF1, 0x81 };
                            output.Protocol = J.ISO14230_PS;
                            r = pt.Ioctl(ch, J.FAST_INIT, input.P, output.P);
                            Log.Raw(string.Format("FAST_INIT {0:X2}: {1} {2}", a, pt.Why(r), r == 0 ? Hex(output.Data, 0) : ""));
                            if (r != J.STATUS_NOERROR || output.Size == 0) continue;
                            anyFast = true;
                            Log.W(string.Format("  адрес {0:X2}: ответил ({1})", a, Hex(output.Data, 0)));
                            string codes = null;
                            bool refused22 = false;
                            // Elantra HD 2009, ABS на контакте 8 (лог 1.2): на 18 xx FF 00 — 7F 18 22 (conditionsNotCorrect, ISO 14230-3).
                            // Проверяем гипотезу «нужна сессия»: после отказа 22 — StartDiagnosticSession 10 81 (стандартная) и повтор.
                            for (int pass = 0; pass < 2 && codes == null; pass++)
                            {
                                if (pass == 1)
                                {
                                    if (!refused22) break;
                                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                                    Send(ch, J.ISO14230_PS, 0, new byte[] { 0x82, (byte)a, 0xF1, 0x10, 0x81 });
                                    foreach (var d in Receive(ch, 300, 1500)) Log.W("    10 81 → " + Hex(d, 3));
                                }
                                foreach (var q in new[] { new byte[] { 0x18, 0x00, 0xFF, 0x00 }, new byte[] { 0x18, 0x02, 0xFF, 0x00 } })
                                {
                                    var msg = Cat(new byte[] { (byte)(0x80 | q.Length), (byte)a, 0xF1 }, q);
                                    pt.Ioctl(ch, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero);
                                    Send(ch, J.ISO14230_PS, 0, msg);
                                    foreach (var d in Receive(ch, 300, 2000))
                                    {
                                        int k = Array.IndexOf(d, (byte)0x58);
                                        if (k >= 0 && k + 1 < d.Length) { codes = KwpCodesAt(d, k); break; }
                                        int n = Array.IndexOf(d, (byte)0x7F);
                                        if (n >= 0 && n + 2 < d.Length)
                                        {
                                            Log.W(string.Format("    {0:X2} {1:X2} … → отказ {2:X2}{3}", q[0], q[1], d[n + 2], d[n + 2] == 0x22 ? " (условия не те)" : ""));
                                            if (d[n + 2] == 0x22) refused22 = true;
                                        }
                                    }
                                    if (codes != null) break;
                                }
                            }
                            Log.W("    ошибки: " + (codes ?? "память не прочитана"));
                            Summary.Add(string.Format("K-line контакт {0}, адрес {1:X2}: {2}", pin, a, codes ?? "отвечает, память не прочитана"));
                            // StopCommunication
                            Send(ch, J.ISO14230_PS, 0, new byte[] { 0x81, (byte)a, 0xF1, 0x82 });
                            Receive(ch, 200, 600);
                        }
                    }
                }
                finally { Disconnect(ch); }
                if (!anyFast) Log.W("  быстрый init: никто не ответил");
                FiveBaud(pin);
            }
        }

        static string KwpCodesAt(byte[] d, int k)
        {
            var list = new List<string>();
            for (int i = k + 2; i + 2 < d.Length; i += 3)
            {
                if (d[i] == 0 && d[i + 1] == 0) continue;
                list.Add(Dtc2(d[i], d[i + 1]) + " / " + ((d[i] << 8) | d[i + 1]).ToString("X4") + " (статус " + d[i + 2].ToString("X2") + ")");
            }
            return list.Count == 0 ? "кодов нет" : string.Join(", ", list);
        }

        /// <summary>5-бодовая инициализация ISO 9141-2 к адресу 0x33 на контакте [pin]: ответ — ключевые байты.</summary>
        static void FiveBaud(int pin)
        {
            uint ch = Connect(J.ISO9141_PS, 0, 10400, "ISO9141_PS");
            if (ch == uint.MaxValue) return;
            IntPtr inBuf = Marshal.AllocHGlobal(1), outBuf = Marshal.AllocHGlobal(8);
            IntPtr inArr = Marshal.AllocHGlobal(IntPtr.Size * 2), outArr = Marshal.AllocHGlobal(IntPtr.Size * 2);
            try
            {
                int r = SetConfig(ch, J.J1962_PINS, (uint)(pin << 8));
                if (r != J.STATUS_NOERROR) { Log.Raw("5 бод J1962_PINS: " + pt.Why(r)); return; }
                Marshal.WriteByte(inBuf, 0x33);
                Marshal.WriteInt32(inArr, 0, 1); Marshal.WriteIntPtr(inArr, IntPtr.Size, inBuf);
                Marshal.WriteInt32(outArr, 0, 8); Marshal.WriteIntPtr(outArr, IntPtr.Size, outBuf);
                r = pt.Ioctl(ch, J.FIVE_BAUD_INIT, inArr, outArr);
                int n = Marshal.ReadInt32(outArr, 0);
                var kb = new byte[Math.Max(0, Math.Min(n, 8))];
                Marshal.Copy(outBuf, kb, 0, kb.Length);
                if (r == J.STATUS_NOERROR)
                {
                    Log.W(string.Format("  5 бод к 33: ответил, ключевые байты {0}", Hex(kb, 0)));
                    Summary.Add(string.Format("K-line контакт {0}: 5-бод init к 33 прошёл ({1})", pin, Hex(kb, 0)));
                }
                else Log.W("  5 бод к 33: " + pt.Why(r));
            }
            finally
            {
                Marshal.FreeHGlobal(inBuf); Marshal.FreeHGlobal(outBuf); Marshal.FreeHGlobal(inArr); Marshal.FreeHGlobal(outArr);
                Disconnect(ch);
            }
        }
    }
}
