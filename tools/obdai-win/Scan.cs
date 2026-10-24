// Проверка машины через J2534: все линии колодки, которые умеет адаптер, только чтение.
// Порядок и решения — по логам Probe 1.1–1.4 (Elantra HD 2009, Scanmatik SM2 PRO) и docs/ОСНОВА ОПРОСА.md.
// Что исправлено против Probe 1.4 (аудит 24.10.2026):
//  * FC с добивкой до 8 байт и ожидание целого многокадрового ответа — VIN и длинные списки кодов не теряются;
//  * запись ждёт до 1000 мс, следующий запрос не уходит, пока адаптер принимает прошлый ответ (терялся режим 03);
//  * OBD по CAN на 500 и 250 кбит/с, 11 и 29 бит; без CAN — K-line OBD (быстрый init C1 33 F1 81, 5 бод к 33) и J1850;
//  * блоки CAN: зонд 10 01, в глубокой проверке ещё 3E 00 для промолчавших; KWP по CAN: 7F 18 22 → сессия 10 81 и повтор;
//  * K-line: контакты 7, 8, 1, 3, 9, 11, 12, 13 (+ 6 без CAN на 6/14, + 2 в глубокой), в глубокой — все адреса 01–FE
//    на 7 и 8 и 5-бодовый init к адресам блоков;
//  * готовность (01 01: лампа, число кодов), стоп-кадр (02 02), постоянные коды (0A).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Threading;

namespace ObdAi
{
    sealed class Code
    {
        public string Id;          // P0068, C1202, B1602-1A
        public string Base { get { int i = Id.IndexOf('-'); return i > 0 ? Id.Substring(0, i) : Id; } }
        public string State;       // active / stored / pending / permanent / history
        public string Raw;         // статус словами для журнала
        public string Module;
    }

    sealed class Module
    {
        public string Name, Kind;  // kind: engine, gearbox, abs, airbag, cluster, body, eps, other
        public string Where;       // «CAN 7D4→7DC», «K-line, контакт 8, адрес 28»
        public string Proto;       // UDS / KWP2000 / OBD
        public bool Read;          // память ошибок прочитана
        public uint CanReq;        // адрес запроса CAN (0 — не CAN)
        public int KAddr = -1;     // адрес K-line
        public string Note;        // почему не прочитана, номер блока
        public readonly List<Code> Codes = new List<Code>();
    }

    sealed class ScanResult
    {
        public string Vin, Adapter, Firmware, LogPath, Brand, BrandKey, CarText;
        public double Volts = double.NaN;
        public bool ObdFound; public string ObdProto;
        public bool Mil; public int MilCount = -1;
        public string Freeze;
        public readonly List<Module> Modules = new List<Module>();
        public readonly List<string> Lines = new List<string>();   // какие линии проверены и что на них
        public TimeSpan Took;
        public bool Deep, Cancelled;
        public string Error;
        public IEnumerable<Code> AllCodes { get { return Modules.SelectMany(m => m.Codes); } }
    }

    sealed class Scanner
    {
        readonly Device device; readonly Trace log; readonly bool deep;
        Pt pt; uint dev;
        public Action<string, double> Stage;   // текст этапа, доля 0..1
        public volatile bool Cancel;
        readonly ScanResult res = new ScanResult();
        readonly HashSet<int> busyPins = new HashSet<int>();
        uint canBaud = 500000;

        public Scanner(Device device, Trace log, bool deep) { this.device = device; this.log = log; this.deep = deep; }

        void St(string s, double p) { var a = Stage; if (a != null) a(s, p); log.Raw("## " + s); }
        void Check() { if (Cancel) throw new OperationCanceledException(); }

        public ScanResult Run()
        {
            var sw = Stopwatch.StartNew();
            res.Deep = deep; res.Adapter = device.ToString(); res.LogPath = log.PathName;
            log.Raw("OBD AI для Windows " + App.Version + (Environment.Is64BitProcess ? " x64" : " x86") + ", глубокая: " + deep);
            log.Raw("Адаптер: " + device.Name + " → " + device.Library);
            try
            {
                St("Связь с адаптером", 0.01);
                pt = device.Library == Sim.Lib ? Sim.Make() : Pt.Load(device.Library);
                int r = pt.Open(IntPtr.Zero, out dev);
                log.Raw("PassThruOpen: " + pt.Why(r));
                if (r != J.STATUS_NOERROR)
                {
                    res.Error = r == J.ERR_DEVICE_IN_USE || r == J.ERR_DEVICE_NOT_CONNECTED
                        ? "Адаптер не отвечает. Закройте программу Сканматик полностью (и значок у часов), проверьте, что адаптер в колодке и светится, и нажмите ещё раз."
                        : "Адаптер не открылся: " + pt.Why(r);
                    return res;
                }
                try { Body(); }
                catch (OperationCanceledException) { res.Cancelled = true; log.Ev("Проверка остановлена"); }
                finally { r = pt.Close(dev); log.Raw("PassThruClose: " + pt.Why(r)); }
            }
            catch (Exception e) { res.Error = e.Message; log.Raw(e.ToString()); }
            res.Took = sw.Elapsed;
            Names.Apply(res);
            log.Raw("Итог: " + res.Modules.Count + " блоков, кодов " + res.AllCodes.Count() + ", за " + (int)res.Took.TotalSeconds + " с");
            foreach (var m in res.Modules) log.Raw("  " + m.Name + " [" + m.Where + "] " + (m.Read ? string.Join(", ", m.Codes.Select(c => c.Id + "/" + c.State)) : "не прочитан: " + m.Note));
            return res;
        }

