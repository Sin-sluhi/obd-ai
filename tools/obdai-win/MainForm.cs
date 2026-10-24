// Главное окно: шапка с «пульсом шины», слева — машина, кнопка проверки и ход проверки, справа — итог.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace ObdAi
{
    sealed class MainForm : Form
    {
        readonly Header header = new Header();
        readonly Side side = new Side();
        readonly ResultView view = new ResultView();
        readonly System.Windows.Forms.Timer tick = new System.Windows.Forms.Timer { Interval = 16 };
        List<Device> devices = new List<Device>();
        Device device;
        Scanner scanner; Thread worker; Trace trace;
        public static readonly Stopwatch Clock = Stopwatch.StartNew();

        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        public MainForm()
        {
            Text = "OBD AI";
            BackColor = Th.Bg;
            ClientSize = new Size(1220, 800);
            MinimumSize = new Size(1000, 660);
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;
            Icon = App.MakeIcon();

            header.Dock = DockStyle.Top; header.Height = 72;
            side.Dock = DockStyle.Left; side.Width = 380;
            view.Dock = DockStyle.Fill;
            Controls.Add(view); Controls.Add(side); Controls.Add(header);

            header.AdapterClick += ShowAdapters;
            side.CheckClick += () => { if (worker != null) StopCheck(); else StartCheck(); };
            view.Action += OnAction;

            tick.Tick += (s, e) => { header.Invalidate(); side.Animate(); view.Animate(); };
            tick.Start();
            LoadDevices();
            view.ShowIntro(device, devices.Count);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            // тёмный заголовок окна (Windows 10 1809+: атрибут 19, с 20H1 — 20)
            int on = 1;
            if (DwmSetWindowAttribute(Handle, 20, ref on, 4) != 0) DwmSetWindowAttribute(Handle, 19, ref on, 4);
        }

        void LoadDevices()
        {
            devices = Device.Find();
            if (App.SimMode) devices.Insert(0, new Device { Name = "Стенд: Elantra HD 2009 (запись)", Vendor = "OBD AI", Library = Sim.Lib });
            var last = Settings.Get("device");
            device = devices.FirstOrDefault(d => d.Library == last) ?? devices.FirstOrDefault(d => d.Wireless) ?? devices.FirstOrDefault();
            header.AdapterText = device != null ? device.Name : "Адаптер J2534 не найден";
            header.AdapterOk = device != null;
            header.Invalidate();
        }

        void ShowAdapters()
        {
            if (worker != null) return;
            LoadDevices();
            var menu = new ContextMenuStrip { Renderer = new ToolStripProfessionalRenderer(new DarkColors()), ShowImageMargin = false, BackColor = Th.Surface, ForeColor = Th.Text, Font = Th.Body };
            foreach (var d in devices)
            {
                var dd = d;
                var it = new ToolStripMenuItem((d == device ? "●  " : "     ") + d.Name + (d.Wireless ? "  · беспроводной" : "")) { ForeColor = Th.Text };
                it.Click += (s, e) => { device = dd; Settings.Set("device", dd.Library); header.AdapterText = dd.Name; header.Invalidate(); view.ShowIntro(device, devices.Count); };
                menu.Items.Add(it);
            }
            if (devices.Count == 0) menu.Items.Add(new ToolStripMenuItem("Нет адаптеров J2534 — установите программу адаптера (Сканматик и т. п.)") { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());
            var refresh = new ToolStripMenuItem("Обновить список") { ForeColor = Th.Muted };
            refresh.Click += (s, e) => { LoadDevices(); view.ShowIntro(device, devices.Count); };
            menu.Items.Add(refresh);
            menu.Show(header, new Point(header.Width - 20 - menu.PreferredSize.Width, header.Height - 8));
        }

        void StartCheck()
        {
            if (device == null) { LoadDevices(); if (device == null) { view.ShowIntro(null, 0); return; } }
            int bits = device.Library == Sim.Lib ? 0 : device.Bits(), mine = Environment.Is64BitProcess ? 64 : 32;
            if (bits != 0 && bits != mine)
            {
                view.ShowMessage("danger", "Нужна другая версия программы", string.Format("Драйвер адаптера {0}-битный, а эта программа {1}-битная. Запустите «OBD AI {2}».", bits, mine, bits == 64 ? "x64" : "x86"));
                return;
            }
            try { trace = new Trace(App.DataDir); }
            catch (Exception e) { view.ShowMessage("danger", "Не создать журнал", e.Message); return; }
            scanner = new Scanner(device, trace, side.Deep);
            side.Begin(side.Deep);
            header.Busy = true;
            view.ShowProgress();
            trace.OnEvent = s => BeginInvoke((Action)(() => { side.AddEvent(s); header.Kick(); }));
            scanner.Stage = (s, p) => BeginInvoke((Action)(() => side.SetStage(s, p)));
            var sc = scanner;
            worker = new Thread(() =>
            {
                ScanResult r;
                try { r = sc.Run(); }
                catch (Exception e) { r = new ScanResult { Error = e.Message }; }
                trace.Close();
                try { BeginInvoke((Action)(() => Finish(r))); } catch { }
            }) { IsBackground = true, Name = "scan" };
            worker.Start();
        }

        void StopCheck() { if (scanner != null) { scanner.Cancel = true; side.Stopping(); } }

        ScanResult last;
        void Finish(ScanResult r)
        {
            worker = null; scanner = null;
            header.Busy = false;
            last = r;
            var v = Verdict.Of(r);
            side.End(r, v);
            view.ShowResult(r, v);
            try { Report.Save(r, v); } catch (Exception e) { r.LogPath = r.LogPath ?? e.Message; }
            Activate();
        }

        void OnAction(string what)
        {
            try
            {
                if (what == "report" && last != null) Process.Start(Report.Save(last, Verdict.Of(last)));
                else if (what == "log" && last != null && File.Exists(last.LogPath)) Process.Start("notepad.exe", "\"" + last.LogPath + "\"");
                else if (what == "folder") Process.Start("explorer.exe", "\"" + App.DataDir + "\"");
                else if (what == "configurator") OpenConfigurator();
                else if (what == "again") StartCheck();
            }
            catch (Exception e) { MessageBox.Show(this, e.Message, "OBD AI"); }
        }

        static void OpenConfigurator()
        {
            foreach (var p in new[] { @"C:\Program Files (x86)\Scanmatik\SM2_J2534_Config.exe", @"C:\Program Files (x86)\Scanmatik" })
                if (File.Exists(p) || Directory.Exists(p)) { Process.Start("explorer.exe", "\"" + p + "\""); return; }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (worker != null)
            {
                // дать адаптеру закрыть канал (PassThruClose), иначе драйвер может остаться занятым
                scanner.Cancel = true;
                worker.Join(4000);
            }
            base.OnFormClosing(e);
        }
    }

    // ---------------- шапка ----------------
    sealed class Header : Control
    {
        public string AdapterText = ""; public bool AdapterOk; public bool Busy;
        public event Action AdapterClick;
        double energy;   // всплески «пульса» на каждое событие
        Rectangle adapterRect;
        bool hover;

        public Header() { DoubleBuffered = true; SetStyle(ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint, true); Cursor = Cursors.Default; }
        public void Kick() { energy = Math.Min(1.6, energy + 0.7); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var bg = new LinearGradientBrush(ClientRectangle, Th.BgTop, Th.Bg, 90f)) g.FillRectangle(bg, ClientRectangle);
            double t = MainForm.Clock.Elapsed.TotalSeconds;
            energy *= 0.96;
            // осциллограф шины: спокойная линия, при проверке — пачки импульсов
            int w = Width, mid = Height / 2 + 6;
            double amp = (Busy ? 7 : 2.2) + energy * 9;
            var pts = new PointF[w / 3 + 2];
            for (int i = 0; i < pts.Length; i++)
            {
                double x = i * 3;
                double y = Math.Sin(x / 37 + t * 2.1) * amp * 0.45 + Math.Sin(x / 11 - t * 5.3) * amp * 0.25;
                if (Busy) { double burst = Math.Max(0, Math.Sin(x / 90 - t * 6)); y += (Math.Sin(x * 1.7 + t * 40) > 0 ? 1 : -1) * amp * burst * burst; }
                pts[i] = new PointF((float)x, (float)(mid + y));
            }
            using (var pen = new Pen(Th.A(Th.Accent, Busy ? 70 : 34), 1.6f)) g.DrawLines(pen, pts);
            using (var pen = new Pen(Th.A(Th.Accent, 14), 6f)) g.DrawLines(pen, pts);
            using (var pen = new Pen(Th.Divider)) g.DrawLine(pen, 0, Height - 1, Width, Height - 1);

            // логотип
            var lr = new Rectangle(28, 16, 54, 40);
            using (var p = Th.Round(new RectangleF(28, 18, 36, 36), 11))
            {
                using (var b = new LinearGradientBrush(new Rectangle(28, 18, 36, 36), Th.Accent, Th.AccentDim, 60f)) g.FillPath(b, p);
            }
            Th.T1(g, "AI", Th.H3, new Rectangle(28, 18, 36, 36), Th.Bg, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            Th.T1(g, "OBD AI", Th.Logo, new Rectangle(76, 15, 200, 28), Th.Text);
            Th.T1(g, "для Windows · адаптеры J2534", Th.Small, new Rectangle(77, 41, 260, 18), Th.Muted);

            // выбор адаптера
            string label = "Адаптер";
            int tw = Math.Max(Th.W(AdapterText, Th.BodyB), Th.W(label, Th.Label)) + 64;
            adapterRect = new Rectangle(Width - tw - 24, 14, tw, 44);
            Th.Card(g, adapterRect, hover ? Th.Mix(Th.SurfaceTop, Th.Border, 0.4) : Th.SurfaceTop, Th.Surface, Th.Border, 14);
            using (var b = new SolidBrush(AdapterOk ? Th.Accent : Th.Danger)) g.FillEllipse(b, adapterRect.X + 14, adapterRect.Y + 19, 8, 8);
            Th.T1(g, label.ToUpperInvariant(), Th.Label, new Rectangle(adapterRect.X + 32, adapterRect.Y + 6, tw - 50, 14), Th.Muted);
            Th.T1(g, AdapterText, Th.BodyB, new Rectangle(adapterRect.X + 32, adapterRect.Y + 20, tw - 50, 20), Th.Text);
            Th.T1(g, "▾", Th.Body, new Rectangle(adapterRect.Right - 24, adapterRect.Y + 12, 16, 20), Th.Muted);
        }

        protected override void OnMouseMove(MouseEventArgs e) { bool h = adapterRect.Contains(e.Location); if (h != hover) { hover = h; Cursor = h ? Cursors.Hand : Cursors.Default; } }
        protected override void OnMouseLeave(EventArgs e) { hover = false; }
        protected override void OnMouseClick(MouseEventArgs e) { if (adapterRect.Contains(e.Location) && AdapterClick != null) AdapterClick(); }
    }

    // ---------------- левая колонка ----------------
    sealed class Side : Control
    {
        public event Action CheckClick;
        public bool Deep { get { return deep; } }
        bool deep, busy, stopping, hoverBtn, hoverDeep;
        double progress, shown; string stage = "Подключите адаптер и включите зажигание";
        readonly List<KeyValuePair<double, string>> events = new List<KeyValuePair<double, string>>();
        readonly List<string[]> stages = new List<string[]>();   // [название, состояние: wait/run/done]
        Rectangle btnRect, deepRect;
        Stopwatch run;
        ScanResult result; Verdict verdict;

        static readonly string[] StageNames = { "Связь", "Двигатель", "Блоки CAN", "Другие шины", "K-line", "Разбор" };

        public Side() { DoubleBuffered = true; SetStyle(ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint, true); }

        public void Begin(bool deepMode)
        {
            busy = true; stopping = false; progress = 0; shown = 0; result = null; verdict = null;
            events.Clear(); stages.Clear();
            foreach (var s in StageNames) stages.Add(new[] { s, "wait" });
            stages[0][1] = "run";
            run = Stopwatch.StartNew();
            stage = "Связь с адаптером";
            Invalidate();
        }

        public void Stopping() { stopping = true; stage = "Останавливаю…"; }

        public void SetStage(string s, double p)
        {
            if (s != null)
            {
                stage = s;
                int k = s.StartsWith("Связь") ? 0 : s.StartsWith("Двигатель") ? 1 : s.StartsWith("Блоки") || s.StartsWith("Читаю блоки") ? 2 : s.StartsWith("Другие") ? 3 : s.StartsWith("K-line") ? 4 : s == "Готово" ? 5 : -1;
                if (k >= 0) for (int i = 0; i < stages.Count; i++) stages[i][1] = i < k ? "done" : i == k ? "run" : stages[i][1] == "done" ? "done" : "wait";
            }
            progress = Math.Max(progress, p);
        }

        public void AddEvent(string s) { events.Add(new KeyValuePair<double, string>(MainForm.Clock.Elapsed.TotalSeconds, s)); if (events.Count > 60) events.RemoveAt(0); }

        public void End(ScanResult r, Verdict v)
        {
            busy = false; result = r; verdict = v; progress = 1;
            foreach (var s in stages) s[1] = "done";
            stage = v.Title;
        }

        public void Animate() { shown += (progress - shown) * 0.08; Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var b = new SolidBrush(Th.Bg)) g.FillRectangle(b, ClientRectangle);
            using (var pen = new Pen(Th.Divider)) g.DrawLine(pen, Width - 1, 0, Width - 1, Height);
            double t = MainForm.Clock.Elapsed.TotalSeconds;
            int x = 24, w = Width - 48, y = 22;

            // ---- карточка машины ----
            var car = new Rectangle(x, y, w, 118);
            Th.Card(g, car);
            Th.T1(g, "ВАША МАШИНА", Th.Label, new Rectangle(x + 20, y + 16, w - 40, 14), Th.Muted);
            if (result != null && (result.CarText != null || result.Vin != null))
            {
                Th.T1(g, result.CarText ?? "Марка не определена", Th.H2, new Rectangle(x + 20, y + 34, w - 40, 28), Th.Text);
                Th.T1(g, result.Vin != null ? "VIN " + result.Vin : "VIN не передан", Th.Mono, new Rectangle(x + 20, y + 66, w - 40, 18), Th.Text2);
            }
            else
            {
                Th.T1(g, busy ? "Узнаю машину…" : result != null ? "Машина не назвалась" : "Готов к проверке", Th.H2, new Rectangle(x + 20, y + 34, w - 40, 28), Th.Text);
                Th.T1(g, busy ? "VIN и блоки появятся по ходу" : "Адаптер в колодку, зажигание включить", Th.Small, new Rectangle(x + 20, y + 66, w - 40, 18), Th.Muted);
            }
            string volts = result != null && !double.IsNaN(result.Volts) ? result.Volts.ToString("0.0") + " В" : "—";
            Th.T1(g, "АКБ  " + volts + (result != null && result.ObdProto != null ? "   ·   " + result.ObdProto : ""), Th.Small, new Rectangle(x + 20, y + 90, w - 40, 18), Th.Muted);
            y = car.Bottom + 26;

            // ---- живая кнопка проверки ----
            int d = 196;
            int cx = Width / 2, cy = y + d / 2 + 14;
            btnRect = new Rectangle(cx - d / 2, cy - d / 2, d, d);
            // дыхание и кольца
            double breath = (Math.Sin(t * (busy ? 4.2 : 1.6)) + 1) / 2;
            for (int i = 0; i < 3; i++)
            {
                double ph = ((t * (busy ? 0.9 : 0.45)) + i / 3.0) % 1.0;
                float rr = (float)(d / 2 + 6 + ph * 34);
                using (var pen = new Pen(Th.A(busy ? Th.Sky : Th.Accent, (int)(70 * (1 - ph))), 2f)) g.DrawEllipse(pen, cx - rr, cy - rr, rr * 2, rr * 2);
            }
            float glow = (float)(d / 2 + 10 + breath * 6);
            using (var gp = new GraphicsPath())
            {
                gp.AddEllipse(cx - glow, cy - glow, glow * 2, glow * 2);
                using (var pg = new PathGradientBrush(gp) { CenterColor = Th.A(busy ? Th.Sky : Th.Accent, hoverBtn ? 110 : 70), SurroundColors = new[] { Color.FromArgb(0, 0, 0, 0) } }) g.FillPath(pg, gp);
            }
            var face = new RectangleF(cx - d / 2f, cy - d / 2f, d, d);
            Color c1 = busy ? Th.Mix(Th.SurfaceTop, Th.Sky, 0.12) : Th.Mix(Th.Accent, Color.White, hoverBtn ? 0.15 : 0.05);
            Color c2 = busy ? Th.Surface2 : Th.AccentDim;
            using (var b = new LinearGradientBrush(face, c1, c2, 70f)) g.FillEllipse(b, face);
            using (var pen = new Pen(Th.A(Color.White, 40), 1.5f)) g.DrawArc(pen, face.X + 6, face.Y + 6, face.Width - 12, face.Height - 12, 200, 140);
            if (busy || result != null)
            {
                // дуга хода проверки
                var ar = new RectangleF(face.X + 12, face.Y + 12, face.Width - 24, face.Height - 24);
                using (var pen = new Pen(Th.A(Th.Border, 200), 6f)) g.DrawEllipse(pen, ar);
                if (busy) using (var pen = new Pen(Th.Sky, 6f) { StartCap = LineCap.Round, EndCap = LineCap.Round }) g.DrawArc(pen, ar, -90, (float)(360 * Math.Max(0.02, shown)));
            }
            Color tc = busy ? Th.Text : Th.Bg;
            if (busy)
            {
                Th.T1(g, ((int)(shown * 100)).ToString() + "%", Th.H1, new Rectangle(cx - 80, cy - 34, 160, 34), tc, TextFormatFlags.HorizontalCenter);
                var el = run != null ? run.Elapsed : TimeSpan.Zero;
                Th.T1(g, string.Format("{0}:{1:00}", (int)el.TotalMinutes, el.Seconds), Th.Mono, new Rectangle(cx - 80, cy + 2, 160, 18), Th.Text2, TextFormatFlags.HorizontalCenter);
                Th.T1(g, stopping ? "останавливаю" : "нажмите — стоп", Th.Small, new Rectangle(cx - 80, cy + 26, 160, 18), Th.Muted, TextFormatFlags.HorizontalCenter);
            }
            else
            {
                Th.T1(g, result != null ? "Проверить" : "Проверить", Th.Btn, new Rectangle(cx - 90, cy - 22, 180, 24), tc, TextFormatFlags.HorizontalCenter);
                Th.T1(g, result != null ? "ещё раз" : "машину", Th.Btn, new Rectangle(cx - 90, cy + 2, 180, 24), tc, TextFormatFlags.HorizontalCenter);
            }
            y = cy + d / 2 + 40;

            // ---- глубокая проверка ----
            deepRect = new Rectangle(x, y, w, 52);
            Th.Card(g, deepRect, hoverDeep && !busy ? Th.Mix(Th.SurfaceTop, Th.Border, 0.5) : Th.SurfaceTop, Th.Surface, deep ? Th.AccentDim : Th.Border, 14);
            var sw = new RectangleF(deepRect.Right - 58, deepRect.Y + 15, 40, 22);
            using (var p = Th.Round(sw, 11)) using (var b = new SolidBrush(deep ? Th.Accent : Th.Border)) g.FillPath(b, p);
            float kx = deep ? sw.Right - 20 : sw.X + 2;
            using (var b = new SolidBrush(deep ? Th.Bg : Th.Muted)) g.FillEllipse(b, kx, sw.Y + 2, 18, 18);
            Th.T1(g, "Глубокая проверка", Th.BodyB, new Rectangle(x + 18, y + 8, w - 90, 20), busy ? Th.Muted : Th.Text);
            Th.T1(g, deep ? "все адреса K-line и 5 бод · около 12 минут" : "обычная · около 3 минут", Th.Small, new Rectangle(x + 18, y + 28, w - 90, 16), Th.Muted);
            y = deepRect.Bottom + 22;

            // ---- этапы ----
            if (stages.Count > 0)
            {
                int sx = x + 6, step = (w - 12) / stages.Count;
                for (int i = 0; i < stages.Count; i++)
                {
                    var st = stages[i][1];
                    int px = sx + i * step + step / 2;
                    if (i > 0) using (var pen = new Pen(stages[i - 1][1] == "done" ? Th.AccentDim : Th.Border, 2f)) g.DrawLine(pen, px - step + 9, y + 8, px - 9, y + 8);
                    Color c = st == "done" ? Th.Accent : st == "run" ? Th.Sky : Th.Border;
                    float r = st == "run" ? (float)(6 + breath * 2) : 6;
                    if (st == "run") using (var b = new SolidBrush(Th.A(Th.Sky, 50))) g.FillEllipse(b, px - r - 4, y + 8 - r - 4, (r + 4) * 2, (r + 4) * 2);
                    using (var b = new SolidBrush(c)) g.FillEllipse(b, px - r, y + 8 - r, r * 2, r * 2);
                    Th.T1(g, stages[i][0], Th.Label, new Rectangle(px - step / 2, y + 20, step, 14), st == "wait" ? Th.Muted : Th.Text2, TextFormatFlags.HorizontalCenter);
                }
                y += 44;
            }
            Th.T(g, stage, Th.BodyB, x, y, w, busy ? Th.Text : verdict != null ? LevelColor(verdict.Level) : Th.Text2);
            y += Th.H(stage, Th.BodyB, w) + 12;

            // ---- лента событий ----
            int bottom = Height - 16;
            int ly = bottom;
            for (int i = events.Count - 1; i >= 0 && ly > y + 16; i--)
            {
                double age = t - events[i].Key;
                int h = Th.H(events[i].Value, Th.Small, w - 16);
                ly -= h + 6;
                if (ly < y) break;
                int alpha = (int)(255 * Math.Max(0.28, 1 - (bottom - ly) / (double)Math.Max(80, bottom - y)));
                double fresh = Math.Max(0, 1 - age / 0.6);
                Color c = Th.Mix(Th.Mix(Th.Bg, Th.Text2, alpha / 255.0), Th.Accent, fresh * 0.7);
                using (var b = new SolidBrush(Th.Mix(Th.Bg, Th.Accent, 0.3 + fresh * 0.7))) g.FillEllipse(b, x + 1, ly + 6, 5, 5);
                Th.T(g, events[i].Value, Th.Small, x + 14, ly + (int)(fresh * 6), w - 16, c);
            }
        }

        static Color LevelColor(string level) { return level == "ok" ? Th.Accent : level == "partial" ? Th.Sky : level == "warn" ? Th.Warn : Th.Danger; }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            int dx = e.X - (btnRect.X + btnRect.Width / 2), dy = e.Y - (btnRect.Y + btnRect.Height / 2);
            hoverBtn = dx * dx + dy * dy <= btnRect.Width * btnRect.Width / 4;
            hoverDeep = deepRect.Contains(e.Location);
            Cursor = hoverBtn || (hoverDeep && !busy) ? Cursors.Hand : Cursors.Default;
        }
        protected override void OnMouseLeave(EventArgs e) { hoverBtn = hoverDeep = false; }
        protected override void OnMouseClick(MouseEventArgs e)
        {
            if (hoverBtn && CheckClick != null) CheckClick();
            else if (hoverDeep && !busy) deep = !deep;
        }
    }
}
