// Правая часть: вступление, ход проверки, итог — карточки рисуются сами, со своей прокруткой и появлением.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Windows.Forms;

namespace ObdAi
{
    abstract class Blk
    {
        public int Y, H; public double Born;   // момент появления, с
        public abstract int Measure(int w);
        public abstract void Paint(Graphics g, Rectangle r, double age);
        public virtual string Hit(Point p) { return null; }   // p — внутри блока
        public virtual bool Toggle(Point p) { return false; }
    }

    sealed class ResultView : Control
    {
        public event Action<string> Action;
        readonly List<Blk> blocks = new List<Blk>();
        double scroll, target; int content;
        bool dragThumb; int dragY; double dragScroll;
        const int Pad = 28, Gap = 18;

        public ResultView()
        {
            DoubleBuffered = true;
            SetStyle(ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.Selectable, true);
            TabStop = true;
        }

        double Now { get { return MainForm.Clock.Elapsed.TotalSeconds; } }

        void Set(IEnumerable<Blk> list)
        {
            blocks.Clear();
            double t = Now;
            int i = 0;
            foreach (var b in list) { b.Born = t + 0.09 * i++; blocks.Add(b); }
            scroll = target = 0;
            Layout2();
            Invalidate();
        }

        void Layout2()
        {
            int w = Math.Max(300, Width - Pad * 2 - 12), y = Pad;
            foreach (var b in blocks) { b.Y = y; b.H = b.Measure(w); y += b.H + Gap; }
            content = y + Pad;
            target = Math.Max(0, Math.Min(target, content - Height));
        }

        protected override void OnResize(EventArgs e) { base.OnResize(e); Layout2(); }

        public void Animate()
        {
            double max = Math.Max(0, content - Height);
            target = Math.Max(0, Math.Min(target, max));
            scroll += (target - scroll) * 0.22;
            if (Math.Abs(target - scroll) < 0.5) scroll = target;
            Invalidate();
        }

        protected override void OnMouseWheel(MouseEventArgs e) { target -= e.Delta / 120.0 * 90; }
        protected override void OnMouseEnter(EventArgs e) { Focus(); }