        void Body()
        {
            var fw = new StringBuilder(256); var dl = new StringBuilder(256); var api = new StringBuilder(256);
            int r = pt.Version(dev, fw, dl, api);
            res.Firmware = fw.ToString();
            log.Raw(string.Format("Версии: прошивка {0}, DLL {1}, API {2} ({3})", fw, dl, api, pt.Why(r)));
            Battery();

            St("Двигатель: стандартный OBD", 0.04);
            bool can = ObdCan();
            Check();
            if (!can)
            {
                St("Двигатель: K-line и J1850", 0.12);
                ObdKline();
                Check();
                if (!res.ObdFound) ObdJ1850();
            }
            Check();

            St("Блоки на CAN (11 бит)", 0.18);
            var found11 = SweepCan(false, 0.18, 0.30);
            Check();
            St("Блоки на CAN (29 бит)", 0.30);
            var found29 = SweepCan(true, 0.30, 0.40);
            Check();
            int i = 0, total = found11.Count + found29.Count;
            foreach (var kv in found11) { St("Читаю блоки CAN: " + (++i) + " из " + total, 0.40 + 0.12 * i / Math.Max(1, total)); ReadCanModule(kv.Key, kv.Value, false, J.ISO15765, 0, canBaud); Check(); }
            foreach (var kv in found29) { St("Читаю блоки CAN: " + (++i) + " из " + total, 0.40 + 0.12 * i / Math.Max(1, total)); ReadCanModule(kv.Key, kv.Value, true, J.ISO15765, 0, canBaud); Check(); }

            St("Другие шины CAN (контакты 3/11, 12/13)", 0.52);
            OtherCan();
            Check();
            St("K-line по контактам колодки", 0.56);
            SweepKline(0.56, 0.98);
            St("Готово", 1);
        }

        void Battery()
        {
            IntPtr v = System.Runtime.InteropServices.Marshal.AllocHGlobal(4);
            try
            {
                int r = pt.Ioctl(dev, J.READ_VBATT, IntPtr.Zero, v);
                if (r == J.STATUS_NOERROR) { res.Volts = System.Runtime.InteropServices.Marshal.ReadInt32(v) / 1000.0; log.Ev(string.Format("Напряжение на колодке {0:0.0} В", res.Volts)); }
                else log.Raw("READ_VBATT: " + pt.Why(r));
            }
            finally { System.Runtime.InteropServices.Marshal.FreeHGlobal(v); }
        }

        Chan Open(uint protocol, uint flags, uint baud, uint pins, string what) { return Chan.Open(pt, dev, log, protocol, flags, baud, pins, what); }

        // ---------- OBD по CAN: 11/29 бит, 500/250 кбит/с, физические адреса ----------
        bool ObdCan()
        {
            foreach (uint baud in new uint[] { 500000, 250000 })
                foreach (bool b29 in new[] { false, true })
                {
                    Check();
                    if (ObdCanAt(b29, baud)) { canBaud = baud; res.Lines.Add("CAN (контакты 6/14) " + baud / 1000 + " кбит/с, " + (b29 ? "29" : "11") + " бит — стандартный OBD отвечает"); return true; }
                }
            res.Lines.Add("CAN (контакты 6/14): стандартный OBD не ответил на 500 и 250 кбит/с");
            return false;
        }

        bool ObdCanAt(bool b29, uint baud)
        {
            uint flags = b29 ? J.CAN_29BIT_ID : 0;
            using (var ch = Open(J.ISO15765, flags, baud, 0, "ISO15765 OBD"))
            {
                if (!ch.Ok) return false;
                for (int i = 0; i < 8; i++)
                    ch.Filter(J.FLOW_CONTROL_FILTER, B.Id(b29 ? 0x1FFFFFFFu : 0x7FFu), B.Id(ObdResp(b29, i)), B.Id(ObdReq(b29, i)));
                bool any = false;
                for (int i = 0; i < 8; i++)
                {
                    Check();
                    uint req = ObdReq(b29, i);
                    var a = Ask(ch, req, new byte[] { 0x01, 0x00 }, 150, 500);
                    if (a == null || a[0] != 0x41) continue;
                    any = true;
                    res.ObdFound = true; res.ObdProto = "ISO 15765-4 CAN " + (b29 ? "29" : "11") + " бит " + baud / 1000 + " кбит/с";
                    uint resp = ObdResp(b29, i);
                    var m = new Module { Where = string.Format("CAN {0:X}→{1:X}", req, resp), Proto = "OBD", Kind = i == 0 ? "engine" : i == 1 ? "gearbox" : "other", CanReq = req };
                    m.Name = i == 0 ? "Двигатель" : i == 1 ? "Коробка передач" : "Блок OBD " + req.ToString("X");
                    log.Ev(m.Name + " отвечает (" + m.Where + ")");
                    ReadObd(m, q => Ask(ch, req, q, 300, 3000));
                    AddModule(m, req);
                }
                return any;
            }
        }

