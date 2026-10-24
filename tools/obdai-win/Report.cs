// Отчёт одним HTML-файлом в «Документы\OBD AI»: тот же итог, что на экране, открывается в любом браузере, можно переслать.

using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;

namespace ObdAi
{
    static class Report
    {
        static string E(string s) { return WebUtility.HtmlEncode(s ?? ""); }

        public static string Save(ScanResult r, Verdict v)
        {
            Directory.CreateDirectory(App.DataDir);
            string stamp = r.LogPath != null ? Path.GetFileNameWithoutExtension(r.LogPath).Replace("obdai-log-", "") : DateTime.Now.ToString("yyyyMMdd-HHmmss");
            string car = (r.CarText ?? "машина").Split('(')[0].Trim();
            foreach (var c in Path.GetInvalidFileNameChars()) car = car.Replace(c, ' ');
            string path = Path.Combine(App.DataDir, "Отчёт " + car + " " + stamp + ".html");
            File.WriteAllText(path, Html(r, v), new UTF8Encoding(false));
            return path;
        }

        static string Html(ScanResult r, Verdict v)
        {
            var s = new StringBuilder();
            string accent = v.Level == "ok" ? "#B8F15A" : v.Level == "partial" ? "#5AD1F1" : v.Level == "warn" ? "#F5A524" : "#FF6B6B";
            s.Append("<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>OBD AI — отчёт</title><style>");
            s.Append(":root{--bg:#0B0D10;--s:#1B1F26;--st:#242932;--b:#2B303A;--t:#EEF0F4;--t2:#B9BFC9;--m:#9AA1AD;--a:#B8F15A;--w:#F5A524;--d:#FF6B6B;--sky:#5AD1F1}");
            s.Append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:15px/1.55 'Segoe UI',system-ui,sans-serif}main{max-width:900px;margin:0 auto;padding:32px 16px 60px}");
            s.Append(".card{background:linear-gradient(var(--st),var(--s));border:1px solid var(--b);border-radius:18px;padding:22px 26px;margin:16px 0;box-shadow:0 6px 20px #0006}");
            s.Append(".lab{font:600 11px 'Segoe UI';letter-spacing:.08em;text-transform:uppercase;color:var(--m)}h1{font:700 28px Bahnschrift,'Segoe UI';margin:6px 0 8px}h2{font:700 20px Bahnschrift,'Segoe UI';margin:28px 0 4px}");
            s.Append("h3{font:700 16px Bahnschrift,'Segoe UI';margin:6px 0}.mut{color:var(--t2)}.sm{font-size:13px;color:var(--m)}.code{font:700 22px Consolas,monospace}");
            s.Append(".price{float:right;background:#B8F15A22;color:var(--a);border-radius:14px;padding:3px 12px;font-weight:600}.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}");
            s.Append(".chip{border:1px solid var(--b);border-radius:12px;padding:6px 12px;font-size:13px}.chip i{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:7px}");
            s.Append("details summary{cursor:pointer;color:var(--a);font-weight:600;margin-top:10px}.sec{border-top:1px solid #22262D;margin-top:12px;padding-top:8px}ul,ol{margin:4px 0;padding-left:22px;color:var(--t2)}");
            s.Append("footer{color:var(--m);font-size:12px;margin-top:30px}</style></head><body><main>");
            s.Append("<div class=lab>OBD AI · отчёт о проверке · ").Append(DateTime.Now.ToString("dd.MM.yyyy HH:mm")).Append("</div>");
            s.Append("<div class=card style='border-color:").Append(accent).Append("55'><div class=lab>Итог проверки</div><h1 style='color:").Append(accent).Append("'>").Append(E(v.Title)).Append("</h1><div class=mut>").Append(E(v.Text)).Append("</div>");
            s.Append("<p class=sm>").Append(E(r.CarText ?? "Марка не определена")).Append(r.Vin != null ? " · VIN " + E(r.Vin) : "");
            if (!double.IsNaN(r.Volts)) s.Append(" · АКБ ").Append(r.Volts.ToString("0.0")).Append(" В");
            if (r.ObdProto != null) s.Append(" · ").Append(E(r.ObdProto));
            s.Append(" · за ").Append((int)r.Took.TotalMinutes).Append(':').Append(r.Took.Seconds.ToString("00"));
            if (v.PriceFrom > 0) s.Append(" · ремонт ").Append(E(Th.Price(v.PriceFrom)));
            s.Append("</p></div>");

