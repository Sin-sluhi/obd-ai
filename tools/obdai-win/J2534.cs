// Слой SAE J2534 v04.04 (+J2534-2 выбор контактов) для OBD AI под Windows. Только чтение.
// Константы, структуры, сигнатуры — J2534_v0404.h драйвера comma.ai panda (MIT). Проверено на Scanmatik SM2 PRO
// по Bluetooth на Hyundai Elantra HD 2009 (OBIDI J2534 Probe 1.1–1.4, логи в tools/j2534/logs).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace ObdAi
{
    static class J
    {
        public const uint J1850VPW = 0x01, J1850PWM = 0x02, ISO9141 = 0x03, ISO14230 = 0x04, CAN = 0x05, ISO15765 = 0x06;
        public const uint ISO9141_PS = 0x8002, ISO14230_PS = 0x8003, CAN_PS = 0x8004, ISO15765_PS = 0x8005;
        public const uint CAN_29BIT_ID = 0x0100, ISO15765_FRAME_PAD = 0x0040;
        public const uint PASS_FILTER = 1, FLOW_CONTROL_FILTER = 3;
        public const uint SET_CONFIG = 0x02, READ_VBATT = 0x03, FIVE_BAUD_INIT = 0x04, FAST_INIT = 0x05, CLEAR_RX_BUFFER = 0x08;
        public const uint DATA_RATE = 0x01, J1962_PINS = 0x8001;
        // RxStatus (J2534-1 §7.3.3): эхо своей передачи и индикация начала многокадрового приёма
        public const uint TX_MSG_TYPE = 0x01, START_OF_MESSAGE = 0x02;
        public const int STATUS_NOERROR = 0, ERR_DEVICE_NOT_CONNECTED = 8, ERR_TIMEOUT = 9, ERR_BUFFER_EMPTY = 0x10, ERR_DEVICE_IN_USE = 0x0E;

        static readonly string[] ErrNames = {
            "STATUS_NOERROR", "ERR_NOT_SUPPORTED", "ERR_INVALID_CHANNEL_ID", "ERR_INVALID_PROTOCOL_ID", "ERR_NULL_PARAMETER",
            "ERR_INVALID_IOCTL_VALUE", "ERR_INVALID_FLAGS", "ERR_FAILED", "ERR_DEVICE_NOT_CONNECTED", "ERR_TIMEOUT",
            "ERR_INVALID_MSG", "ERR_INVALID_TIME_INTERVAL", "ERR_EXCEEDED_LIMIT", "ERR_INVALID_MSG_ID", "ERR_DEVICE_IN_USE",
            "ERR_INVALID_IOCTL_ID", "ERR_BUFFER_EMPTY", "ERR_BUFFER_FULL", "ERR_BUFFER_OVERFLOW", "ERR_PIN_INVALID",
            "ERR_CHANNEL_IN_USE", "ERR_MSG_PROTOCOL_ID", "ERR_INVALID_FILTER_ID", "ERR_NO_FLOW_CONTROL", "ERR_NOT_UNIQUE",
            "ERR_INVALID_BAUDRATE", "ERR_INVALID_DEVICE_ID" };
        public static string Name(int code) { return code >= 0 && code < ErrNames.Length ? ErrNames[code] : "0x" + code.ToString("X"); }
        public const int MSG_SIZE = 24 + 4128;
    }

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
        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] public static extern IntPtr LoadLibraryEx(string path, IntPtr reserved, uint flags);
        // зависимые DLL ищутся в папке загружаемой (драйвер Bluetooth Сканматика сам грузит smj2534.dll)
        public const uint LOAD_WITH_ALTERED_SEARCH_PATH = 0x00000008;
        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Ansi)] public static extern IntPtr GetProcAddress(IntPtr h, string name);
    }

    sealed class Msg : IDisposable
    {
        public readonly IntPtr P;
        public Msg() { P = Marshal.AllocHGlobal(J.MSG_SIZE); Clear(); }
        public void Clear() { for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(P, i, 0); }
        public uint Protocol { set { Marshal.WriteInt32(P, 0, (int)value); } }
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
        public string Name, Vendor, Library;
        public bool Wireless { get { var n = (Name + " " + Library).ToLowerInvariant(); return n.Contains("bluetooth") || n.Contains("bth") || n.Contains("wireless") || n.Contains("wifi"); } }
        public override string ToString() { return Name + (string.IsNullOrEmpty(Vendor) ? "" : " · " + Vendor); }

        public static List<Device> Find()
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
                            using (var k = root.OpenSubKey(sub))
                            {
                                if (k == null) continue;
                                var lib = k.GetValue("FunctionLibrary") as string;
                                if (string.IsNullOrEmpty(lib)) continue;
                                if (list.Exists(x => string.Equals(x.Library, lib, StringComparison.OrdinalIgnoreCase))) continue;
                                list.Add(new Device { Name = (k.GetValue("Name") as string) ?? sub, Vendor = (k.GetValue("Vendor") as string) ?? "", Library = lib });
                            }
                    }
                }
                catch { }
            }
            return list;
        }

        /// <summary>Разрядность DLL по заголовку PE: 32, 64, 0 — не прочитать.</summary>
        public int Bits()
        {
            try
            {
                using (var f = new BinaryReader(File.OpenRead(Library)))
                {
                    f.BaseStream.Seek(0x3C, SeekOrigin.Begin); int pe = f.ReadInt32();
                    f.BaseStream.Seek(pe + 4, SeekOrigin.Begin); ushort m = f.ReadUInt16();
                    return m == 0x14C ? 32 : m == 0x8664 ? 64 : 0;
                }
            }
            catch { return 0; }
        }
    }

    /// <summary>Журнал обмена: файл рядом с отчётами + строки для ленты на экране.</summary>
    sealed class Trace
    {
        readonly StreamWriter file;
        public readonly string PathName;
        public Action<string> OnEvent;
        public Trace(string dir)
        {
            Directory.CreateDirectory(dir);
            PathName = Path.Combine(dir, "obdai-log-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt");
            file = new StreamWriter(PathName, false, new UTF8Encoding(false)) { AutoFlush = true };
        }
        public void Raw(string s) { lock (file) file.WriteLine(DateTime.Now.ToString("HH:mm:ss.fff") + "  " + s); }
        /// <summary>Событие для пользователя: и в файл, и на экран.</summary>
        public void Ev(string s) { Raw("# " + s); var e = OnEvent; if (e != null) e(s); }
        public void Close() { lock (file) file.Close(); }
    }

    sealed class Pt
    {
        public PtOpen Open; public PtClose Close; public PtConnect Connect; public PtDisconnect Disconnect;
        public PtReadMsgs Read; public PtWriteMsgs Write; public PtStartMsgFilter Filter; public PtIoctl Ioctl;
        public PtReadVersion Version; public PtGetLastError LastError;

        static T F<T>(IntPtr h, string name) where T : class
        {
            IntPtr p = Native.GetProcAddress(h, name);
            if (p == IntPtr.Zero) throw new Exception("в драйвере адаптера нет функции " + name);
            return Marshal.GetDelegateForFunctionPointer(p, typeof(T)) as T;
        }

        public static Pt Load(string path)
        {
            IntPtr h = Native.LoadLibraryEx(path, IntPtr.Zero, Native.LOAD_WITH_ALTERED_SEARCH_PATH);
            if (h == IntPtr.Zero) throw new Exception("не загрузился драйвер адаптера " + path + " (код Windows " + Marshal.GetLastWin32Error() + ")");
            var p = new Pt();
            p.Open = F<PtOpen>(h, "PassThruOpen"); p.Close = F<PtClose>(h, "PassThruClose");
            p.Connect = F<PtConnect>(h, "PassThruConnect"); p.Disconnect = F<PtDisconnect>(h, "PassThruDisconnect");
            p.Read = F<PtReadMsgs>(h, "PassThruReadMsgs"); p.Write = F<PtWriteMsgs>(h, "PassThruWriteMsgs");
            p.Filter = F<PtStartMsgFilter>(h, "PassThruStartMsgFilter"); p.Ioctl = F<PtIoctl>(h, "PassThruIoctl");
            p.Version = F<PtReadVersion>(h, "PassThruReadVersion"); p.LastError = F<PtGetLastError>(h, "PassThruGetLastError");
            return p;
        }

        public string Why(int code)
        {
            if (code == J.STATUS_NOERROR) return "OK";
            var sb = new StringBuilder(256);
            try { LastError(sb); } catch { }
            return J.Name(code) + (sb.Length > 0 ? " (" + sb + ")" : "");
        }
    }

    /// <summary>Открытый канал J2534: отправка, приём, фильтры. Всё пишется в журнал.</summary>
    sealed class Chan : IDisposable
    {
        readonly Pt pt; readonly Trace log;
        public readonly uint Id, Protocol, Flags;
        public bool Ok { get { return Id != uint.MaxValue; } }

        Chan(Pt pt, Trace log, uint id, uint protocol, uint flags) { this.pt = pt; this.log = log; Id = id; Protocol = protocol; Flags = flags; }

        /// <summary>pins — 0xPPSS для протоколов *_PS (J2534-2), 0 — без выбора контактов.</summary>
        public static Chan Open(Pt pt, uint dev, Trace log, uint protocol, uint flags, uint baud, uint pins, string what)
        {
            uint ch;
            int r = pt.Connect(dev, protocol, flags, baud, out ch);
            log.Raw(string.Format("Connect {0} (proto 0x{1:X}, flags 0x{2:X}, {3}{4}): {5}", what, protocol, flags, baud, pins != 0 ? ", pins " + pins.ToString("X4") : "", pt.Why(r)));
            if (r != J.STATUS_NOERROR) return new Chan(pt, log, uint.MaxValue, protocol, flags);
            var c = new Chan(pt, log, ch, protocol, flags);
            if (pins != 0)
            {
                r = c.SetConfig(J.J1962_PINS, pins);
                if (r != J.STATUS_NOERROR) { log.Raw("J1962_PINS " + pins.ToString("X4") + ": " + pt.Why(r)); c.Dispose(); return new Chan(pt, log, uint.MaxValue, protocol, flags); }
                c.SetConfig(J.DATA_RATE, baud);
            }
            return c;
        }

        public int SetConfig(uint param, uint value)
        {
            IntPtr cfg = Marshal.AllocHGlobal(8), list = Marshal.AllocHGlobal(IntPtr.Size * 2);
            try
            {
                Marshal.WriteInt32(cfg, 0, (int)param); Marshal.WriteInt32(cfg, 4, (int)value);
                Marshal.WriteInt32(list, 0, 1); Marshal.WriteIntPtr(list, IntPtr.Size, cfg);
                return pt.Ioctl(Id, J.SET_CONFIG, list, IntPtr.Zero);
            }
            finally { Marshal.FreeHGlobal(cfg); Marshal.FreeHGlobal(list); }
        }

        /// <summary>Фильтр. У FLOW_CONTROL флаги кадра управления потоком (FC) берутся из TxFlags образца FC:
        /// без ISO15765_FRAME_PAD адаптер шлёт FC длиной 3 байта, и блоки, ждущие 8 байт, его не принимают
        /// (на Elantra HD VIN 09 02 остался первым кадром, лог Probe 1.4) — поэтому FC всегда с добивкой.</summary>
        public void Filter(uint type, byte[] mask, byte[] pattern, byte[] flow)
        {
            using (var m = new Msg()) using (var p = new Msg()) using (var f = new Msg())
            {
                uint fl = Flags | (flow != null && (Protocol == J.ISO15765 || Protocol == J.ISO15765_PS) ? J.ISO15765_FRAME_PAD : 0);
                m.Protocol = Protocol; m.TxFlags = fl; m.Data = mask;
                p.Protocol = Protocol; p.TxFlags = fl; p.Data = pattern;
                IntPtr fp = IntPtr.Zero;
                if (flow != null) { f.Protocol = Protocol; f.TxFlags = fl; f.Data = flow; fp = f.P; }
                uint id;
                int r = pt.Filter(Id, type, m.P, p.P, fp, out id);
                if (r != J.STATUS_NOERROR) log.Raw("Filter " + B.Hex(pattern) + ": " + pt.Why(r));
            }
        }

        public void PassAll() { Filter(J.PASS_FILTER, new byte[] { 0 }, new byte[] { 0 }, null); }
        public void ClearRx() { pt.Ioctl(Id, J.CLEAR_RX_BUFFER, IntPtr.Zero, IntPtr.Zero); }

        /// <summary>Отправка. Таймаут записи 1000 мс: у ISO 15765 запись ждёт, пока адаптер освободится от прошлого обмена.</summary>
        public int Send(byte[] data, uint extraFlags)
        {
            using (var m = new Msg())
            {
                m.Protocol = Protocol; m.TxFlags = Flags | extraFlags; m.Data = data;
                uint n = 1;
                int r = pt.Write(Id, m.P, ref n, 1000);
                log.Raw(">> " + B.Hex(data) + (r == J.STATUS_NOERROR ? "" : "  " + pt.Why(r)));
                return r;
            }
        }

        /// <summary>Читать, пока идут сообщения: window — тишина, после которой конец; total — общий предел.
        /// Индикация START_OF_MESSAGE (первый кадр многокадрового ответа) продлевает ожидание до total —
        /// само сообщение придёт целиком позже (в Probe 1.4 тут обрывались и теряли VIN).</summary>
        public List<byte[]> Receive(int windowMs, int totalMs)
        {
            var got = new List<byte[]>();
            var sw = Stopwatch.StartNew();
            bool assembling = false;
            using (var m = new Msg())
            {
                while (sw.ElapsedMilliseconds < totalMs)
                {
                    m.Clear();
                    uint n = 1;
                    int left = (int)(totalMs - sw.ElapsedMilliseconds);
                    int r = pt.Read(Id, m.P, ref n, (uint)Math.Max(10, Math.Min(assembling ? left : windowMs, left)));
                    if (r == J.STATUS_NOERROR && n > 0)
                    {
                        uint st = m.RxStatus;
                        if ((st & J.TX_MSG_TYPE) != 0) continue;
                        var d = m.Data;
                        if ((st & J.START_OF_MESSAGE) != 0) { assembling = true; log.Raw("<< " + B.Hex(d) + "  [начало многокадрового ответа]"); continue; }
                        if (d.Length == 0) continue;
                        assembling = false;
                        got.Add(d);
                        log.Raw("<< " + B.Hex(d) + string.Format("  [rx 0x{0:X}]", st));
                    }
                    else if (assembling && sw.ElapsedMilliseconds < totalMs) continue;
                    else break;
                }
            }
            if (assembling) log.Raw("   многокадровый ответ не пришёл целиком за " + totalMs + " мс");
            return got;
        }

        /// <summary>Быстрый init KWP2000 (ISO 14230-2): ответ StartCommunication через выход ioctl.</summary>
        public byte[] FastInit(byte[] start)
        {
            using (var input = new Msg()) using (var output = new Msg())
            {
                input.Protocol = Protocol; input.Data = start; output.Protocol = Protocol;
                int r = pt.Ioctl(Id, J.FAST_INIT, input.P, output.P);
                byte[] resp = r == J.STATUS_NOERROR ? output.Data : null;
                log.Raw(string.Format("FAST_INIT {0}: {1} {2}", B.Hex(start), pt.Why(r), resp != null ? B.Hex(resp) : ""));
                return resp != null && resp.Length > 0 ? resp : null;
            }
        }

        /// <summary>5-бодовый init к адресу: ключевые байты или null.</summary>
        public byte[] FiveBaud(byte addr)
        {
            IntPtr inBuf = Marshal.AllocHGlobal(1), outBuf = Marshal.AllocHGlobal(8);
            IntPtr inArr = Marshal.AllocHGlobal(IntPtr.Size * 2), outArr = Marshal.AllocHGlobal(IntPtr.Size * 2);
            try
            {
                Marshal.WriteByte(inBuf, addr);
                Marshal.WriteInt32(inArr, 0, 1); Marshal.WriteIntPtr(inArr, IntPtr.Size, inBuf);
                Marshal.WriteInt32(outArr, 0, 8); Marshal.WriteIntPtr(outArr, IntPtr.Size, outBuf);
                int r = pt.Ioctl(Id, J.FIVE_BAUD_INIT, inArr, outArr);
                int n = Marshal.ReadInt32(outArr, 0);
                var kb = new byte[Math.Max(0, Math.Min(n, 8))];
                Marshal.Copy(outBuf, kb, 0, kb.Length);
                log.Raw(string.Format("FIVE_BAUD {0:X2}: {1} {2}", addr, pt.Why(r), B.Hex(kb)));
                return r == J.STATUS_NOERROR ? kb : null;
            }
            finally { Marshal.FreeHGlobal(inBuf); Marshal.FreeHGlobal(outBuf); Marshal.FreeHGlobal(inArr); Marshal.FreeHGlobal(outArr); }
        }

        public void Dispose() { if (Ok) pt.Disconnect(Id); }
    }

    static class B
    {
        public static byte[] Id(uint id) { return new byte[] { (byte)(id >> 24), (byte)(id >> 16), (byte)(id >> 8), (byte)id }; }
        public static uint IdOf(byte[] d) { return d.Length < 4 ? 0 : ((uint)d[0] << 24) | ((uint)d[1] << 16) | ((uint)d[2] << 8) | d[3]; }
        public static string Hex(byte[] b) { return Hex(b, 0); }
        public static string Hex(byte[] b, int from)
        {
            if (b == null) return "";
            var sb = new StringBuilder();
            for (int i = from; i < b.Length; i++) { if (sb.Length > 0) sb.Append(' '); sb.Append(b[i].ToString("X2")); }
            return sb.ToString();
        }
        public static byte[] Cat(byte[] a, params byte[] b) { var r = new byte[a.Length + b.Length]; a.CopyTo(r, 0); b.CopyTo(r, a.Length); return r; }
        public static string Ascii(byte[] d, int from)
        {
            var sb = new StringBuilder();
            for (int i = from; i < d.Length; i++) if (d[i] >= 0x20 && d[i] < 0x7F) sb.Append((char)d[i]);
            return sb.ToString().Trim();
        }
        /// <summary>SAE J2012: старшие два бита — P/C/B/U.</summary>
        public static string Dtc(byte a, byte b) { return "PCBU"[a >> 6] + ((a >> 4) & 3).ToString() + (a & 0xF).ToString("X") + b.ToString("X2"); }
    }
}