        static uint ObdReq(bool b29, int i) { return b29 ? 0x18DA10F1u + (uint)(i << 8) : 0x7E0u + (uint)i; }
        static uint ObdResp(bool b29, int i) { return b29 ? 0x18DAF110u + (uint)i : 0x7E8u + (uint)i; }

        /// <summary>Запрос по ISO 15765 и первый ответ блока (без 4 байт ID). 7F xx 78 — ждём настоящий ответ до 5 с.</summary>
        byte[] Ask(Chan ch, uint req, byte[] q, int window, int total)
        {
            ch.ClearRx();
            if (ch.Send(B.Cat(B.Id(req), q), J.ISO15765_FRAME_PAD) != J.STATUS_NOERROR) return null;
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < total + 5000)
            {
                var got = ch.Receive(window, total);
                byte[] pending = null;
                foreach (var d in got)
                {
                    if (d.Length < 5) continue;
                    var p = d.Skip(4).ToArray();
                    if (p[0] == 0x7F && p.Length >= 3 && p[2] == 0x78) { pending = p; continue; }
                    if (p[0] == q[0] + 0x40 || (p[0] == 0x7F && p.Length >= 2 && p[1] == q[0])) return p;
                }
                if (pending == null) return null;
                window = 1500; total = 5000;
            }
            return null;
        }

        /// <summary>Стандартный OBD у блока: готовность, VIN, режимы 03/07/0A, стоп-кадр.</summary>
        void ReadObd(Module m, Func<byte[], byte[]> ask)
        {
            var a = ask(new byte[] { 0x01, 0x01 });
            if (a != null && a[0] == 0x41 && a.Length >= 3 && m.Kind == "engine")
            {
                res.Mil = (a[2] & 0x80) != 0; res.MilCount = a[2] & 0x7F;
                log.Ev("Лампа «Check Engine»: " + (res.Mil ? "горит" : "не горит") + ", кодов по счётчику блока: " + res.MilCount);
            }
            if (res.Vin == null)
            {
                a = ask(new byte[] { 0x09, 0x02 });
                if (a != null && a[0] == 0x49 && a.Length > 3)
                {
                    var vin = B.Ascii(a, 3);
                    if (vin.Length >= 17) vin = vin.Substring(vin.Length - 17);
                    if (vin.Length == 17) { res.Vin = vin; log.Ev("VIN: " + vin); }
                }
                else log.Raw("VIN не получен: " + (a == null ? "нет ответа" : B.Hex(a)));
            }
            bool any = false;
            foreach (var mode in new byte[] { 0x03, 0x07, 0x0A })
            {
                a = ask(new[] { mode });
                if (a == null) continue;
                if (a[0] == 0x7F) { log.Raw(string.Format("режим {0:X2}: отказ {1}", mode, B.Hex(a))); continue; }
                if (a[0] != mode + 0x40) continue;
                if (mode == 0x03) any = true;
                // в CAN за номером режима идёт число кодов, затем по 2 байта
                int from = m.Proto == "OBD" && m.Where.StartsWith("CAN") ? 2 : 1;
                for (int i = from; i + 1 < a.Length; i += 2)
                {
                    if (a[i] == 0 && a[i + 1] == 0) continue;
                    AddCode(m, B.Dtc(a[i], a[i + 1]), mode == 0x03 ? "stored" : mode == 0x07 ? "pending" : "permanent", "режим " + mode.ToString("X2"));
                }
            }
            m.Read = any;
            if (!any) m.Note = "режим 03 не ответил";
            if (m.Kind == "engine")
            {
                a = ask(new byte[] { 0x02, 0x02, 0x00 });
                if (a != null && a[0] == 0x42 && a.Length >= 5 && (a[3] != 0 || a[4] != 0))
                {
                    res.Freeze = B.Dtc(a[3], a[4]);
                    log.Ev("Стоп-кадр записан по коду " + res.Freeze);
                }
            }
        }

        void AddCode(Module m, string id, string state, string raw)
        {
            var old = m.Codes.FirstOrDefault(c => c.Base == id.Split('-')[0]);
            if (old != null)
            {
                // самое серьёзное состояние важнее: активна > в памяти > неподтверждённая
                if (Rank(state) > Rank(old.State)) { old.State = state; old.Raw = raw; }
                return;
            }
            m.Codes.Add(new Code { Id = id, State = state, Raw = raw, Module = m.Name });
        }

        static int Rank(string s) { return s == "active" ? 4 : s == "stored" ? 3 : s == "permanent" ? 2 : s == "pending" ? 1 : 0; }