        protected override bool IsInputKey(Keys k) { return k == Keys.Up || k == Keys.Down || k == Keys.PageUp || k == Keys.PageDown || base.IsInputKey(k); }
        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Down) target += 60; else if (e.KeyCode == Keys.Up) target -= 60;
            else if (e.KeyCode == Keys.PageDown || e.KeyCode == Keys.Space) target += Height * 0.85; else if (e.KeyCode == Keys.PageUp) target -= Height * 0.85;
            else if (e.KeyCode == Keys.Home) target = 0; else if (e.KeyCode == Keys.End) target = content;
        }

        Rectangle Thumb()
        {
            if (content <= Height) return Rectangle.Empty;
            int h = Math.Max(40, Height * Height / content);
            int y = (int)((Height - h) * scroll / Math.Max(1, content - Height));
            return new Rectangle(Width - 10, y, 6, h);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var bg = new LinearGradientBrush(ClientRectangle, Th.Bg, Th.Mix(Th.Bg, Th.BgTop, 0.5), 90f)) g.FillRectangle(bg, ClientRectangle);
            double t = Now;
            int w = Math.Max(300, Width - Pad * 2 - 12);
            foreach (var b in blocks)
            {
                double age = t - b.Born;
                if (age < 0) continue;
                int dy = (int)((1 - Th.Ease(age / 0.45)) * 28);
                var r = new Rectangle(Pad, b.Y - (int)scroll + dy, w, b.H);
                if (r.Bottom < -40 || r.Y > Height + 40) continue;
                b.Paint(g, r, age);
            }
            var th = Thumb();
            if (!th.IsEmpty) using (var p = Th.Round(th, 3)) using (var br = new SolidBrush(Th.A(Th.Text2, dragThumb ? 120 : 60))) g.FillPath(br, p);
        }

        Blk At(Point p, out Point local)
        {
            local = Point.Empty;
            int w = Math.Max(300, Width - Pad * 2 - 12);
            foreach (var b in blocks)
            {
                var r = new Rectangle(Pad, b.Y - (int)scroll, w, b.H);
                if (r.Contains(p)) { local = new Point(p.X - r.X, p.Y - r.Y); return b; }
            }
            return null;
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            Focus();
            var th = Thumb();
            if (!th.IsEmpty && new Rectangle(th.X - 6, th.Y, th.Width + 12, th.Height).Contains(e.Location)) { dragThumb = true; dragY = e.Y; dragScroll = scroll; }
        }
        protected override void OnMouseUp(MouseEventArgs e) { dragThumb = false; }
        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (dragThumb)
            {
                var th = Thumb();
                double k = (content - Height) / Math.Max(1.0, Height - th.Height);
                target = scroll = Math.Max(0, Math.Min(content - Height, dragScroll + (e.Y - dragY) * k));
                return;
            }
            Point l; var b = At(e.Location, out l);
            Cursor = b != null && b.Hit(l) != null ? Cursors.Hand : Cursors.Default;
        }
        protected override void OnMouseClick(MouseEventArgs e)
        {
            if (dragThumb) return;
            Point l; var b = At(e.Location, out l);
            if (b == null) return;
            var h = b.Hit(l);
            if (h == null) return;
            if (h == "toggle") { b.Toggle(l); Layout2(); return; }
            if (Action != null) Action(h);
        }

        // ---------------- экраны ----------------
        public void ShowIntro(Device d, int count)
        {
            var list = new List<Blk>();
            list.Add(new Hero("Одна кнопка — и весь отчёт", "Программа сама пройдёт по всем линиям колодки: стандартный OBD, все блоки на CAN, K-line на каждом контакте. Ошибки объяснит по нашей базе: что случилось, как проверить, что делать и сколько стоит."));
            if (d == null)
                list.Add(new Note("danger", "Адаптер J2534 не найден", "Установите программу своего адаптера (для Scanmatik — «Сканматик 2»), она добавит драйвер J2534. Потом нажмите на «Адаптер» вверху → «Обновить список»."));
            else
            {
                list.Add(new Note("partial", "Перед проверкой", "1. Адаптер в колодке, зажигание включено (двигатель можно не глушить).\n2. Программу Сканматик закройте полностью — адаптер может работать только с одной программой.\n3. " +
                    (d.Wireless ? "Bluetooth: подключение должно быть добавлено в «SM2 J2534-RP1210 Configurator» (Пуск → Scanmatik)." : "По кабелю USB выберите «SM2 USB»; по Bluetooth — беспроводное подключение из «SM2 J2534-RP1210 Configurator».")));
                list.Add(new Facts("Что проверю", new[] {
                    "Стандартный OBD двигателя: CAN 500/250 кбит/с 11 и 29 бит, K-line (KWP2000, ISO 9141-2), J1850",
                    "VIN, лампа Check Engine, стоп-кадр, коды подтверждённые, неподтверждённые и постоянные",
                    "Все блоки на CAN: перебор адресов 700–7FF и 18DAxxF1, заводская память ошибок UDS и KWP2000",
                    "Другие шины CAN на контактах 3/11 и 12/13",
                    "K-line на контактах 7, 8, 1, 3, 9, 11, 12, 13 — ABS, подушки, кузов старых машин",
                    "Только чтение: в блоки ничего не пишется" }));
            }
            Set(list);
        }

        public void ShowMessage(string level, string title, string text) { Set(new Blk[] { new Note(level, title, text) }); }

        public void ShowProgress()
        {
            Set(new Blk[] { new Hero("Проверяю машину", "Ход проверки — слева. Не вынимайте адаптер и не выключайте зажигание. Глубокая проверка идёт дольше: K-line перебирается по всем адресам.") });
        }

        public void ShowResult(ScanResult r, Verdict v)
        {
            var list = new List<Blk>();
            list.Add(new VerdictBlk(r, v));
            if (r.Modules.Count > 0) list.Add(new CoverageBlk(r, v));
            if (v.Items.Count > 0)
            {
                list.Add(new SectionBlk("Ошибки и что с ними делать", v.Items.Count + " " + Plural(v.Items.Count, "код", "кода", "кодов") + (v.PriceFrom > 0 ? " · ремонт " + Th.Price(v.PriceFrom) : "")));
                for (int i = 0; i < v.Items.Count; i++) list.Add(new CodeBlk(v.Items[i], i == 0));
            }
            list.Add(new LinesBlk(r));
            list.Add(new ActionsBlk(r));
            Set(list);
        }

        public static string Plural(int n, string one, string few, string many)
        {
            int a = n % 100, b = n % 10;
            return a >= 11 && a <= 14 ? many : b == 1 ? one : b >= 2 && b <= 4 ? few : many;
        }
    }

    // ---------------- блоки ----------------
    sealed class Hero : Blk
    {
        readonly string title, text;
        public Hero(string title, string text) { this.title = title; this.text = text; }
        public override int Measure(int w) { return 20 + Th.H(title, Th.H1, w - 8) + 12 + Th.H(text, Th.Body, Math.Min(w, 760)) + 10; }
        public override void Paint(Graphics g, Rectangle r, double age)
        {
            int y = r.Y + 20;
            y += Th.T(g, title, Th.H1, r.X + 4, y, r.Width - 8, Th.Text) + 12;
            Th.T(g, text, Th.Body, r.X + 4, y, Math.Min(r.Width, 760), Th.Text2);
        }
    }

    static class Lv
    {
        public static void Colors(string level, out Color top, out Color bottom, out Color border, out Color accent, out Color text, out Color muted)
        {
            switch (level)
            {
                case "ok": top = Th.OkBgTop; bottom = Th.OkBg; border = Th.OkBorder; accent = Th.Accent; text = Th.Text; muted = Th.Text2; return;
                case "warn": top = Th.WarnBgTop; bottom = Th.WarnBg; border = Th.WarnBorder; accent = Th.Warn; text = Th.WarnText; muted = Th.WarnMuted; return;
                case "danger": case "error": top = Th.DangerBgTop; bottom = Th.DangerBg; border = Th.DangerBorder; accent = Th.Danger; text = Th.DangerText; muted = Th.DangerMuted; return;
                default: top = Th.SurfaceTop; bottom = Th.Surface; border = Th.Border; accent = Th.Sky; text = Th.Text; muted = Th.Text2; return;
            }
        }
    }

    sealed class Note : Blk
    {
        readonly string level, title, text;
        public Note(string level, string title, string text) { this.level = level; this.title = title; this.text = text; }
        public override int Measure(int w) { return 22 + Th.H(title, Th.H3, w - 48) + 8 + Th.H(text, Th.Body, w - 48) + 22; }
        public override void Paint(Graphics g, Rectangle r, double age)
        {
            Color top, bottom, border, accent, text0, muted;
            Lv.Colors(level, out top, out bottom, out border, out accent, out text0, out muted);
            Th.Card(g, r, top, bottom, border);
            using (var b = new SolidBrush(accent)) g.FillRectangle(b, r.X, r.Y + 18, 3, r.Height - 36);
            int y = r.Y + 22;
            y += Th.T(g, title, Th.H3, r.X + 24, y, r.Width - 48, text0) + 8;
            Th.T(g, text, Th.Body, r.X + 24, y, r.Width - 48, muted);
        }
    }

    sealed class Facts : Blk
    {
        readonly string title; readonly string[] items;
        public Facts(string title, string[] items) { this.title = title; this.items = items; }
        public override int Measure(int w) { int h = 24 + 24; foreach (var s in items) h += Th.H(s, Th.Body, w - 70) + 10; return h + 14; }
        public override void Paint(Graphics g, Rectangle r, double age)
        {
            Th.Card(g, r);
            Th.T1(g, title.ToUpperInvariant(), Th.Label, new Rectangle(r.X + 24, r.Y + 22, r.Width - 48, 16), Th.Muted);
            int y = r.Y + 48;
            for (int i = 0; i < items.Length; i++)
            {
                double a = Th.Ease((age - 0.25 - i * 0.07) / 0.4);
                using (var b = new SolidBrush(Th.A(Th.Accent, (int)(255 * a)))) g.FillEllipse(b, r.X + 26, y + 6, 7, 7);
                y += Th.T(g, items[i], Th.Body, r.X + 46, y, r.Width - 70, Th.Text2) + 10;
            }
        }
    }

    sealed class VerdictBlk : Blk
    {
        readonly ScanResult r; readonly Verdict v;
        public VerdictBlk(ScanResult r, Verdict v) { this.r = r; this.v = v; }
        string Sub()
        {
            var parts = new List<string>();
            parts.Add("за " + (int)r.Took.TotalMinutes + ":" + r.Took.Seconds.ToString("00"));
            parts.Add(r.Modules.Count + " " + ResultView.Plural(r.Modules.Count, "блок ответил", "блока ответили", "блоков ответили"));
            if (r.Deep) parts.Add("глубокая проверка");
            if (v.PriceFrom > 0) parts.Add("ремонт " + Th.Price(v.PriceFrom));
            return string.Join("  ·  ", parts);
        }
        public override int Measure(int w) { return 26 + 16 + 10 + Th.H(v.Title, Th.H1, w - 140) + 10 + Th.H(v.Text, Th.Body, w - 140) + 14 + 18 + 26; }
        public override void Paint(Graphics g, Rectangle rc, double age)
        {
            Color top, bottom, border, accent, text, muted;
            Lv.Colors(v.Level, out top, out bottom, out border, out accent, out text, out muted);
            Th.Card(g, rc, top, bottom, border, 22);
            // значок-печать: кольцо прорисовывается, внутри знак
            double a = Th.Ease(age / 0.8);
            var ic = new RectangleF(rc.Right - 98, rc.Y + 26, 64, 64);
            using (var pen = new Pen(Th.A(accent, 60), 5f)) g.DrawEllipse(pen, ic);
            using (var pen = new Pen(accent, 5f) { StartCap = LineCap.Round, EndCap = LineCap.Round }) g.DrawArc(pen, ic, -90, (float)(360 * a));
            float k = (float)Th.Back((age - 0.5) / 0.4);
            if (k > 0)
            {
                var st = g.Save();
                g.TranslateTransform(ic.X + 32, ic.Y + 32); g.ScaleTransform(k, k);
                using (var pen = new Pen(accent, 5f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
                {
                    if (v.Level == "ok") g.DrawLines(pen, new[] { new PointF(-13, 1), new PointF(-4, 10), new PointF(14, -10) });
                    else if (v.Level == "partial") { g.DrawLine(pen, -12, 0, 12, 0); }
                    else { g.DrawLine(pen, 0, -14, 0, 4); g.FillEllipse(new SolidBrush(accent), -3.5f, 9, 7, 7); }
                }
                g.Restore(st);
            }
            int y = rc.Y + 26;
            Th.T1(g, "ИТОГ ПРОВЕРКИ", Th.Label, new Rectangle(rc.X + 28, y, 300, 16), muted);
            y += 26;
            y += Th.T(g, v.Title, Th.H1, rc.X + 28, y, rc.Width - 140, text) + 10;
            y += Th.T(g, v.Text, Th.Body, rc.X + 28, y, rc.Width - 140, muted) + 14;
            Th.T1(g, Sub(), Th.SmallB, new Rectangle(rc.X + 28, y, rc.Width - 56, 18), accent);
        }
    }

    /// <summary>«Что проверено»: кольцо из сегментов — каждый найденный блок и каждый главный узел, которого не нашли.</summary>
    sealed class CoverageBlk : Blk
    {
        readonly ScanResult r; readonly Verdict v;
        readonly List<KeyValuePair<string, string>> segs = new List<KeyValuePair<string, string>>();   // имя, состояние
        public CoverageBlk(ScanResult r, Verdict v)
        {
            this.r = r; this.v = v;
            foreach (var m in r.Modules) segs.Add(new KeyValuePair<string, string>(m.Name, !m.Read ? "unread" : m.Codes.Count == 0 ? "clean" : "codes"));
            foreach (var m in v.Missing) segs.Add(new KeyValuePair<string, string>(char.ToUpper(m[0]) + m.Substring(1), "missing"));
        }
        static Color Col(string s) { return s == "clean" ? Th.Accent : s == "codes" ? Th.Warn : s == "unread" ? Th.Muted : Th.Border; }
        static string Word(string s) { return s == "clean" ? "чисто" : s == "codes" ? "есть ошибки" : s == "unread" ? "не отдал память" : "не найден"; }
        int Cols(int w) { return Math.Max(1, (w - 260) / 250); }
        public override int Measure(int w) { int rows = (segs.Count + Cols(w) - 1) / Cols(w); return Math.Max(250, 64 + rows * 46 + 20); }
        public override void Paint(Graphics g, Rectangle rc, double age)
        {
            Th.Card(g, rc);
            Th.T1(g, "ЧТО ПРОВЕРЕНО", Th.Label, new Rectangle(rc.X + 28, rc.Y + 24, 300, 16), Th.Muted);
            // кольцо с толщиной: нижняя «стенка» темнее, сверху — сегменты, они вылетают по очереди
            float cx = rc.X + 130, cy = rc.Y + rc.Height / 2f + 8, rad = 78;
            int n = segs.Count;
            float gap = n > 1 ? 4 : 0, sweep = 360f / n;
            for (int depth = 6; depth >= 0; depth--)
            {
                for (int i = 0; i < n; i++)
                {
                    double a = Th.Back((age - 0.15 - i * 0.06) / 0.55);
                    if (a <= 0) continue;
                    var c = Col(segs[i].Value);
                    if (depth > 0) c = Th.Mix(c, Color.Black, 0.55 + depth * 0.04);
                    float spin = (float)(age * 6);
                    float start = -90 + i * sweep + gap / 2 + spin, len = (float)((sweep - gap) * Math.Min(1, a));
                    float rr = (float)(rad * (0.85 + 0.15 * Math.Min(1.2, a)));
                    using (var pen = new Pen(c, 18f) { StartCap = LineCap.Flat, EndCap = LineCap.Flat })
                    {
                        if (segs[i].Value == "missing") pen.DashPattern = new[] { 0.35f, 0.25f };
                        g.DrawArc(pen, cx - rr, cy - rr * 0.92f + depth, rr * 2, rr * 1.84f, start, Math.Max(0.5f, len));
                    }
                }
            }
            int read = r.Modules.Count(m => m.Read);
            float up = (float)(Math.Sin(age * 1.6) * 2);
            Th.T1(g, read + "/" + segs.Count, Th.H1, new Rectangle((int)cx - 60, (int)(cy - 26 + up), 120, 34), Th.Text, TextFormatFlags.HorizontalCenter);
            Th.T1(g, "прочитано", Th.Small, new Rectangle((int)cx - 60, (int)(cy + 8 + up), 120, 18), Th.Muted, TextFormatFlags.HorizontalCenter);

            // плашки блоков
            int cols = Cols(rc.Width), cw = (rc.Width - 280) / cols;
            for (int i = 0; i < n; i++)
            {
                double a = Th.Ease((age - 0.3 - i * 0.05) / 0.4);
                if (a <= 0) continue;
                int x = rc.X + 260 + (i % cols) * cw, y = rc.Y + 56 + (i / cols) * 46 + (int)((1 - a) * 12);
                var cr = new Rectangle(x, y, cw - 12, 38);
                Th.Card(g, cr, Th.Surface, Th.Surface2, Th.Border, 12);
                using (var b = new SolidBrush(Col(segs[i].Value))) g.FillEllipse(b, cr.X + 12, cr.Y + 15, 9, 9);
                Th.T1(g, segs[i].Key, Th.SmallB, new Rectangle(cr.X + 30, cr.Y + 4, cr.Width - 36, 16), Th.Text);
                Th.T1(g, Word(segs[i].Value), Th.Small, new Rectangle(cr.X + 30, cr.Y + 19, cr.Width - 36, 16), Th.Muted);
            }
        }
    }

    sealed class SectionBlk : Blk
    {
        readonly string title, sub;
        public SectionBlk(string title, string sub) { this.title = title; this.sub = sub; }
        public override int Measure(int w) { return 46; }
        public override void Paint(Graphics g, Rectangle r, double age)
        {
            Th.T1(g, title, Th.H2, new Rectangle(r.X + 4, r.Y + 12, r.Width, 26), Th.Text);
            int tw = Th.W(title, Th.H2);
            Th.T1(g, sub, Th.Small, new Rectangle(r.X + 18 + tw, r.Y + 19, r.Width - tw - 20, 18), Th.Muted);
        }
    }

    sealed class CodeBlk : Blk
    {
        readonly CodeItem it; bool open;
        readonly List<KeyValuePair<string, object>> parts = new List<KeyValuePair<string, object>>();
        Rectangle toggle;
        public CodeBlk(CodeItem it, bool open)
        {
            this.it = it; this.open = open;
            var i = it.Info;
            if (i == null) return;
            Add("Что происходит", i.Explanation);
            Add("Как проявляется", i.Symptoms);
            Add(i.Detailed ? "Причины — от частых к редким" : "Частые причины", i.Causes);
            Add("Как проверить", i.Checks);
            Add("Что делать", i.WhatToDo);
            Add("Чего не делать", i.FalsePaths);
            Add("Чем грозит", i.Risk);
            Add("Как устроен узел" + (string.IsNullOrEmpty(i.FamilyTitle) ? "" : " · " + i.FamilyTitle), i.Story);
        }
        void Add(string h, object body)
        {
            if (body == null) return;
            if (body is string && ((string)body).Trim() == "") return;
            if (body is List<string> && ((List<string>)body).Count == 0) return;
            parts.Add(new KeyValuePair<string, object>(h, body));
        }
        DtcInfo I { get { return it.Info; } }
        string Title { get { return I != null && I.Title != "" ? I.Title : "Нет в справочнике"; } }
        string Meta { get { return string.Join("  ·  ", new[] { Verdict.StateText(it.State), string.Join(", ", it.Modules), I != null ? Th.SevText(I.Severity) : null, I != null && I.Stop ? "лучше не ехать" : null }.Where(s => !string.IsNullOrEmpty(s))); } }

        public override int Measure(int w)
        {
            int cw = w - 64;
            int h = 24 + 30 + 6 + Th.H(Title, Th.H3, cw - 150) + 8 + Th.H(Meta, Th.Small, cw) + 10;
            if (I != null && !string.IsNullOrEmpty(I.Meaning)) h += Th.H(I.Meaning, Th.Body, cw) + 8;
            if (I != null && I.Note != null) h += Th.H(I.Note, Th.Small, cw) + 8;
            if (open) foreach (var p in parts) h += PartH(p, cw) + 14;
            if (parts.Count > 0) h += 34;
            return h + 16;
        }

        static int PartH(KeyValuePair<string, object> p, int cw)
        {
            int h = 22;
            var list = p.Value as List<string>;
            if (list != null) foreach (var s in list) h += Th.H(s, Th.Body, cw - 22) + 6;
            else h += Th.H((string)p.Value, Th.Body, cw);
            return h;
        }

        public override void Paint(Graphics g, Rectangle r, double age)
        {
            Color sev = I != null ? Th.SevColor(I.Severity) : Th.Muted;
            if (it.State == "pending" || it.State == "history") sev = Th.Mix(sev, Th.Muted, 0.5);
            Th.Card(g, r);
            using (var b = new LinearGradientBrush(new Rectangle(r.X, r.Y + 16, 4, r.Height - 32), sev, Th.A(sev, 40), 90f)) g.FillRectangle(b, r.X, r.Y + 16, 4, r.Height - 32);
            int x = r.X + 32, cw = r.Width - 64, y = r.Y + 24;
            Th.T1(g, it.Code.Id, Th.MonoBig, new Rectangle(x, y, 260, 30), Th.Text);
            var price = I != null ? Th.Price(I.Price) : null;
            if (price != null)
            {
                int pw = Th.W(price, Th.BodyB) + 28;
                var pr = new Rectangle(r.Right - 32 - pw, y + 2, pw, 28);
                using (var p = Th.Round(pr, 14)) using (var b = new SolidBrush(Th.A(Th.Accent, 26))) g.FillPath(b, p);
                Th.T1(g, price, Th.BodyB, pr, Th.Accent, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
            // активная — дышащая точка рядом с кодом
            if (it.State == "active")
            {
                int cwid = Th.W(it.Code.Id, Th.MonoBig);
                float pulse = (float)((Math.Sin(age * 4) + 1) / 2);
                using (var b = new SolidBrush(Th.A(sev, (int)(60 + pulse * 60)))) g.FillEllipse(b, x + cwid + 8 - pulse * 3, y + 9 - pulse * 3, 12 + pulse * 6, 12 + pulse * 6);
                using (var b = new SolidBrush(sev)) g.FillEllipse(b, x + cwid + 11, y + 12, 6, 6);
            }
            y += 36;
            y += Th.T(g, Title, Th.H3, x, y, cw - 150, Th.Text) + 8;
            y += Th.T(g, Meta, Th.Small, x, y, cw, sev) + 10;
            if (I != null && !string.IsNullOrEmpty(I.Meaning)) y += Th.T(g, I.Meaning, Th.Body, x, y, cw, Th.Text2) + 8;
            if (I != null && I.Note != null) y += Th.T(g, I.Note, Th.Small, x, y, cw, Th.Muted) + 8;
            if (open)
                foreach (var p in parts)
                {
                    using (var pen = new Pen(Th.Divider)) g.DrawLine(pen, x, y + 4, x + cw, y + 4);
                    Th.T1(g, p.Key.ToUpperInvariant(), Th.Label, new Rectangle(x, y + 8, cw, 14), Th.Muted);
                    int yy = y + 26;
                    var list = p.Value as List<string>;
                    if (list != null)
                        for (int k = 0; k < list.Count; k++)
                        {
                            string mark = p.Key.StartsWith("Как проверить") || p.Key.StartsWith("Причины") ? (k + 1).ToString() : "•";
                            Th.T1(g, mark, Th.SmallB, new Rectangle(x, yy + 1, 18, 18), sev);
                            yy += Th.T(g, list[k], Th.Body, x + 22, yy, cw - 22, Th.Text2) + 6;
                        }
                    else Th.T(g, (string)p.Value, Th.Body, x, yy, cw, Th.Text2);
                    y += PartH(p, cw) + 14;
                }
            if (parts.Count > 0)
            {
                toggle = new Rectangle(x - r.X, y + 4 - r.Y, 220, 26);
                Th.T1(g, open ? "Свернуть  ▴" : "Подробно: причины, проверки, ремонт  ▾", Th.SmallB, new Rectangle(x, y + 8, 320, 18), Th.Accent);
            }
        }

        public override string Hit(Point p) { return parts.Count > 0 && new Rectangle(toggle.X, toggle.Y, 320, 30).Contains(p) ? "toggle" : null; }
        public override bool Toggle(Point p) { open = !open; return true; }
    }

    sealed class LinesBlk : Blk
    {
        readonly List<string> lines = new List<string>();
        public LinesBlk(ScanResult r)
        {
            foreach (var m in r.Modules.Where(m => !m.Read)) lines.Add(m.Name + " (" + m.Where + ") ответил, но память ошибок не отдал" + (m.Note != null ? ": " + m.Note : ""));
            lines.AddRange(r.Lines);
            if (r.Freeze != null) lines.Add("Стоп-кадр записан по коду " + r.Freeze);
        }
        public override int Measure(int w) { int h = 52; foreach (var s in lines) h += Th.H(s, Th.Small, w - 70) + 8; return h + 16; }
        public override void Paint(Graphics g, Rectangle r, double age)
        {
            Th.Card(g, r, Th.Surface, Th.Surface2, Th.Border);
            Th.T1(g, "КАКИЕ ЛИНИИ КОЛОДКИ ПРОВЕРЕНЫ", Th.Label, new Rectangle(r.X + 24, r.Y + 22, r.Width - 48, 16), Th.Muted);
            int y = r.Y + 48;
            foreach (var s in lines)
            {
                using (var b = new SolidBrush(Th.Border)) g.FillRectangle(b, r.X + 26, y + 6, 6, 6);
                y += Th.T(g, s, Th.Small, r.X + 44, y, r.Width - 70, Th.Text2) + 8;
            }
        }
    }

    sealed class ActionsBlk : Blk
    {
        readonly ScanResult r;
        readonly List<KeyValuePair<Rectangle, string>> btns = new List<KeyValuePair<Rectangle, string>>();
        public ActionsBlk(ScanResult r) { this.r = r; }
        public override int Measure(int w) { return 96; }
        public override void Paint(Graphics g, Rectangle rc, double age)
        {
            btns.Clear();
            int x = rc.X;
            foreach (var b in new[] { new[] { "report", "Открыть отчёт" }, new[] { "log", "Журнал обмена" }, new[] { "folder", "Папка с отчётами" } })
            {
                int w = Th.W(b[1], Th.BodyB) + 44;
                var br = new Rectangle(x, rc.Y + 6, w, 44);
                bool main = b[0] == "report";
                Th.Card(g, br, main ? Th.Accent : Th.SurfaceTop, main ? Th.AccentDim : Th.Surface, main ? Th.AccentDim : Th.Border, 22);
                Th.T1(g, b[1], Th.BodyB, br, main ? Th.Bg : Th.Text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
                btns.Add(new KeyValuePair<Rectangle, string>(new Rectangle(br.X - rc.X, br.Y - rc.Y, br.Width, br.Height), b[0]));
                x += w + 12;
            }
            Th.T1(g, "Отчёт и журнал сохранены: " + App.DataDir, Th.Small, new Rectangle(rc.X + 2, rc.Y + 62, rc.Width, 18), Th.Muted);
        }
        public override string Hit(Point p) { foreach (var b in btns) if (b.Key.Contains(p)) return b.Value; return null; }
    }
}
