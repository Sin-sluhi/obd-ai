// Стенд: модель Hyundai Elantra HD 2009 за адаптером J2534 — ответы взяты из лога Probe 1.4
// (tools/j2534/logs/2026-10-24_elantra-hd-2009_probe-1.4_*.txt). VIN выдуман (серийная часть нули).
// Запуск: OBD-AI-x86.exe --sim (адаптер «Стенд» в списке). Проверяет весь путь: запросы, разбор, итог, отчёт.

using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

namespace ObdAi
{
    static class Sim
    {
        public const string Lib = "sim:elantra-hd-2009";
        sealed class Ch { public uint Proto, Flags, Baud, Pins; public bool Session, Inited; public int KAddr; public readonly Queue<KeyValuePair<uint, byte[]>> Rx = new Queue<KeyValuePair<uint, byte[]>>(); }
        static readonly Dictionary<uint, Ch> chans = new Dictionary<uint, Ch>();
        static uint next = 1;
        const string VIN = "KMHDU41BP9U000000";

        static byte[] H(string s) { return s.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).Select(x => Convert.ToByte(x, 16)).ToArray(); }

        public static Pt Make()
        {
            var p = new Pt();
            p.Open = (IntPtr n, out uint d) => { d = 1; return 0; };
            p.Close = d => 0;
            p.Connect = (uint d, uint proto, uint flags, uint baud, out uint ch) => { ch = next++; chans[ch] = new Ch { Proto = proto, Flags = flags, Baud = baud }; return 0; };
            p.Disconnect = ch => { chans.Remove(ch); return 0; };
            p.Filter = (uint ch, uint type, IntPtr m, IntPtr pa, IntPtr f, out uint id) => { id = 1; return 0; };
            p.Version = (d, fw, dl, api) => { fw.Append("СТЕНД"); dl.Append("sim"); api.Append("04.04"); return 0; };
            p.LastError = sb => { sb.Append("стенд"); return 0; };
            p.Ioctl = Ioctl;
            p.Write = Write;
            p.Read = Read;
            return p;
        }

        static int Ioctl(uint id, uint code, IntPtr input, IntPtr output)
        {
            Ch c; chans.TryGetValue(id, out c);
            switch (code)
            {
                case J.READ_VBATT: Marshal.WriteInt32(output, 13880); return 0;
                case J.CLEAR_RX_BUFFER: if (c != null) c.Rx.Clear(); return 0;
                case J.SET_CONFIG:
                    {
                        IntPtr cfg = Marshal.ReadIntPtr(input, IntPtr.Size);
                        uint par = (uint)Marshal.ReadInt32(cfg, 0), val = (uint)Marshal.ReadInt32(cfg, 4);
                        if (c != null && par == J.J1962_PINS) c.Pins = val;
                        return 0;
                    }
                case J.FAST_INIT:
                    {
                        var d = ReadData(input);
                        if (c != null && c.Pins == 0x0800 && d.Length >= 2 && d[1] == 0x28) { c.Inited = true; c.KAddr = 0x28; c.Session = false; WriteData(output, H("83 F1 28 C1 EA 8F")); Thread.Sleep(5); return 0; }
                        return 7;
                    }
                case J.FIVE_BAUD_INIT: return 7;
            }
            return 0;
        }

        static byte[] ReadData(IntPtr m) { int n = Marshal.ReadInt32(m, 16); var b = new byte[n]; Marshal.Copy(m + 24, b, 0, n); return b; }
        static void WriteData(IntPtr m, byte[] d) { Marshal.WriteInt32(m, 16, d.Length); Marshal.Copy(d, 0, m + 24, d.Length); }

        static int Read(uint id, IntPtr msgs, ref uint num, uint timeout)
        {
            Ch c;
            if (!chans.TryGetValue(id, out c) || c.Rx.Count == 0) { num = 0; return J.ERR_BUFFER_EMPTY; }
            var kv = c.Rx.Dequeue();
            for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(msgs, i, 0);
            Marshal.WriteInt32(msgs, 4, (int)kv.Key);
            WriteData(msgs, kv.Value);
            num = 1;
            return 0;
        }