        void AddModule(Module m, uint key)
        {
            res.Modules.Add(m);
            if (m.Read) log.Ev(m.Name + ": " + (m.Codes.Count == 0 ? "ошибок нет" : string.Join(", ", m.Codes.Select(c => c.Id))));
            else log.Ev(m.Name + ": память ошибок не прочитана" + (m.Note != null ? " (" + m.Note + ")" : ""));
        }

        // ---------- OBD без CAN: K-line на контакте 7 ----------
        void ObdKline()
        {
            // ISO 14230-4: быстрый init функциональным адресом 33 (формат C1)
            using (var ch = Open(J.ISO14230_PS, 0, 10400, 7 << 8, "ISO14230 OBD"))
                if (ch.Ok)
                {
                    ch.PassAll();
                    var kb = ch.FastInit(new byte[] { 0xC1, 0x33, 0xF1, 0x81 });
                    if (kb != null)
                    {
                        res.ObdFound = true; res.ObdProto = "ISO 14230-4 KWP (быстрый init)";
                        res.Lines.Add("K-line контакт 7: стандартный OBD по KWP2000");
                        var m = new Module { Name = "Двигатель", Kind = "engine", Where = "K-line, контакт 7 (OBD)", Proto = "OBD" };
                        log.Ev("Двигатель отвечает по K-line (KWP2000)");
                        ReadObd(m, q => AskK(ch, new byte[] { (byte)(0xC0 | q.Length), 0x33, 0xF1 }, q));
                        AddModule(m, 0);
                        return;
                    }
                }
            Check();
            // ISO 9141-2: 5 бод к 33, заголовок 68 6A F1
            using (var ch = Open(J.ISO9141_PS, 0, 10400, 7 << 8, "ISO9141 OBD"))
                if (ch.Ok)
                {
                    ch.PassAll();
                    var kb = ch.FiveBaud(0x33);
                    if (kb != null)
                    {
                        res.ObdFound = true; res.ObdProto = "ISO 9141-2 (5 бод, ключевые байты " + B.Hex(kb) + ")";
                        res.Lines.Add("K-line контакт 7: стандартный OBD по ISO 9141-2");
                        var m = new Module { Name = "Двигатель", Kind = "engine", Where = "K-line, контакт 7 (OBD)", Proto = "OBD" };
                        log.Ev("Двигатель отвечает по K-line (ISO 9141-2)");
                        ReadObd(m, q => AskK(ch, new byte[] { 0x68, 0x6A, 0xF1 }, q));
                        AddModule(m, 0);
                        return;
                    }
                }
            res.Lines.Add("K-line контакт 7: стандартный OBD не ответил (быстрый init и 5 бод)");
        }

        /// <summary>J1850 VPW (контакт 2) и PWM (2/10) — машины GM/Ford до 2008 без CAN.</summary>
        void ObdJ1850()
        {
            foreach (var v in new[] { new { P = J.J1850VPW, Baud = 10400u, H = new byte[] { 0x68, 0x6A, 0xF1 }, N = "J1850 VPW" },
                                      new { P = J.J1850PWM, Baud = 41600u, H = new byte[] { 0x61, 0x6A, 0xF1 }, N = "J1850 PWM" } })
            {
                Check();
                using (var ch = Open(v.P, 0, v.Baud, 0, v.N))
                {
                    if (!ch.Ok) { res.Lines.Add(v.N + ": адаптер не открыл канал"); continue; }
                    ch.PassAll();
                    var a = AskK(ch, v.H, new byte[] { 0x01, 0x00 });
                    if (a == null || a[0] != 0x41) { res.Lines.Add(v.N + ": нет ответа"); continue; }
                    res.ObdFound = true; res.ObdProto = v.N;
                    res.Lines.Add(v.N + ": стандартный OBD отвечает");
                    var m = new Module { Name = "Двигатель", Kind = "engine", Where = v.N, Proto = "OBD" };
                    log.Ev("Двигатель отвечает по " + v.N);
                    var h = v.H;
                    ReadObd(m, q => AskK(ch, h, q));
                    AddModule(m, 0);
                    return;
                }
            }
        }

        /// <summary>Запрос по K-line/J1850: заголовок + данные (контрольную сумму добавляет адаптер, в приёме её нет —
        /// лог Probe 1.4); ответ — данные без заголовка.</summary>
        byte[] AskK(Chan ch, byte[] header, byte[] q)
        {
            ch.ClearRx();
            if (ch.Send(B.Cat(header, q), 0) != J.STATUS_NOERROR) return null;
            for (int round = 0; round < 4; round++)
            {
                bool pending = false;
                foreach (var d in ch.Receive(round == 0 ? 400 : 1500, round == 0 ? 2500 : 5000))
                {
                    var p = KData(d, ch.Protocol);
                    if (p == null || p.Length == 0) continue;
                    if (p[0] == q[0] + 0x40) return p;
                    if (p[0] == 0x7F && p.Length >= 3 && p[1] == q[0])
                    {
                        if (p[2] == 0x78) { pending = true; continue; }
                        return p;
                    }
                }
                if (!pending) return null;
            }
            return null;
        }

