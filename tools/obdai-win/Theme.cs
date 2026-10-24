// Палитра «приборная панель ночью» — цвета из ui/Theme.kt приложения.

using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Linq;
using System.Windows.Forms;

namespace ObdAi
{
    static class Th
    {
        static Color C(int rgb) { return Color.FromArgb(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF); }
        public static readonly Color Bg = C(0x0B0D10), BgTop = C(0x151920), Surface = C(0x1B1F26), SurfaceTop = C(0x242932), Surface2 = C(0x12151A),
            Border = C(0x2B303A), Divider = C(0x22262D), Text = C(0xEEF0F4), Text2 = C(0xB9BFC9), Muted = C(0x9AA1AD),
            Accent = C(0xB8F15A), AccentDim = C(0x5C7A2D), Sky = C(0x5AD1F1),
            Warn = C(0xF5A524), WarnBg = C(0x2A1F10), WarnBgTop = C(0x3A2A14), WarnBorder = C(0x5A3E14), WarnText = C(0xFFE6C2), WarnMuted = C(0xE3C293),
            Danger = C(0xFF6B6B), DangerBg = C(0x2A1414), DangerBgTop = C(0x3A1A1A), DangerBorder = C(0x5A1E1E), DangerText = C(0xFFD9D9), DangerMuted = C(0xE3A3A3),
            OkBg = C(0x1B2417), OkBgTop = C(0x243320), OkBorder = C(0x3A5226);

        public static readonly string HeadFamily = Has("Bahnschrift") ? "Bahnschrift" : "Segoe UI";
        public static readonly Font Logo = new Font(HeadFamily, 17f, FontStyle.Bold),
            H1 = new Font(HeadFamily, 21f, FontStyle.Bold), H2 = new Font(HeadFamily, 15f, FontStyle.Bold), H3 = new Font(HeadFamily, 12f, FontStyle.Bold),
            Btn = new Font(HeadFamily, 14f, FontStyle.Bold),
            Body = new Font("Segoe UI", 10.5f), BodyB = new Font("Segoe UI Semibold", 10.5f), Small = new Font("Segoe UI", 9f), SmallB = new Font("Segoe UI Semibold", 9f),
            Label = new Font("Segoe UI Semibold", 8.5f), Mono = new Font("Consolas", 10f), MonoBig = new Font("Consolas", 17f, FontStyle.Bold), MonoMid = new Font("Consolas", 12f, FontStyle.Bold);

        static bool Has(string family) { using (var f = new InstalledFontCollection()) return f.Families.Any(x => x.Name == family); }

        public static GraphicsPath Round(RectangleF r, float rad)
        {
            var p = new GraphicsPath();
            float d = Math.Min(rad * 2, Math.Min(r.Width, r.Height));
            if (d <= 0) { p.AddRectangle(r); return p; }
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        /// <summary>Объёмная карточка: градиент сверху вниз, светлая кромка сверху, тень снизу.</summary>
        public static void Card(Graphics g, Rectangle r, Color top, Color bottom, Color border, float rad = 18)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var sh = Round(new RectangleF(r.X + 1, r.Y + 5, r.Width - 2, r.Height), rad))
            using (var b = new SolidBrush(Color.FromArgb(90, 0, 0, 0))) g.FillPath(b, sh);
            using (var p = Round(r, rad))
            {
                using (var lg = new LinearGradientBrush(new Rectangle(r.X, r.Y - 1, r.Width, r.Height + 2), top, bottom, 90f)) g.FillPath(lg, p);
                using (var pen = new Pen(border, 1f)) g.DrawPath(pen, p);
            }
            using (var pen = new Pen(Color.FromArgb(22, 255, 255, 255), 1f))
                g.DrawLine(pen, r.X + rad, r.Y + 1, r.Right - rad, r.Y + 1);
        }

        public static void Card(Graphics g, Rectangle r) { Card(g, r, SurfaceTop, Surface, Border); }

        const TextFormatFlags Wrap = TextFormatFlags.WordBreak | TextFormatFlags.NoPadding | TextFormatFlags.TextBoxControl | TextFormatFlags.NoPrefix;
        const TextFormatFlags Line = TextFormatFlags.SingleLine | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.EndEllipsis;

        public static int H(string s, Font f, int w) { return string.IsNullOrEmpty(s) ? 0 : TextRenderer.MeasureText(s, f, new Size(Math.Max(10, w), int.MaxValue), Wrap).Height; }
        public static int W(string s, Font f) { return string.IsNullOrEmpty(s) ? 0 : TextRenderer.MeasureText(s, f, new Size(int.MaxValue, int.MaxValue), Line).Width; }
        /// <summary>Текст с переносом; возвращает высоту.</summary>
        public static int T(Graphics g, string s, Font f, int x, int y, int w, Color c)
        {
            if (string.IsNullOrEmpty(s)) return 0;
            int h = H(s, f, w);
            TextRenderer.DrawText(g, s, f, new Rectangle(x, y, w, h + 2), c, Wrap);
            return h;
        }
        public static void T1(Graphics g, string s, Font f, Rectangle r, Color c, TextFormatFlags extra = 0) { TextRenderer.DrawText(g, s, f, r, c, Line | extra); }

        public static Color Mix(Color a, Color b, double t)
        {
            t = Math.Max(0, Math.Min(1, t));
            return Color.FromArgb((int)(a.A + (b.A - a.A) * t), (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
        }
        public static Color A(Color c, int alpha) { return Color.FromArgb(Math.Max(0, Math.Min(255, alpha)), c); }
        public static double Ease(double t) { t = Math.Max(0, Math.Min(1, t)); return 1 - Math.Pow(1 - t, 3); }
        public static double Back(double t) { t = Math.Max(0, Math.Min(1, t)); const double c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.Pow(t - 1, 3) + c1 * Math.Pow(t - 1, 2); }

        public static Color SevColor(string sev) { return sev == "high" ? Danger : sev == "medium" ? Warn : sev == "low" ? Sky : Muted; }
        public static string SevText(string sev) { return sev == "high" ? "Серьёзно" : sev == "medium" ? "Средне" : sev == "low" ? "Не срочно" : "Серьёзность не определена"; }
        public static string Price(int p) { return p <= 0 ? null : "от " + p.ToString("N0", System.Globalization.CultureInfo.GetCultureInfo("ru-RU")) + " ₽"; }
    }

    /// <summary>Тёмное меню выбора адаптера.</summary>
    sealed class DarkColors : ProfessionalColorTable
    {
        public override Color MenuItemSelected { get { return Th.SurfaceTop; } }
        public override Color MenuItemBorder { get { return Th.Border; } }
        public override Color MenuBorder { get { return Th.Border; } }
        public override Color ToolStripDropDownBackground { get { return Th.Surface; } }
        public override Color ImageMarginGradientBegin { get { return Th.Surface; } }
        public override Color ImageMarginGradientMiddle { get { return Th.Surface; } }
        public override Color ImageMarginGradientEnd { get { return Th.Surface; } }
        public override Color SeparatorDark { get { return Th.Border; } }
        public override Color MenuItemSelectedGradientBegin { get { return Th.SurfaceTop; } }
        public override Color MenuItemSelectedGradientEnd { get { return Th.SurfaceTop; } }
    }
}
