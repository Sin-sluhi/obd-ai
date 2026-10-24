// Справочник кодов — те же данные и та же логика, что Catalog.kt в APK (prep_catalog.py собирает их в один файл).

using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Web.Script.Serialization;

namespace ObdAi
{
    sealed class DtcInfo
    {
        public string Code, Title, Meaning, Explanation, Story, WhatToDo, Risk, Severity, FamilyTitle, Brand, Note;
        public List<string> Symptoms = new List<string>(), Causes = new List<string>(), Checks = new List<string>(), FalsePaths = new List<string>();
        public int Price; public bool Stop; public bool Detailed;
    }

    static class Catalog
    {
        static Dictionary<string, object> families, codes, brands, openG, openB, openV;
        public static bool Ready { get; private set; }
        public static string Error;
        static readonly Dictionary<string, string> BrandTitles = new Dictionary<string, string> {
            { "hyundai_kia", "Hyundai / Kia" }, { "lada", "Lada" }, { "uaz_gaz", "УАЗ / ГАЗ" }, { "toyota", "Toyota / Lexus" }, { "vag", "VW / Audi / Skoda" },
            { "ford", "Ford" }, { "gm", "GM / Chevrolet / Opel" }, { "nissan", "Nissan" }, { "honda", "Honda" } };