        /// <summary>Данные из кадра K-line. ISO 14230-2: байт формата (биты 7–6 — есть адреса, 5–0 — длина, 0 — отдельный байт длины);
        /// ISO 9141-2 и J1850 — три байта заголовка.</summary>
        static byte[] KData(byte[] d, uint proto)
        {
            if (d.Length < 2) return null;
            if (proto == J.ISO14230 || proto == J.ISO14230_PS)
            {
                int fmt = d[0], p = 1;
                if ((fmt & 0xC0) != 0) p += 2;
                int len = fmt & 0x3F;
                if (len == 0) { if (d.Length <= p) return null; len = d[p]; p++; }
                if (p >= d.Length) return null;
                return d.Skip(p).Take(Math.Min(len, d.Length - p)).ToArray();
            }
            return d.Length > 3 ? d.Skip(3).ToArray() : null;
        }

        // ---------- поиск блоков по сырому CAN ----------
        Dictionary<uint, uint> SweepCan(bool b29, double p0, double p1)
        {
            var found = new Dictionary<uint, uint>();
            uint flags = b29 ? J.CAN_29BIT_ID : 0;
            using (var ch = Open(J.CAN, flags, canBaud, 0, "CAN поиск"))
            {
                if (!ch.Ok) return found;
                if (b29) ch.Filter(J.PASS_FILTER, B.Id(0x1FFFFF00), B.Id(0x18DAF100), null);
                else ch.Filter(J.PASS_FILTER, B.Id(0x700), B.Id(0x700), null);
                var ids = new List<uint>();
                if (b29) { for (uint x = 0; x <= 0xFF; x++) if (x != 0xF1 && x != 0x33) ids.Add(0x18DA00F1u | (x << 8)); }
                else { for (uint x = 0x700; x <= 0x7FF; x++) if (x != 0x7DF) ids.Add(x); }
                // зонд 1: 10 01 (обычная сессия, без последствий); зонд 2 (глубокая): 3E 00 «я на связи» — для блоков,
                // которые на 10 01 молчат
                var probes = deep ? new[] { new byte[] { 0x02, 0x10, 0x01 }, new byte[] { 0x02, 0x3E, 0x00 } } : new[] { new byte[] { 0x02, 0x10, 0x01 } };
                int step = 0, steps = ids.Count * probes.Length;
                foreach (var probe in probes)
                    foreach (var req in ids)
                    {
                        if ((++step & 15) == 0) { Check(); var a = Stage; if (a != null) a(null, p0 + (p1 - p0) * step / steps); }
                        if (found.ContainsKey(req)) continue;
                        ch.ClearRx();
                        var frame = new byte[8]; Array.Copy(probe, frame, probe.Length);
                        ch.Send(B.Cat(B.Id(req), frame), 0);
                        foreach (var d in ch.Receive(40, 120))
                        {
                            if (d.Length < 7) continue;
                            uint rid = B.IdOf(d);
                            if (rid == req || found.ContainsValue(rid)) continue;
                            // одиночный кадр: положительный ответ или отказ на этот же сервис — блок есть
                            bool sf = (d[4] >> 4) == 0;
                            bool yes = sf && (d[5] == probe[1] + 0x40 || (d[5] == 0x7F && d[6] == probe[1]));
                            if (yes) { found[req] = rid; log.Ev(string.Format("Найден блок CAN {0:X} → {1:X}", req, rid)); }
                        }
                    }
            }
            // OBD-блоки уже прочитаны стандартным путём — их заводскую память всё равно читаем (UDS у Hyundai шире режима 03)
            res.Lines.Add("CAN " + (b29 ? "29" : "11") + " бит, перебор " + (b29 ? "18DAxxF1" : "700–7FF") + (deep ? " (два зонда)" : "") + ": найдено блоков " + found.Count);
            return found;
        }

