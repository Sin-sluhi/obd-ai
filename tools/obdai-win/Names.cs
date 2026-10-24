// Марка по VIN, имена блоков по марке — те же таблицы, что в APK (VinDecoder.kt, ModuleMap в ObdDecoder.kt).

using System;
using System.Collections.Generic;
using System.Linq;

namespace ObdAi
{
    static class Names
    {
        static readonly Dictionary<string, string> Kinds = new Dictionary<string, string> {
            { "engine", "Двигатель" }, { "engine_extra", "Двигатель (доп. блок)" }, { "gearbox", "Коробка передач" },
            { "abs", "ABS / ESP" }, { "airbag", "Подушки безопасности" }, { "eps", "Электроусилитель руля" },
            { "parking_brake", "Стояночный тормоз" }, { "cluster", "Приборная панель" }, { "parktronic", "Парктроники" },
            { "body", "Кузовной блок" }, { "smart_key", "Смарт-ключ" }, { "climate", "Климат" }, { "awd", "Полный привод" },
            { "camera", "Камера и помощники" }, { "radar", "Радар" }, { "tpms", "Давление в шинах" }, { "windows", "Люк и стеклоподъёмники" },
            { "seats", "Сиденья" }, { "avn", "Мультимедиа" }, { "immo", "Иммобилайзер" }, { "gateway", "Шлюз CAN" } };

        static readonly Dictionary<uint, string> HyundaiKia = new Dictionary<uint, string> {
            { 0x7E0, "engine" }, { 0x7E1, "gearbox" }, { 0x7D1, "abs" }, { 0x7D2, "airbag" }, { 0x7D4, "eps" }, { 0x7D5, "parking_brake" },
            { 0x7C6, "cluster" }, { 0x7C4, "parktronic" }, { 0x7A0, "body" }, { 0x7A5, "smart_key" }, { 0x7B3, "climate" }, { 0x7B6, "awd" },
            { 0x7B7, "camera" }, { 0x7B1, "radar" }, { 0x7C7, "tpms" }, { 0x7A2, "windows" }, { 0x7A3, "seats" }, { 0x7C5, "avn" },
            { 0x7D0, "engine_extra" }, { 0x7D3, "immo" }, { 0x7D6, "awd" }, { 0x7B2, "camera" }, { 0x7B4, "radar" } };
        static readonly Dictionary<uint, string> Vag = new Dictionary<uint, string> {
            { 0x7E0, "engine" }, { 0x7E1, "gearbox" }, { 0x713, "abs" }, { 0x715, "airbag" }, { 0x714, "cluster" }, { 0x712, "eps" },
            { 0x710, "gateway" }, { 0x70E, "body" }, { 0x746, "climate" }, { 0x757, "radar" }, { 0x74F, "camera" } };
        static readonly Dictionary<uint, string> Toyota = new Dictionary<uint, string> {
            { 0x7E0, "engine" }, { 0x7E1, "gearbox" }, { 0x7B0, "abs" }, { 0x780, "airbag" }, { 0x7C0, "cluster" }, { 0x7A1, "eps" }, { 0x7C4, "climate" } };
        static readonly Dictionary<uint, string> RenaultNissan = new Dictionary<uint, string> {
            { 0x7E0, "engine" }, { 0x7E1, "gearbox" }, { 0x740, "abs" }, { 0x742, "eps" }, { 0x743, "cluster" }, { 0x744, "climate" },
            { 0x745, "body" }, { 0x752, "airbag" }, { 0x755, "parking_brake" }, { 0x707, "camera" } };
        static readonly Dictionary<uint, string> Ford = new Dictionary<uint, string> {
            { 0x7E0, "engine" }, { 0x7E1, "gearbox" }, { 0x760, "abs" }, { 0x730, "eps" }, { 0x720, "cluster" }, { 0x726, "body" },
            { 0x737, "airbag" }, { 0x764, "radar" }, { 0x706, "camera" }, { 0x7D0, "avn" } };
        static readonly Dictionary<uint, string> Generic = new Dictionary<uint, string> { { 0x7E0, "engine" }, { 0x7E1, "gearbox" } };

        // ModuleMap.klineNames: адреса KWP2000, у которых назначение одинаково у большинства марок
        static readonly Dictionary<int, string> Kline = new Dictionary<int, string> {
            { 0x10, "engine" }, { 0x11, "engine_extra" }, { 0x12, "engine_extra" }, { 0x17, "cluster" }, { 0x18, "gearbox" },
            { 0x20, "abs" }, { 0x28, "abs" }, { 0x27, "airbag" }, { 0x58, "airbag" }, { 0x30, "eps" }, { 0x70, "body" },
            { 0xA0, "immo" }, { 0x36, "climate" }, { 0x29, "climate" }, { 0x60, "cluster" }, { 0x6A, "parktronic" }, { 0x40, "body" } };

        public static string KindName(string kind) { string n; return kind != null && Kinds.TryGetValue(kind, out n) ? n : null; }

        static Dictionary<uint, string> MapFor(string brand)
        {
            var b = (brand ?? "").ToLowerInvariant();
            if (b.Contains("hyundai") || b.Contains("kia") || b.Contains("genesis")) return HyundaiKia;
            if (b.Contains("volkswagen") || b.Contains("audi") || b.Contains("skoda") || b.Contains("seat") || b.Contains("porsche")) return Vag;
            if (b.Contains("toyota") || b.Contains("lexus")) return Toyota;
            if (b.Contains("renault") || b.Contains("nissan") || b.Contains("dacia") || b.Contains("infiniti") || b.Contains("lada")) return RenaultNissan;
            if (b.Contains("ford")) return Ford;
            return Generic;
        }