        static void Put(Ch c, string hex) { c.Rx.Enqueue(new KeyValuePair<uint, byte[]>(0, H(hex))); }

        static int Write(uint id, IntPtr msgs, ref uint num, uint timeout)
        {
            Ch c;
            if (!chans.TryGetValue(id, out c)) return 2;
            var d = ReadData(msgs);
            bool b29 = (c.Flags & J.CAN_29BIT_ID) != 0;
            if (c.Proto == J.ISO15765 && !b29 && c.Baud == 500000 && d.Length >= 5)
            {
                uint req = B.IdOf(d);
                string q = B.Hex(d, 4), rid = "00 00 07 " + (req + 8).ToString("X2").Substring(1);
                string rsp = "00 00 0" + (req + 8).ToString("X3").Substring(0, 1) + " " + (req + 8).ToString("X3").Substring(1);
                rsp = "00 00 " + ((req + 8) >> 8).ToString("X2") + " " + ((req + 8) & 0xFF).ToString("X2");
                string a = Iso(req, q);
                if (a == "VIN")
                {
                    // как у реального адаптера: сначала индикация начала многокадрового ответа (только ID), потом целое сообщение
                    c.Rx.Enqueue(new KeyValuePair<uint, byte[]>(J.START_OF_MESSAGE, H(rsp)));
                    var vin = string.Join(" ", VIN.Select(ch => ((int)ch).ToString("X2")));
                    Put(c, rsp + " 49 02 01 " + vin);
                }
                else if (a != null) Put(c, rsp + " " + a);
            }
            else if (c.Proto == J.CAN && !b29 && c.Baud == 500000 && d.Length >= 12)
            {
                uint req = B.IdOf(d);
                if ((req == 0x7E0 || req == 0x7E1 || req == 0x7D4) && d[5] == 0x10)
                    Put(c, "00 00 " + ((req + 8) >> 8).ToString("X2") + " " + ((req + 8) & 0xFF).ToString("X2") + " 03 7F 10 12 00 00 00 00");
            }
            else if (c.Proto == J.ISO14230_PS && c.Inited && c.KAddr == 0x28 && d.Length >= 4)
            {
                string q = B.Hex(d, 3);
                if (q == "10 81") { c.Session = true; Put(c, "82 F1 28 50 81"); }
                else if (q.StartsWith("18")) Put(c, c.Session ? "8E F1 28 58 04 52 02 00 52 08 00 52 11 00 51 02 00" : "83 F1 28 7F 18 22");
                else if (q == "82") { c.Inited = false; Put(c, "81 F1 28 C2"); }
                else Put(c, "83 F1 28 7F " + q.Substring(0, 2) + " 11");
            }
            return 0;
        }

        /// <summary>Ответы блоков ISO 15765 по логу Probe 1.4 (VIN — по исправленной логике: целиком).</summary>
        static string Iso(uint req, string q)
        {
            if (req == 0x7E0)
            {
                switch (q)
                {
                    case "01 00": return "41 00 BE 3E B8 13";
                    case "01 01": return "41 01 00 07 E5 00";
                    case "09 02": return "VIN";
                    case "03": return "43 00";
                    case "07": return "47 01 00 68";
                    case "0A": return "7F 0A 11";
                    case "02 02 00": return "42 02 00 00 00";
                    case "10 01": return "7F 10 12";
                    case "19 02 FF": return "7F 19 11";
                    case "18 02 FF 00": case "18 00 FF 00": return "58 01 00 68 22";
                }
                return "7F " + q.Substring(0, 2) + " 11";
            }
            if (req == 0x7E1)
            {
                switch (q)
                {
                    case "01 00": return "41 00 88 18 80 13";
                    case "09 02": return "7F 09 12";
                    case "03": return "43 00";
                    case "07": return "47 00";
                    case "18 02 FF 00": case "18 00 FF 00": return "58 00";
                }
                return "7F " + q.Substring(0, 2) + " 11";
            }
            if (req == 0x7D4)
            {
                switch (q)
                {
                    case "18 02 FF 00": return "58 01 51 02 01";
                    case "18 00 FF 00": return "58 00";
                }
                return "7F " + q.Substring(0, 2) + " 11";
            }
            return null;
        }
    }
}
