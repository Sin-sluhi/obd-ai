// Итог проверки — те же правила, что Coverage и Diagnosis.local в APK: «Ошибок не найдено» только если прочитаны
// двигатель, ABS, подушки и приборка/кузов; иначе — честно, что не проверено.

using System;
using System.Collections.Generic;
using System.Linq;

namespace ObdAi
{
    sealed class CodeItem
    {
        public Code Code; public DtcInfo Info; public string State; public List<string> Modules = new List<string>();
    }

    sealed class Verdict
    {
        public string Level;   // ok, partial, warn, danger, error
        public string Title, Text;
        public readonly List<CodeItem> Items = new List<CodeItem>();
        public readonly List<Module> Clean = new List<Module>(), WithCodes = new List<Module>(), Unread = new List<Module>();
        public readonly List<string> Missing = new List<string>();
        public int PriceFrom;

        static readonly string[][] Core = { new[] { "engine" }, new[] { "abs" }, new[] { "airbag" }, new[] { "cluster", "body" } };

        public static int Rank(string s) { return s == "active" ? 5 : s == "stored" ? 4 : s == "permanent" ? 3 : s == "pending" ? 2 : 1; }
        static int Sev(string s) { return s == "high" ? 3 : s == "medium" ? 2 : s == "low" ? 1 : 0; }

        public static string StateText(string s)
        {
            switch (s)
            {
                case "active": return "Активна сейчас";
                case "stored": return "В памяти блока";
                case "permanent": return "Постоянная (до ремонта)";
                case "pending": return "Неподтверждённая";
                default: return "История";
            }
        }

        public static Verdict Of(ScanResult r)
        {
            var v = new Verdict();
            foreach (var m in r.Modules)
            {
                if (!m.Read) v.Unread.Add(m);
                else if (m.Codes.Count == 0) v.Clean.Add(m);
                else v.WithCodes.Add(m);
            }
            foreach (var g in r.AllCodes.GroupBy(c => c.Base))
            {
                var best = g.OrderByDescending(c => Rank(c.State)).First();
                var it = new CodeItem { Code = best, State = best.State, Info = Catalog.Info(best.Id, r.BrandKey) };
                foreach (var c in g) if (!it.Modules.Contains(c.Module)) it.Modules.Add(c.Module);
                v.Items.Add(it);
            }
            v.Items.Sort((a, b) =>
            {
                int k = Rank(b.State).CompareTo(Rank(a.State));
                if (k != 0) return k;
                return Sev(b.Info != null ? b.Info.Severity : null).CompareTo(Sev(a.Info != null ? a.Info.Severity : null));
            });
            v.PriceFrom = v.Items.Where(i => i.Info != null && i.State != "history").Sum(i => i.Info.Price);
            var readKinds = new HashSet<string>(r.Modules.Where(m => m.Read).Select(m => m.Kind));
            foreach (var c in Core) if (!c.Any(readKinds.Contains)) v.Missing.Add(c.Length > 1 ? "приборная панель / кузовной блок" : Names.KindName(c[0]).ToLowerInvariant());

            int real = v.Items.Count(i => i.State != "history");
            bool serious = v.Items.Any(i => i.State != "history" && i.State != "pending" && i.Info != null && (i.Info.Stop || i.Info.Severity == "high"));
            if (r.Error != null) { v.Level = "error"; v.Title = "Проверка не прошла"; v.Text = r.Error; return v; }
            if (r.Modules.Count == 0)
            {
                v.Level = "error"; v.Title = "Машина не ответила";
                v.Text = "Ни один блок не ответил ни на одной линии колодки. Проверьте: зажигание включено, адаптер плотно в колодке и светится, программа Сканматик закрыта.";
                return v;
            }
            if (real > 0)
            {
                v.Level = serious ? "danger" : "warn";
                v.Title = serious ? "Есть серьёзные неисправности" : "Найдено ошибок: " + real;
                var first = v.Items[0];
                v.Text = (serious ? "Ошибок: " + real + ". " : "") + "Начать с " + first.Code.Base + (first.Info != null && first.Info.Title != "" ? " — " + first.Info.Title.TrimEnd('.') : "") + ". " +
                    "Блоки с ошибками: " + string.Join(", ", v.WithCodes.Select(m => m.Name)) + ".";
            }
            else if (v.Missing.Count == 0 && v.Unread.Count == 0)
            {
                v.Level = "ok"; v.Title = "Ошибок не найдено";
                v.Text = "Прочитаны все найденные блоки: " + string.Join(", ", v.Clean.Select(m => m.Name)) + ".";
            }
            else
            {
                v.Level = "partial"; v.Title = "В прочитанных блоках ошибок нет";
                var parts = new List<string>();
                if (v.Missing.Count > 0) parts.Add("Не найдены: " + string.Join(", ", v.Missing) + ".");
                if (v.Unread.Count > 0) parts.Add("Ответили, но память не отдали: " + string.Join(", ", v.Unread.Select(m => m.Name)) + ".");
                v.Text = string.Join(" ", parts);
            }
            if (r.Mil) v.Text += " Лампа «Check Engine» горит.";
            if (r.Cancelled) { v.Title = "Проверка остановлена"; v.Text = "Показано то, что успел прочитать. " + v.Text; if (v.Level == "ok") v.Level = "partial"; }
            return v;
        }
    }
}