        /// <summary>Заводская память ошибок блока на ISO 15765: UDS 19 02 FF, затем KWP 18 02/18 00; отказы 11/12/22/7E/7F — сессия и повтор.</summary>
        void ReadCanModule(uint req, uint resp, bool b29, uint protocol, uint pins, uint baud)
        {
            uint flags = b29 ? J.CAN_29BIT_ID : 0;
            var m = new Module { Where = string.Format("CAN {0:X}→{1:X}", req, resp) + (pins != 0 ? string.Format(" (контакты {0}/{1})", pins >> 8, pins & 0xFF) : ""), Proto = "UDS", CanReq = pins == 0 ? req : 0 };
            m.Name = "Блок " + req.ToString("X");
            using (var ch = Open(protocol, flags, baud, pins, "ISO15765 блок"))
            {
                if (!ch.Ok) return;
                ch.Filter(J.FLOW_CONTROL_FILTER, B.Id(b29 ? 0x1FFFFFFFu : 0x7FFu), B.Id(resp), B.Id(req));
                Func<byte[], byte[]> ask = q => Ask(ch, req, q, 300, 2500);
                var notes = new List<string>();
                bool read = false;

                // UDS
                var a = ask(new byte[] { 0x19, 0x02, 0xFF });
                if (a != null && a[0] == 0x7F && a.Length >= 3 && (a[2] == 0x22 || a[2] == 0x7E || a[2] == 0x7F || a[2] == 0x31))
                {
                    var s = ask(new byte[] { 0x10, 0x03 });
                    log.Raw("10 03 → " + B.Hex(s));
                    a = ask(new byte[] { 0x19, 0x02, 0xFF });
                }
                if (a != null && a[0] == 0x59) { read = true; UdsCodes(m, a); }
                else if (a != null) notes.Add("19 02 FF → " + B.Hex(a));

                // KWP2000 по CAN (Hyundai до ~2010: двигатель и ЭУР отдали коды только так — лог Probe 1.4)
                if (!read)
                {
                    m.Proto = "KWP2000";
                    bool session = false;
                    for (int pass = 0; pass < 2 && !read; pass++)
                    {
                        bool refused = false;
                        foreach (var q in new[] { new byte[] { 0x18, 0x02, 0xFF, 0x00 }, new byte[] { 0x18, 0x00, 0xFF, 0x00 } })
                        {
                            a = ask(q);
                            if (a != null && a[0] == 0x58) { read = true; KwpCodes(m, a, 1); }
                            else if (a != null && a[0] == 0x7F && a.Length >= 3) { notes.Add(B.Hex(q, 0).Substring(0, 5) + " → 7F " + a[2].ToString("X2")); if (a[2] == 0x22 || a[2] == 0x7E || a[2] == 0x7F) refused = true; }
                        }
                        if (read || !refused || session) break;
                        // ISO 14230-3: conditionsNotCorrect — StartDiagnosticSession 10 81 и повтор (так прочиталась ABS по K-line)
                        session = true;
                        var s = ask(new byte[] { 0x10, 0x81 });
                        log.Raw("10 81 → " + B.Hex(s));
                    }
                }

                // номер блока и VIN блока
                foreach (var did in new[] { new byte[] { 0x22, 0xF1, 0x87 }, new byte[] { 0x22, 0xF1, 0x90 } })
                {
                    a = ask(did);
                    if (a != null && a[0] == 0x62 && a.Length > 3)
                    {
                        var t = B.Ascii(a, 3);
                        if (did[2] == 0x87 && t.Length > 0) notes.Insert(0, "номер " + t);
                        if (did[2] == 0x90 && t.Length == 17 && res.Vin == null) { res.Vin = t; log.Ev("VIN (из блока " + req.ToString("X") + "): " + t); }
                    }
                }
                m.Read = read;
                m.Note = notes.Count > 0 ? string.Join("; ", notes) : (read ? null : "блок не отдал память ошибок");
            }
            // блок уже есть как OBD (7E0/7E1) — дописываем заводские коды к нему
            var same = m.CanReq == 0 ? null : res.Modules.FirstOrDefault(x => x.CanReq == m.CanReq);
            if (same != null)
            {
                foreach (var c in m.Codes) AddCode(same, c.Id, c.State, c.Raw);
                same.Read |= m.Read;
                if (m.Read) { same.Proto = "OBD + " + m.Proto; same.Note = null; }
                log.Ev(same.Name + " (заводская память " + m.Proto + "): " + (m.Read ? (m.Codes.Count == 0 ? "ошибок нет" : string.Join(", ", m.Codes.Select(c => c.Id))) : "не прочитана"));
                return;
            }
            AddModule(m, req);
        }

        /// <summary>UDS 59 02: маска, затем по 4 байта (код 3 байта + статус). Без битов 0,1,2,3,5,7 — тест не завершён, не ошибка.</summary>
        void UdsCodes(Module m, byte[] a)
        {
            for (int i = 3; i + 3 < a.Length; i += 4)
            {
                byte st = a[i + 3];
                if ((st & 0xAF) == 0) continue;
                string state = (st & 0x01) != 0 ? "active" : (st & 0x08) != 0 ? "stored" : (st & 0x04) != 0 ? "pending" : "history";
                AddCode(m, B.Dtc(a[i], a[i + 1]) + (a[i + 2] != 0 ? "-" + a[i + 2].ToString("X2") : ""), state, "UDS статус " + st.ToString("X2"));
            }
        }

        /// <summary>KWP 58 n: по 3 байта (код 2 байта + статус). Биты 6–5 = 11 — «есть сейчас» (ISO 14230-3); иначе — в памяти.
        /// Значение байта у разных марок разное (у ABS Hyundai все коды со статусом 00, а Сканматик их показывает) — поэтому
        /// «нет ошибки» по статусу не делаем.</summary>
        void KwpCodes(Module m, byte[] a, int k)
        {
            for (int i = k + 1; i + 2 < a.Length; i += 3)
            {
                if (a[i] == 0 && a[i + 1] == 0) continue;
                byte st = a[i + 2];
                AddCode(m, B.Dtc(a[i], a[i + 1]), (st & 0x60) == 0x60 ? "active" : "stored", "KWP статус " + st.ToString("X2"));
            }
        }