        public static void Apply(ScanResult r)
        {
            var vin = Vin.Decode(r.Vin);
            r.Brand = vin.Brand;
            if (r.Brand == null)
            {
                // ModuleMap.guessBrand: марка по набору блоков, когда VIN нет
                var pairs = r.Modules.Where(m => m.CanReq != 0).Select(m => m.CanReq).ToList();
                if (new uint[] { 0x7D1, 0x7D2, 0x7D4, 0x7A0, 0x7C6, 0x7B3 }.Count(pairs.Contains) >= 3) r.Brand = "Hyundai / Kia";
            }
            r.BrandKey = Catalog.BrandKey(r.Brand);
            r.CarText = vin.Brand == null ? (r.Brand != null ? r.Brand + " (по набору блоков)" : null) : vin.Brand + (vin.Year > 0 ? ", " + vin.Year + " г." : "");
            var map = MapFor(r.Brand);
            foreach (var m in r.Modules)
            {
                string kind = null;
                if (m.CanReq != 0) map.TryGetValue(m.CanReq, out kind);
                if (m.KAddr >= 0) Kline.TryGetValue(m.KAddr, out kind);
                if (kind == null && m.Kind != null && m.Kind != "other") kind = m.Kind;
                m.Kind = kind ?? "other";
                var n = KindName(kind);
                if (n != null) m.Name = n;
                foreach (var c in m.Codes) c.Module = m.Name;
            }
            // одинаковые имена (два блока ABS) — различаем адресом
            foreach (var g in r.Modules.GroupBy(m => m.Name).Where(g => g.Count() > 1))
                foreach (var m in g) { m.Name += m.KAddr >= 0 ? " (K-line " + m.KAddr.ToString("X2") + ")" : m.CanReq != 0 ? " (" + m.CanReq.ToString("X") + ")" : ""; foreach (var c in m.Codes) c.Module = m.Name; }
        }
    }

    sealed class VinInfo { public string Brand; public int Year; }

    static class Vin
    {
        static readonly string[][] Wmi = {
            new[] { "KMH", "Hyundai" }, new[] { "Z94", "Hyundai" }, new[] { "KM8", "Hyundai" }, new[] { "5NP", "Hyundai" }, new[] { "TMA", "Hyundai" },
            new[] { "KNA", "Kia" }, new[] { "KNB", "Kia" }, new[] { "KNC", "Kia" }, new[] { "KND", "Kia" }, new[] { "KNE", "Kia" }, new[] { "XWE", "Kia" }, new[] { "U5Y", "Kia" }, new[] { "U6Y", "Kia" },
            new[] { "XTA", "Lada" }, new[] { "XTT", "УАЗ" }, new[] { "X96", "ГАЗ" }, new[] { "X9F", "Ford" }, new[] { "XW8", "Volkswagen" }, new[] { "XW7", "Toyota" },
            new[] { "X7L", "Renault" }, new[] { "VF1", "Renault" }, new[] { "Z8N", "Nissan" }, new[] { "XUU", "Chevrolet" }, new[] { "XWB", "Chevrolet" }, new[] { "X4X", "BMW" },
            new[] { "WVW", "Volkswagen" }, new[] { "WV1", "Volkswagen" }, new[] { "WV2", "Volkswagen" }, new[] { "WVG", "Volkswagen" }, new[] { "WAU", "Audi" }, new[] { "WUA", "Audi" },
            new[] { "TMB", "Skoda" }, new[] { "VSS", "SEAT" }, new[] { "WP0", "Porsche" }, new[] { "WP1", "Porsche" },
            new[] { "WBA", "BMW" }, new[] { "WBS", "BMW" }, new[] { "WBY", "BMW" }, new[] { "WMW", "MINI" }, new[] { "WDB", "Mercedes-Benz" }, new[] { "WDD", "Mercedes-Benz" }, new[] { "WDC", "Mercedes-Benz" },
            new[] { "W0L", "Opel" }, new[] { "W0V", "Opel" }, new[] { "WF0", "Ford" }, new[] { "VF3", "Peugeot" }, new[] { "VF7", "Citroen" }, new[] { "YV1", "Volvo" },
            new[] { "SJN", "Nissan" }, new[] { "JN1", "Nissan" }, new[] { "JN8", "Nissan" }, new[] { "SB1", "Toyota" }, new[] { "JT", "Toyota" },
            new[] { "JHM", "Honda" }, new[] { "JHL", "Honda" }, new[] { "SHH", "Honda" }, new[] { "SHS", "Honda" }, new[] { "JM", "Mazda" },
            new[] { "JMB", "Mitsubishi" }, new[] { "JA", "Mitsubishi" }, new[] { "JF", "Subaru" }, new[] { "JS", "Suzuki" }, new[] { "TSM", "Suzuki" },
            new[] { "KL", "Chevrolet" }, new[] { "1G", "Chevrolet" }, new[] { "1F", "Ford" }, new[] { "2F", "Ford" }, new[] { "3F", "Ford" } };

        public static VinInfo Decode(string vin)
        {
            var r = new VinInfo();
            if (vin == null || vin.Length != 17) return r;
            foreach (var w in Wmi.OrderByDescending(x => x[0].Length))
                if (vin.StartsWith(w[0])) { r.Brand = w[1]; break; }
            // 10-й символ — год (цикл 30 лет): берём последний год, не позже следующего
            const string yc = "ABCDEFGHJKLMNPRSTVWXY123456789";
            int k = yc.IndexOf(vin[9]);
            if (k >= 0)
            {
                int y = 2010 + k;
                while (y > DateTime.Now.Year + 1) y -= 30;
                r.Year = y;
            }
            return r;
        }
    }
}
