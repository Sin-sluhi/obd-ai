// OBD AI для Windows: точка входа, папка данных, настройки, значок.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace ObdAi
{
    static class App
    {
        public const string Version = "0.1";
        public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "OBD AI");

        public static bool SimMode;

        [STAThread]
        static int Main(string[] args)
        {
            SimMode = Array.IndexOf(args, "--sim") >= 0;
            if (Array.IndexOf(args, "--selftest") >= 0) return SelfTest();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.ThreadException += (s, e) => Crash(e.Exception);
            AppDomain.CurrentDomain.UnhandledException += (s, e) => Crash(e.ExceptionObject as Exception);
            new Thread(Catalog.Load) { IsBackground = true, Name = "catalog" }.Start();
            Application.Run(new MainForm());
            return 0;
        }

        /// <summary>Прогон проверки на стенде без окна: итог в selftest.txt рядом с exe.</summary>
        static int SelfTest()
        {
            Catalog.Load();
            var tr = new Trace(Path.Combine(Path.GetTempPath(), "obdai-selftest"));
            var r = new Scanner(new Device { Name = "Стенд", Library = Sim.Lib }, tr, false).Run();
            tr.Close();
            var v = Verdict.Of(r);
            var sb = new StringBuilder();
            sb.AppendLine("VIN " + r.Vin + " | " + r.CarText + " | " + r.ObdProto + " | ошибка " + r.Error);
            foreach (var m in r.Modules) sb.AppendLine(m.Name + " [" + m.Where + "] " + m.Proto + " read=" + m.Read + " " + string.Join(", ", m.Codes.Select(c => c.Id + "/" + c.State)));
            sb.AppendLine("ИТОГ: " + v.Level + " | " + v.Title + " | " + v.Text + " | от " + v.PriceFrom);
            foreach (var i in v.Items) sb.AppendLine(i.Code.Id + " " + i.State + " " + string.Join("+", i.Modules) + " — " + (i.Info != null ? i.Info.Title + " / " + i.Info.Severity + " / " + i.Info.Price : "нет в базе"));
            foreach (var l in r.Lines) sb.AppendLine("линия: " + l);
            sb.AppendLine("отчёт: " + Report.Save(r, v));
            File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "selftest.txt"), sb.ToString(), Encoding.UTF8);
            return 0;
        }

        static void Crash(Exception e)
        {
            try { Directory.CreateDirectory(DataDir); File.AppendAllText(Path.Combine(DataDir, "crash.txt"), DateTime.Now + "\r\n" + e + "\r\n\r\n", Encoding.UTF8); } catch { }
            MessageBox.Show("Что-то пошло не так: " + (e != null ? e.Message : "?") + "\r\nПодробности — в «Документы\\OBD AI\\crash.txt».", "OBD AI");
        }

        /// <summary>Значок окна: лаймовый квадрат со скруглением и буквами AI, как в шапке.</summary>
        public static Icon MakeIcon()
        {
            using (var bmp = new Bitmap(64, 64))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                    using (var p = Th.Round(new RectangleF(2, 2, 60, 60), 16))
                    using (var b = new LinearGradientBrush(new Rectangle(0, 0, 64, 64), Th.Accent, Th.AccentDim, 60f)) g.FillPath(b, p);
                    using (var f = new Font(Th.HeadFamily, 24f, FontStyle.Bold, GraphicsUnit.Pixel))
                    using (var b = new SolidBrush(Th.Bg))
                        g.DrawString("AI", f, b, new RectangleF(0, 0, 64, 64), new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center });
                }
                return Icon.FromHandle(bmp.GetHicon());
            }
        }
    }

    /// <summary>Настройки — строки «ключ=значение» в Документы\OBD AI\settings.txt.</summary>
    static class Settings
    {
        static string PathName { get { return Path.Combine(App.DataDir, "settings.txt"); } }
        static Dictionary<string, string> Read()
        {
            var d = new Dictionary<string, string>();
            try { foreach (var l in File.ReadAllLines(PathName, Encoding.UTF8)) { int i = l.IndexOf('='); if (i > 0) d[l.Substring(0, i)] = l.Substring(i + 1); } } catch { }
            return d;
        }
        public static string Get(string k) { string v; return Read().TryGetValue(k, out v) ? v : null; }
        public static void Set(string k, string v)
        {
            try
            {
                var d = Read(); d[k] = v;
                Directory.CreateDirectory(App.DataDir);
                var sb = new StringBuilder();
                foreach (var kv in d) sb.Append(kv.Key).Append('=').Append(kv.Value).Append("\r\n");
                File.WriteAllText(PathName, sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }
    }
}