        // ---------- другие пары CAN: сначала только слушаем ----------
        void OtherCan()
        {
            int[][] pairs = { new[] { 3, 11 }, new[] { 12, 13 } };
            foreach (var pr in pairs)
            {
                bool live = false;
                foreach (uint baud in new uint[] { 500000, 125000 })
                {
                    Check();
                    uint pins = (uint)((pr[0] << 8) | pr[1]);
                    var frames = 0;
                    using (var ch = Open(J.CAN_PS, 0, baud, pins, "CAN_PS слушаю"))
                    {
                        if (!ch.Ok) break;
                        ch.Filter(J.PASS_FILTER, B.Id(0), B.Id(0), null);
                        frames = ch.Receive(300, 600).Count;
                    }
                    if (frames == 0) continue;
                    live = true;
                    busyPins.Add(pr[0]); busyPins.Add(pr[1]);
                    res.Lines.Add(string.Format("CAN на контактах {0}/{1}: живая шина {2} кбит/с", pr[0], pr[1], baud / 1000));
                    log.Ev(string.Format("Живая шина CAN на контактах {0}/{1}, {2} кбит/с — ищу блоки", pr[0], pr[1], baud / 1000));
                    var found = SweepCanPs(pins, baud);
                    foreach (var kv in found) { ReadCanModule(kv.Key, kv.Value, false, J.ISO15765_PS, pins, baud); Check(); }
                    break;
                }
                if (!live) res.Lines.Add(string.Format("CAN на контактах {0}/{1}: тихо (500 и 125 кбит/с)", pr[0], pr[1]));
            }
        }

        Dictionary<uint, uint> SweepCanPs(uint pins, uint baud)
        {
            var found = new Dictionary<uint, uint>();
            using (var ch = Open(J.CAN_PS, 0, baud, pins, "CAN_PS поиск"))
            {
                if (!ch.Ok) return found;
                ch.Filter(J.PASS_FILTER, B.Id(0x700), B.Id(0x700), null);
                for (uint req = 0x700; req <= 0x7FF; req++)
                {
                    if ((req & 15) == 0) Check();
                    if (req == 0x7DF) continue;
                    ch.ClearRx();
                    ch.Send(B.Cat(B.Id(req), 0x02, 0x10, 0x01, 0, 0, 0, 0, 0), 0);
                    foreach (var d in ch.Receive(40, 120))
                    {
                        if (d.Length < 7) continue;
                        uint rid = B.IdOf(d);
                        if (rid == req || found.ContainsValue(rid)) continue;
                        if ((d[4] >> 4) == 0 && (d[5] == 0x50 || (d[5] == 0x7F && d[6] == 0x10))) { found[req] = rid; log.Ev(string.Format("Найден блок {0:X} → {1:X} (контакты {2}/{3})", req, rid, pins >> 8, pins & 0xFF)); }
                    }
                }
            }
            return found;
        }

        // ---------- K-line по контактам ----------
        static readonly int[] KlineAddrs = { 0x10, 0x11, 0x12, 0x17, 0x18, 0x20, 0x28, 0x27, 0x58, 0x30, 0x40, 0x60, 0x6A, 0x70, 0xA0, 0x36, 0x29 };

        void SweepKline(double p0, double p1)
        {
            var pins = new List<int> { 7, 8, 1, 3, 9, 11, 12, 13 };
            // контакт 6 — K-line только у машин без CAN на 6/14 (иначе это CAN-H)
            if (!res.ObdProto.StartsWithSafe("ISO 15765") && res.Modules.All(m => !m.Where.StartsWith("CAN"))) pins.Add(6);
            // контакт 2 — шина J1850 у GM/Ford; в глубокой проверке, если J1850 там нет
            if (deep && !res.ObdProto.StartsWithSafe("J1850")) pins.Add(2);
            var skipped = pins.Where(p => busyPins.Contains(p)).ToList();
            pins.RemoveAll(p => busyPins.Contains(p));
            if (skipped.Count > 0) res.Lines.Add("K-line не включалась на контактах " + string.Join(", ", skipped) + ": на них живой CAN");

            for (int pi = 0; pi < pins.Count; pi++)
            {
                Check();
                int pin = pins[pi];
                // глубокая: на 7 и 8 — все адреса 01–FE; на остальных — наш список
                List<int> addrs = deep && (pin == 7 || pin == 8)
                    ? KlineAddrs.Concat(Enumerable.Range(1, 0xFE).Where(x => x != 0xF1 && x != 0x33 && !KlineAddrs.Contains(x))).ToList()
                    : KlineAddrs.ToList();
                double a0 = p0 + (p1 - p0) * pi / pins.Count, a1 = p0 + (p1 - p0) * (pi + 1) / pins.Count;
                St("K-line: контакт " + pin + " (" + (pi + 1) + " из " + pins.Count + ")", a0);
                int answered = 0;
                using (var ch = Open(J.ISO14230_PS, 0, 10400, (uint)(pin << 8), "K-line " + pin))
                {
                    if (!ch.Ok) { res.Lines.Add("K-line: адаптер не умеет выбирать контакты (J2534-2) — проверен только стандартный путь"); return; }
                    // без фильтра PASS адаптер отбрасывает принятые сообщения (J2534-1; так в Probe 1.1 не дошли коды ABS)
                    ch.PassAll();
                    for (int ai = 0; ai < addrs.Count; ai++)
                    {
                        if ((ai & 3) == 0) { Check(); var s = Stage; if (s != null) s(null, a0 + (a1 - a0) * ai / addrs.Count); }
                        int addr = addrs[ai];
                        var kb = ch.FastInit(new byte[] { 0x81, (byte)addr, 0xF1, 0x81 });
                        if (kb == null) continue;
                        answered++;
                        ReadKline(ch, pin, addr, "быстрый init " + B.Hex(kb));
                    }
                }
                // 5 бод: к 33 на контакте 7 всегда (если OBD ещё не нашли), в глубокой — к адресам блоков на 7 и 8
                if (deep && (pin == 7 || pin == 8)) answered += FiveBaudSweep(pin);
                res.Lines.Add(string.Format("K-line контакт {0}: {1} адресов{2}, ответили {3}", pin, addrs.Count, deep && (pin == 7 || pin == 8) ? " + 5 бод" : "", answered));
            }
        }