            s.Append("<div class=card><div class=lab>Что проверено</div><div class=chips>");
            foreach (var m in r.Modules)
            {
                string c = !m.Read ? "#9AA1AD" : m.Codes.Count == 0 ? "#B8F15A" : "#F5A524";
                string w = !m.Read ? "не отдал память" : m.Codes.Count == 0 ? "чисто" : string.Join(", ", m.Codes.Select(x => x.Id));
                s.Append("<span class=chip title='").Append(E(m.Where)).Append("'><i style='background:").Append(c).Append("'></i>").Append(E(m.Name)).Append(" — ").Append(E(w)).Append("</span>");
            }
            foreach (var m in v.Missing) s.Append("<span class=chip style='border-style:dashed'><i style='background:#2B303A'></i>").Append(E(m)).Append(" — не найден</span>");
            s.Append("</div></div>");

            if (v.Items.Count > 0) s.Append("<h2>Ошибки и что с ними делать</h2>");
            foreach (var it in v.Items)
            {
                var i = it.Info;
                string sc = i == null ? "#9AA1AD" : i.Severity == "high" ? "#FF6B6B" : i.Severity == "medium" ? "#F5A524" : i.Severity == "low" ? "#5AD1F1" : "#9AA1AD";
                s.Append("<div class=card style='border-left:4px solid ").Append(sc).Append("'>");
                if (i != null && i.Price > 0) s.Append("<span class=price>").Append(E(Th.Price(i.Price))).Append("</span>");
                s.Append("<div class=code>").Append(E(it.Code.Id)).Append("</div><h3>").Append(E(i != null && i.Title != "" ? i.Title : "Нет в справочнике")).Append("</h3>");
                s.Append("<div class=sm style='color:").Append(sc).Append("'>").Append(E(Verdict.StateText(it.State))).Append(" · ").Append(E(string.Join(", ", it.Modules)));
                if (i != null) s.Append(" · ").Append(E(Th.SevText(i.Severity))).Append(i.Stop ? " · лучше не ехать" : "");
                s.Append("</div>");
                if (i != null)
                {
                    if (i.Meaning != "" && i.Meaning != null) s.Append("<p class=mut>").Append(E(i.Meaning)).Append("</p>");
                    if (i.Note != null) s.Append("<p class=sm>").Append(E(i.Note)).Append("</p>");
                    s.Append("<details").Append(v.Items[0] == it ? " open" : "").Append("><summary>Подробно: причины, проверки, ремонт</summary>");
                    Sec(s, "Что происходит", i.Explanation);
                    List(s, "Как проявляется", i.Symptoms, false);
                    List(s, i.Detailed ? "Причины — от частых к редким" : "Частые причины", i.Causes, true);
                    List(s, "Как проверить", i.Checks, true);
                    Sec(s, "Что делать", i.WhatToDo);
                    List(s, "Чего не делать", i.FalsePaths, false);
                    Sec(s, "Чем грозит", i.Risk);
                    Sec(s, "Как устроен узел", i.Story);
                    s.Append("</details>");
                }
                s.Append("</div>");
            }

            s.Append("<div class=card><div class=lab>Какие линии колодки проверены</div><ul>");
            foreach (var m in r.Modules.Where(m => !m.Read)) s.Append("<li>").Append(E(m.Name + " (" + m.Where + ") ответил, но память ошибок не отдал" + (m.Note != null ? ": " + m.Note : ""))).Append("</li>");
            foreach (var l in r.Lines) s.Append("<li>").Append(E(l)).Append("</li>");
            s.Append("</ul><p class=sm>Адаптер: ").Append(E(r.Adapter)).Append(r.Firmware != null ? " (" + E(r.Firmware) + ")" : "").Append(". Только чтение: в блоки ничего не записывалось.</p></div>");
            s.Append("<footer>OBD AI для Windows ").Append(App.Version).Append(" · расшифровки — база OBD AI (подробные записи) и открытая база Wal33D/dtc-database (MIT, только названия)</footer>");
            s.Append("</main></body></html>");
            return s.ToString();
        }

        static void Sec(StringBuilder s, string h, string t) { if (!string.IsNullOrEmpty(t)) s.Append("<div class=sec><div class=lab>").Append(E(h)).Append("</div><div class=mut>").Append(E(t)).Append("</div></div>"); }
        static void List(StringBuilder s, string h, System.Collections.Generic.List<string> l, bool num)
        {
            if (l == null || l.Count == 0) return;
            s.Append("<div class=sec><div class=lab>").Append(E(h)).Append("</div><").Append(num ? "ol" : "ul").Append('>');
            foreach (var x in l) s.Append("<li>").Append(E(x)).Append("</li>");
            s.Append("</").Append(num ? "ol" : "ul").Append("></div>");
        }
    }
}