        public static void Load()
        {
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("catalog.json.gz"))
                using (var gz = new GZipStream(s, CompressionMode.Decompress))
                using (var rd = new StreamReader(gz, Encoding.UTF8))
                {
                    var js = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 64 };
                    var root = (Dictionary<string, object>)js.DeserializeObject(rd.ReadToEnd());
                    families = (Dictionary<string, object>)root["families"];
                    codes = (Dictionary<string, object>)root["codes"];
                    brands = (Dictionary<string, object>)root["brands"];
                    var op = (Dictionary<string, object>)root["open"];
                    openG = (Dictionary<string, object>)op["g"]; openB = (Dictionary<string, object>)op["b"]; openV = (Dictionary<string, object>)op["v"];
                }
                Ready = true;
            }
            catch (Exception e) { Error = e.Message; }
        }

        /// <summary>Catalog.brandKey в APK.</summary>
        public static string BrandKey(string brand)
        {
            if (brand == null) return null;
            var b = brand.ToLowerInvariant();
            if (b.Contains("hyundai") || b.Contains("kia")) return "hyundai_kia";
            if (b.Contains("lada") || b.Contains("ваз")) return "lada";
            if (b.Contains("уаз") || b.Contains("uaz") || b.StartsWith("газ") || b.Contains("gaz")) return "uaz_gaz";
            if (b.Contains("toyota") || b.Contains("lexus")) return "toyota";
            if (b.Contains("volkswagen") || b.Contains("audi") || b.Contains("skoda") || b.Contains("seat") || b.Contains("porsche")) return "vag";
            if (b.Contains("ford")) return "ford";
            if (b.Contains("chevrolet") || b.Contains("opel")) return "gm";
            if (b.Contains("nissan") || b.Contains("infiniti")) return "nissan";
            if (b.Contains("honda") || b.Contains("acura")) return "honda";
            if (b.Contains("bmw") || b.Contains("mini")) return "bmw";
            if (b.Contains("mazda")) return "mazda";
            if (b.Contains("mitsubishi")) return "mitsubishi";
            if (b.Contains("subaru")) return "subaru";
            if (b.Contains("mercedes")) return "mercedes";
            return null;
        }

        static Dictionary<string, object> Obj(Dictionary<string, object> d, string k) { object v; return d != null && d.TryGetValue(k, out v) ? v as Dictionary<string, object> : null; }
        static string S(Dictionary<string, object> d, string k) { object v; return d != null && d.TryGetValue(k, out v) && v != null ? v.ToString() : ""; }
        static List<string> L(Dictionary<string, object> d, string k)
        {
            object v; var r = new List<string>();
            if (d != null && d.TryGetValue(k, out v) && v is IEnumerable && !(v is string)) foreach (var x in (IEnumerable)v) if (x != null) r.Add(x.ToString());
            return r;
        }

        public static DtcInfo Info(string code, string brandKey)
        {
            if (!Ready) return null;
            var c = code.Split('-')[0].Trim().ToUpperInvariant();
            Dictionary<string, object> e = null; string brand = null;
            if (brandKey != null) { e = Obj(Obj(brands, brandKey), c); if (e != null) brand = brandKey; }
            if (e == null) e = Obj(codes, c);
            string note = null;
            if (e == null && brandKey == null)
            {
                // марка не определена: заводской код есть только в таблицах марок — показываем, у кого он есть, и честно пишем
                var who = brands.Where(kv => Obj((Dictionary<string, object>)kv.Value, c) != null).Select(kv => kv.Key).ToList();
                if (who.Count == 1) { e = Obj(Obj(brands, who[0]), c); brand = who[0]; note = "Марка машины не определена — расшифровка по таблице " + BrandTitle(who[0]) + "."; }
            }
            if (e != null) return Build(c, e, brand, note);

            // открытый слой: только название, без подробностей
            string title = OpenTitle(c, brandKey);
            var fk = title != null && (c.StartsWith("P0") || c.StartsWith("P2") || c.StartsWith("P34") || c.StartsWith("B0") || c.StartsWith("C0") || c.StartsWith("U0")) ? "open_sae"
                : c.StartsWith("P1") || c.StartsWith("P3") ? "generic_p1" : c.StartsWith("C") ? "generic_c" : c.StartsWith("B") ? "generic_b" : c.StartsWith("U") ? "generic_u" : "generic_p1";
            var fam = Obj(families, fk);
            var info = new DtcInfo { Code = c, Title = title ?? (c[0] == 'P' ? "Заводской код двигателя" : c[0] == 'C' ? "Заводской код шасси" : c[0] == 'B' ? "Заводской код кузова" : "Заводской код сети"), Detailed = false };
            if (fam != null)
            {
                info.Story = S(fam, "s"); info.Causes = L(fam, "c"); info.WhatToDo = S(fam, "d"); info.Severity = S(fam, "sev"); info.FamilyTitle = S(fam, "t");
                info.Price = fam.ContainsKey("p") ? Convert.ToInt32(fam["p"]) : 0;
            }
            if (string.IsNullOrEmpty(info.Severity)) info.Severity = "unknown";
            info.Note = title == null ? "Этого кода нет в нашей базе. Расшифровка — у дилерского сканера марки." : "Подробного разбора по этому коду в базе пока нет — только название.";
            return info;
        }

        public static string BrandTitle(string key) { string t; return key != null && BrandTitles.TryGetValue(key, out t) ? t : key; }

        static string OpenTitle(string c, string brandKey)
        {
            object v;
            if (c.Length == 5 && c.All(char.IsDigit)) return openV.TryGetValue(c, out v) ? v as string : null;
            if (brandKey != null) { var b = Obj(openB, brandKey); if (b != null && b.TryGetValue(c, out v) && !string.IsNullOrEmpty(v as string)) return (string)v; }
            return openG.TryGetValue(c, out v) && !string.IsNullOrEmpty(v as string) ? (string)v : null;
        }

        static DtcInfo Build(string c, Dictionary<string, object> e, string brand, string note)
        {
            var fam = Obj(families, S(e, "f")) ?? new Dictionary<string, object>();
            var i = new DtcInfo { Code = c, Title = S(e, "t"), Meaning = S(e, "m"), Brand = brand, Note = note, Detailed = e.ContainsKey("ck") };
            var own = S(e, "e");
            i.Explanation = own;
            i.Story = S(fam, "s");
            i.Symptoms = L(e, "sy");
            i.Causes = e.ContainsKey("c") ? L(e, "c") : L(fam, "c");
            i.Checks = L(e, "ck");
            i.WhatToDo = S(e, "d"); if (i.WhatToDo == "") i.WhatToDo = S(fam, "d");
            i.FalsePaths = L(e, "x");
            i.Risk = S(e, "rk");
            i.Severity = S(e, "s"); if (i.Severity == "") i.Severity = S(fam, "sev"); if (i.Severity == "") i.Severity = "medium";
            i.Stop = e.ContainsKey("stop") ? (bool)e["stop"] : fam.ContainsKey("stop") && (bool)fam["stop"];
            i.Price = e.ContainsKey("p") ? Convert.ToInt32(e["p"]) : fam.ContainsKey("p") ? Convert.ToInt32(fam["p"]) : 0;
            i.FamilyTitle = S(fam, "t");
            return i;
        }
    }
}