        int FiveBaudSweep(int pin)
        {
            int n = 0;
            foreach (int addr in KlineAddrs)
            {
                Check();
                if (res.Modules.Any(m => m.Where == KlineWhere(pin, addr))) continue;
                using (var ch = Open(J.ISO14230_PS, 0, 10400, (uint)(pin << 8), "5 бод " + pin))
                {
                    if (!ch.Ok) return n;
                    ch.PassAll();
                    var kb = ch.FiveBaud((byte)addr);
                    if (kb == null) continue;
                    n++;
                    ReadKline(ch, pin, addr, "5 бод, ключевые байты " + B.Hex(kb));
                }
            }
            return n;
        }

        static string KlineWhere(int pin, int addr) { return string.Format("K-line, контакт {0}, адрес {1:X2}", pin, addr); }

        void ReadKline(Chan ch, int pin, int addr, string how)
        {
            var m = new Module { Name = "Блок " + addr.ToString("X2"), Where = KlineWhere(pin, addr), Proto = "KWP2000" };
            m.KAddr = addr;
            log.Ev("Ответил блок по K-line: контакт " + pin + ", адрес " + addr.ToString("X2") + " (" + how + ")");
            var hdr = new Func<byte[], byte[]>(q => new byte[] { (byte)(0x80 | q.Length), (byte)addr, 0xF1 });
            Func<byte[], byte[]> ask = q => AskK(ch, hdr(q), q);
            var notes = new List<string>();
            bool session = false;
            for (int pass = 0; pass < 2 && !m.Read; pass++)
            {
                bool refused = false;
                foreach (var q in new[] { new byte[] { 0x18, 0x00, 0xFF, 0x00 }, new byte[] { 0x18, 0x02, 0xFF, 0x00 } })
                {
                    var a = ask(q);
                    if (a != null && a[0] == 0x58) { m.Read = true; KwpCodes(m, a, 1); }
                    else if (a != null && a[0] == 0x7F && a.Length >= 3) { if (a[2] == 0x22 || a[2] == 0x7E || a[2] == 0x7F || a[2] == 0x11) refused = true; notes.Add(B.Hex(q).Substring(0, 5) + " → 7F " + a[2].ToString("X2")); }
                }
                if (m.Read || !refused || session) break;
                // Elantra HD: ABS на 18 xx — 7F 18 22, после 10 81 → 50 81 коды читаются (лог Probe 1.4)
                session = true;
                var s = ask(new byte[] { 0x10, 0x81 });
                log.Raw("10 81 → " + B.Hex(s));
            }
            if (!m.Read)
            {
                // KWP с сервисом 13 (старые блоки, ISO 14230-3 readDiagnosticTroubleCodes)
                var a = ask(new byte[] { 0x13 });
                if (a != null && a[0] == 0x53) { m.Read = true; for (int i = 2; i + 1 < a.Length; i += 2) if (a[i] != 0 || a[i + 1] != 0) AddCode(m, B.Dtc(a[i], a[i + 1]), "stored", "KWP 13"); }
            }
            var id = ask(new byte[] { 0x1A, 0x87 });
            if (id != null && id[0] == 0x5A && id.Length > 2) { var t = B.Ascii(id, 2); if (t.Length > 0) notes.Insert(0, "номер " + t); }
            m.Note = notes.Count > 0 ? string.Join("; ", notes) : (m.Read ? null : "блок не отдал память ошибок");
            // StopCommunication
            ch.Send(new byte[] { 0x81, (byte)addr, 0xF1, 0x82 }, 0);
            ch.Receive(200, 600);
            AddModule(m, 0);
        }
    }

    static class Ext
    {
        public static bool StartsWithSafe(this string s, string p) { return s != null && s.StartsWith(p); }
    }
}
