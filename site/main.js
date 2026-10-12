// OBD AI — сайт. Анимации на GSAP + ScrollTrigger, плавная прокрутка Lenis, фон — осциллограф CAN-шины.
(function () {
  "use strict";
  const doc = document.documentElement;
  doc.classList.add("js");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const fine = matchMedia("(hover: hover) and (pointer: fine)").matches;
  const hasGsap = !!(window.gsap && window.ScrollTrigger);
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

  if (hasGsap) { gsap.registerPlugin(ScrollTrigger); ScrollTrigger.config({ ignoreMobileResize: true }); }

  /* ============ Разбивка текста ============ */
  $$("[data-split]").forEach(el => {
    const t = el.textContent;
    el.textContent = "";
    for (const c of t) {
      const s = document.createElement("span");
      s.className = "ch";
      s.textContent = c === " " ? " " : c;
      el.appendChild(s);
    }
  });
  $$("[data-words]").forEach(el => {
    const parts = el.innerHTML.split(/(<br\s*\/?>)/i);
    el.innerHTML = parts.map(p => /^<br/i.test(p) ? p :
      p.split(/\s+/).filter(Boolean).map(w => `<span class="w"><span>${w}</span></span>`).join(" ")).join("");
  });

  /* ============ Плавная прокрутка ============ */
  let lenis = null;
  if (!reduce && window.Lenis) {
    lenis = new Lenis({ duration: 1.15, easing: t => 1 - Math.pow(1 - t, 4), smoothWheel: true });
    if (hasGsap) {
      lenis.on("scroll", ScrollTrigger.update);
      gsap.ticker.add(t => lenis.raf(t * 1000));
      gsap.ticker.lagSmoothing(0);
    } else {
      const raf = t => { lenis.raf(t); requestAnimationFrame(raf); };
      requestAnimationFrame(raf);
    }
  }
  function jump(target) {
    const el = typeof target === "string" ? $(target) : target;
    if (!el) return;
    if (lenis) lenis.scrollTo(el, { immediate: true, force: true });
    else el.scrollIntoView();
    if (hasGsap) ScrollTrigger.update();
  }

  /* ============ Фон: осциллограф шины (по MOTION.md) ============ */
  const Bus = (function () {
    const cv = $("#bus");
    if (!cv) return { burst() {}, emit() {} };
    const cx = cv.getContext("2d");
    let W = 0, H = 0, dpr = 1, lanes = [], packets = [], last = 0, nextSpawn = 0, speedK = 1, visible = true;
    let mx = -1, my = -1;
    const LIME = [184, 241, 90], MUTED = [134, 141, 153];
    function seedRand(s) { return () => (s = (s * 16807) % 2147483647) / 2147483647; }

    function layout() {
      dpr = Math.min(devicePixelRatio || 1, 2);
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr;
      const n = clamp(Math.round(H / 96), 5, 10);
      const r = seedRand(7);
      lanes = [];
      for (let i = 0; i < n; i++) lanes.push({ y: (i + .5) * H / n + (r() * 24 - 12), reveal: 0, delay: i * 40 });
    }
    function bits() {
      const n = 8 + Math.floor(Math.random() * 11);
      const out = []; let run = 0, prev = -1;
      for (let i = 0; i < n; i++) {
        let b = Math.random() < .5 ? 0 : 1;
        if (run >= 5 && b === prev) b = 1 - b;      // бит-стаффинг CAN: не больше пяти одинаковых
        run = b === prev ? run + 1 : 1; prev = b; out.push(b);
      }
      return out;
    }
    function spawn(lane, strong) {
      if (packets.length > 26 || !lanes.length) return;
      const li = lane == null ? Math.floor(Math.random() * lanes.length) : lane % lanes.length;
      packets.push({ lane: li, x: -20, bits: bits(), v: 72 * (.75 + Math.random() * .5), age: 0, a: strong ? .9 : .55 });
    }
    function burst(n) { for (let i = 0; i < n; i++) setTimeout(() => spawn(i, true), i * 40); }

    function frame(now) {
      requestAnimationFrame(frame);
      if (!visible) { last = now; return; }
      const dt = Math.min((now - (last || now)) / 1000, .05); last = now;
      if (now > nextSpawn) { spawn(); nextSpawn = now + (380 + Math.random() * 340) / speedK; }
      speedK += (1 - speedK) * dt * 1.5;
      cx.setTransform(dpr, 0, 0, dpr, 0, 0);
      cx.clearRect(0, 0, W, H);
      const bw = 5, hi = 9;
      for (const l of lanes) {
        l.reveal = clamp(l.reveal + dt * 1.4, 0, 1);
        const near = my >= 0 ? Math.max(0, 1 - Math.abs(my - l.y) / 90) : 0;
        cx.fillStyle = `rgba(184,241,90,${.05 + near * .12})`;
        cx.fillRect(0, l.y, W * (1 - Math.pow(1 - l.reveal, 3)), 1);
      }
      cx.lineWidth = 1.5; cx.lineCap = "butt";
      for (let i = packets.length - 1; i >= 0; i--) {
        const p = packets[i];
        p.age += dt; p.x += p.v * speedK * dt;
        const l = lanes[p.lane]; if (!l) { packets.splice(i, 1); continue; }
        const len = p.bits.length * bw;
        if (p.x - len > W + 8) { packets.splice(i, 1); continue; }
        let A = p.a * Math.min(1, p.age / .16) * clamp((W - p.x) / 56, 0, 1);
        if (mx >= 0 && Math.abs(my - l.y) < 70 && Math.abs(mx - p.x) < 160) A = Math.min(1, A * 1.8);
        const col = p.a > .3 ? LIME : MUTED;
        let x = p.x, prevY = null;
        for (let b = 0; b < p.bits.length; b++) {
          const y = p.bits[b] ? l.y - hi : l.y;
          const a = A * Math.pow(1 - b / p.bits.length, 1.6);
          cx.strokeStyle = `rgba(${col[0]},${col[1]},${col[2]},${a})`;
          cx.beginPath();
          if (prevY !== null && prevY !== y) { cx.moveTo(x, prevY); cx.lineTo(x, y); }
          cx.moveTo(x, y); cx.lineTo(x - bw, y); cx.stroke();
          prevY = y; x -= bw;
        }
        const hy = p.bits[0] ? l.y - hi : l.y;
        cx.fillStyle = `rgba(184,241,90,${.1 * A})`; cx.beginPath(); cx.arc(p.x, hy, 7, 0, 7); cx.fill();
        cx.fillStyle = `rgba(184,241,90,${.3 * A})`; cx.beginPath(); cx.arc(p.x, hy, 3.5, 0, 7); cx.fill();
        cx.fillStyle = `rgba(226,255,180,${A})`; cx.beginPath(); cx.arc(p.x, hy, 1.5, 0, 7); cx.fill();
      }
    }
    layout();
    addEventListener("resize", layout);
    const hero = $("#hero");
    hero.addEventListener("pointermove", e => { const r = cv.getBoundingClientRect(); mx = e.clientX - r.left; my = e.clientY - r.top; });
    hero.addEventListener("pointerleave", () => { mx = my = -1; });
    hero.addEventListener("pointerdown", e => {
      if (e.target.closest("a,button")) return;
      const r = cv.getBoundingClientRect(); const y = e.clientY - r.top;
      let best = 0; lanes.forEach((l, i) => { if (Math.abs(l.y - y) < Math.abs(lanes[best].y - y)) best = i; });
      for (let k = 0; k < 4; k++) setTimeout(() => spawn(best, true), k * 70);
      speedK = 2.4;
    });
    new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(hero);
    if (!reduce) requestAnimationFrame(frame);
    return { burst, emit: () => spawn(null, true), kick: k => { speedK = k; } };
  })();

  /* ============ Загрузка: адаптер просыпается ============ */
  function boot() {
    const el = $("#boot");
    const seen = (() => { try { return sessionStorage.getItem("obdai_boot"); } catch (e) { return "1"; } })();
    if (reduce || !hasGsap || seen) { el.remove(); document.body.classList.remove("is-booting"); return Promise.resolve(false); }
    try { sessionStorage.setItem("obdai_boot", "1"); } catch (e) { /* без хранилища — просто каждый раз */ }
    const lines = [["> ATZ", ""], ["ELM327 v1.5", "ok"], ["> ATSP0", ""], ["OK", "ok"], ["> 0100", ""], ["41 00 BE 3F A8 13", "ok"], ["ШИНА ЖИВА", "ok"]];
    const box = $(".boot-lines");
    return new Promise(res => {
      const tl = gsap.timeline({ onComplete: () => { el.remove(); document.body.classList.remove("is-booting"); res(true); } });
      lines.forEach(([t, c], i) => {
        tl.call(() => { const p = document.createElement("p"); p.className = c; p.textContent = t; box.appendChild(p); Bus.emit(); }, null, i * .17);
      });
      tl.to(".boot-bar i", { scaleX: 1, duration: 1.15, ease: "power2.inOut" }, 0);
      tl.to(".boot-term, .boot-bar", { opacity: 0, duration: .18 }, 1.3);
      tl.set(".boot-scan", { opacity: 1 }, 1.32);
      tl.to(".boot-scan", { scaleY: 3, duration: .08, yoyo: true, repeat: 1 }, 1.32);
      tl.to(".boot-top", { yPercent: -100, duration: .9, ease: "expo.inOut" }, 1.45);
      tl.to(".boot-bot", { yPercent: 100, duration: .9, ease: "expo.inOut" }, 1.45);
      tl.to(".boot-scan", { opacity: 0, duration: .5 }, 1.6);
      el.addEventListener("click", () => tl.progress(1));
    });
  }

  /* ============ Герой ============ */
  function heroIn(delay) {
    if (!hasGsap || reduce) return;
    // меню въезжает CSS-анимацией: твин GSAP мог застыть на середине (фоновая вкладка), и меню оставалось почти прозрачным
    const nav = $(".nav");
    nav.style.animationDelay = (delay + .3) + "s";
    nav.classList.add("is-entering");
    nav.addEventListener("animationend", () => nav.classList.remove("is-entering"), { once: true });
    const tl = gsap.timeline({ delay });
    tl.from(".hero-title .ch", { yPercent: 115, rotateX: -90, opacity: 0, duration: 1.1, ease: "expo.out", stagger: .028, transformOrigin: "50% 100%" })
      .from(".hero .eyebrow", { opacity: 0, y: 20, duration: .6 }, .1)
      .from(".hero-lead", { opacity: 0, y: 30, duration: .9, ease: "power3.out" }, .45)
      .from(".hero-cta > *", { opacity: 0, y: 30, duration: .8, stagger: .08, ease: "power3.out" }, .55)
      .from("#heroPhone", { opacity: 0, y: 140, rotateX: 40, rotateY: -30, scale: .8, duration: 1.6, ease: "expo.out" }, .2)
      .from(".hero .chip", { opacity: 0, scale: .6, y: 30, duration: .9, stagger: .12, ease: "back.out(2)" }, .9)
      .from(".scroll-hint", { opacity: 0, duration: .8 }, 1.2)
      .call(() => Bus.burst(8), null, .2);
  }

  // буквы «наливаются» весом рядом с курсором — Unbounded переменной толщины
  if (fine && !reduce) {
    const chars = $$(".hero-title .ch");
    let raf = 0, px = 0, py = 0;
    addEventListener("pointermove", e => {
      px = e.clientX; py = e.clientY;
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        for (const c of chars) {
          const r = c.getBoundingClientRect();
          const d = Math.hypot(px - (r.left + r.width / 2), py - (r.top + r.height / 2));
          c.style.setProperty("--w", Math.round(800 - 600 * Math.max(0, 1 - d / 260)));
        }
      });
    });
  }

  // телефон наклоняется за курсором, плашки плавают
  if (fine && !reduce && hasGsap) {
    const ph = $("#heroPhone");
    const qx = gsap.quickTo(ph, "rotationY", { duration: .8, ease: "power3" });
    const qy = gsap.quickTo(ph, "rotationX", { duration: .8, ease: "power3" });
    gsap.set(ph, { rotationY: -14, rotationX: 6 });
    const scr = $("#heroPhone .hero-screen");
    $("#hero").addEventListener("pointermove", e => {
      const nx = e.clientX / innerWidth - .5, ny = e.clientY / innerHeight - .5;
      qx(-14 + nx * 26); qy(6 - ny * 18);
      scr.style.setProperty("--gx", (nx * -60).toFixed(1) + "%");
    });
    $$(".hero .chip").forEach((c, i) => gsap.to(c, { y: i % 2 ? 14 : -14, duration: 2.6 + i * .4, repeat: -1, yoyo: true, ease: "sine.inOut" }));
  } else if (!reduce && hasGsap) {
    gsap.set("#heroPhone", { rotationY: -10, rotationX: 4 });
    gsap.to("#heroPhone", { rotationY: 8, rotationX: -2, y: -10, duration: 5, repeat: -1, yoyo: true, ease: "sine.inOut" });
    gsap.to("#heroPhone .hero-screen", { "--gx": "40%", duration: 5, repeat: -1, yoyo: true, ease: "sine.inOut", startAt: { "--gx": "-40%" } });
    $$(".hero .chip").forEach((c, i) => gsap.to(c, { y: i % 2 ? 10 : -10, duration: 2.6 + i * .4, repeat: -1, yoyo: true, ease: "sine.inOut" }));
  }

  /* ============ Курсор и «магнитные» кнопки ============ */
  if (fine && !reduce && hasGsap) {
    document.body.classList.add("has-cursor");
    const cur = $(".cursor"), dot = $(".cursor-dot"), ring = $(".cursor-ring"), lbl = $(".cursor-ring em");
    const dx = gsap.quickTo(dot, "x", { duration: .08 }), dy = gsap.quickTo(dot, "y", { duration: .08 });
    const rx = gsap.quickTo(ring, "x", { duration: .45, ease: "power3" }), ry = gsap.quickTo(ring, "y", { duration: .45, ease: "power3" });
    addEventListener("pointermove", e => { dx(e.clientX); dy(e.clientY); rx(e.clientX); ry(e.clientY); });
    document.addEventListener("pointerover", e => {
      const t = e.target;
      cur.classList.toggle("is-text", !!t.closest("input"));
      const lab = t.closest("[data-cursor]");
      cur.classList.toggle("is-label", !!lab); if (lab) lbl.textContent = lab.dataset.cursor;
      cur.classList.toggle("is-hover", !lab && !!t.closest("a,button,.card"));
    });
    $$(".magnetic").forEach(b => {
      const mx = gsap.quickTo(b, "x", { duration: .6, ease: "elastic.out(1,.4)" });
      const my = gsap.quickTo(b, "y", { duration: .6, ease: "elastic.out(1,.4)" });
      b.addEventListener("pointermove", e => { const r = b.getBoundingClientRect(); mx((e.clientX - r.left - r.width / 2) * .3); my((e.clientY - r.top - r.height / 2) * .4); });
      b.addEventListener("pointerleave", () => { mx(0); my(0); });
    });
    $$(".card").forEach(c => c.dataset.cursor = "тяни");
  }

  /* ============ Переход по меню: шторка с лучом ============ */
  let wiping = false;
  function wipeTo(sel) {
    if (reduce || !hasGsap) { jump(sel); return; }
    if (wiping) return; wiping = true;
    const bars = $$("#wipe i"), beam = $("#wipe b");
    gsap.timeline({ onComplete: () => { wiping = false; } })
      .set(bars, { transformOrigin: "top" })
      .to(bars, { scaleY: 1, duration: .5, ease: "expo.in", stagger: { each: .04, from: "start" } })
      .set(beam, { opacity: 1, top: 0 }, .25)
      .to(beam, { top: "100%", duration: .5, ease: "power2.in" }, .25)
      .call(() => { jump(sel); Bus.burst(5); })
      .set(bars, { transformOrigin: "bottom" })
      .to(bars, { scaleY: 0, duration: .65, ease: "expo.out", stagger: { each: .04, from: "end" } })
      .to(beam, { opacity: 0, duration: .3 }, "<");
  }
  $$("[data-go]").forEach(a => a.addEventListener("click", e => {
    const h = a.getAttribute("href"); if (!h || h[0] !== "#") return;
    e.preventDefault(); wipeTo(h);
  }));

  // меню прячется при прокрутке вниз и подсвечивает текущий раздел
  (function () {
    const nav = $(".nav"); let lastY = 0;
    const onScroll = y => { nav.classList.toggle("is-hidden", y > lastY && y > 400); lastY = y; };
    if (lenis) lenis.on("scroll", e => onScroll(e.scroll)); else addEventListener("scroll", () => onScroll(scrollY), { passive: true });
    const links = $$(".nav-links a");
    const io = new IntersectionObserver(es => es.forEach(en => {
      if (en.isIntersecting) links.forEach(l => l.classList.toggle("is-on", l.getAttribute("href") === "#" + en.target.id));
    }), { rootMargin: "-45% 0px -50% 0px" });
    $$("main > section").forEach(s => io.observe(s));
  })();

  /* ============ Бегущая строка: скорость и наклон от прокрутки ============ */
  (function () {
    const tr = $(".marquee-track"); if (!tr) return;
    tr.innerHTML += tr.innerHTML;
    let x = 0, vel = 0, dir = -1, skew = 0;
    if (lenis) lenis.on("scroll", e => { vel = e.velocity; if (Math.abs(vel) > .5) dir = vel > 0 ? -1 : 1; });
    (function loop() {
      requestAnimationFrame(loop);
      if (reduce) return;
      const half = tr.scrollWidth / 2;
      x += dir * (1.1 + Math.min(Math.abs(vel) * .5, 24));
      if (x <= -half) x += half; if (x > 0) x -= half;
      skew += (clamp(-vel * .35, -14, 14) - skew) * .12;
      tr.style.transform = `translate3d(${x}px,0,0) skewX(${skew}deg)`;
      vel *= .92;
    })();
  })();

  /* ============ Живой телефон в герое: проверка идёт сама по кругу ============ */
  function heroPhone() {
    const scr = $(".hero-screen"); if (!scr) return;
    const clock = $(".clock", scr);
    const tick = () => { clock.textContent = new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }); };
    tick(); setInterval(tick, 20000);
    // напряжение АКБ «дышит», при проверке чуть проседает
    const volt = $(".volt", scr), line = $(".spark polyline", scr), vals = Array(24).fill(12.6);
    const draw = () => { line.setAttribute("points", vals.map((v, i) => (i * 60 / 23).toFixed(1) + "," + (15 - (v - 12.2) / .55 * 13).toFixed(1)).join(" ")); };
    draw();
    if (reduce || !hasGsap) return;
    setInterval(() => {
      const busy = scr.dataset.s === "scan";
      const v = (busy ? 12.38 : 12.56) + Math.random() * .08;
      vals.push(v); vals.shift(); draw();
      volt.textContent = v.toFixed(2) + " В";
    }, 650);
    scr.classList.add("js-phone");
    const pct = $(".cb-pct", scr), stage = $(".ui-stage", scr), prog = $(".arc-prog", scr), btn = $(".ui-check-btn", scr);
    const mods = ["двигатель 7E0", "коробка 7E1", "ABS 7D1", "подушки 7D2", "ЭУР 7D4", "приборка 7C6", "климат 7B3"];
    const o = { p: 0 };
    const setP = () => { prog.style.strokeDashoffset = 100 - o.p; };
    const tl = gsap.timeline({ repeat: -1, paused: true });
    tl.call(() => { scr.dataset.s = "home"; stage.textContent = "Готов к проверке"; })
      .to({}, { duration: 2.2 })
      .to(btn, { scale: .88, duration: .14, ease: "power2.in" })
      .call(() => { scr.dataset.s = "scan"; Bus.burst(4); })
      .to(btn, { scale: 1, duration: .7, ease: "elastic.out(1,.4)" })
      .fromTo(o, { p: 0 }, { p: 100, duration: 4.4, ease: "power1.inOut", onUpdate: () => {
        setP(); pct.textContent = Math.round(o.p) + "%";
        stage.textContent = "Опрашиваю: " + mods[Math.min(mods.length - 1, Math.floor(o.p / 100 * mods.length))];
      } }, "<")
      .call(() => { scr.dataset.s = "verdict"; stage.textContent = "3 кода · 10 блоков ответили"; })
      .to({}, { duration: 1.3 })
      .call(() => scr.classList.add("has-notif"))
      .to({}, { duration: 2.8 })
      .call(() => scr.classList.remove("has-notif"))
      .to({}, { duration: 1.4 })
      .call(() => { scr.dataset.s = "home"; })
      .to(o, { p: 0, duration: .9, ease: "power2.inOut", onUpdate: setP });
    new IntersectionObserver(es => { es[0].isIntersecting ? tl.play() : tl.pause(); }).observe(scr);
  }

  /* ============ Обиди: смотрит, моргает, говорит ============ */
  const Obidi = (function () {
    const m = $("#mascot"), bub = $("#bubble"), txt = bub && $(".bubble-txt", bub);
    if (!m) return { say() {}, mood() {}, jump() {}, shake() {} };
    const eyes = $(".m-eyes", m);
    let typing = 0, held = false;
    function say(t) {
      if (!txt || txt.dataset.t === t) return;
      txt.dataset.t = t;
      clearInterval(typing); bub.classList.remove("done");
      if (reduce) { txt.textContent = t; bub.classList.add("done"); return; }
      let i = 0; txt.textContent = "";
      typing = setInterval(() => { txt.textContent = t.slice(0, ++i); if (i >= t.length) { clearInterval(typing); bub.classList.add("done"); } }, 24);
      if (hasGsap) gsap.fromTo(bub, { scale: .86, opacity: .3 }, { scale: 1, opacity: 1, duration: .55, ease: "back.out(2)", transformOrigin: "10% 100%" });
    }
    function mood(k) { m.classList.remove("is-happy", "is-sad", "is-busy"); if (k) m.classList.add("is-" + k); }
    function lookAt(x, y) {
      const r = m.getBoundingClientRect(); if (!r.width) return;
      const dx = clamp((x - (r.left + r.width / 2)) / 40, -9, 9), dy = clamp((y - (r.top + r.height * .45)) / 50, -6, 6);
      eyes.style.transform = `translate(${dx}px,${dy}px)`;
    }
    function blink() { m.classList.add("is-blink"); setTimeout(() => m.classList.remove("is-blink"), 140); }
    function jump() { if (hasGsap && !reduce) gsap.fromTo(m, { y: 0 }, { y: -34, duration: .28, ease: "power2.out", yoyo: true, repeat: 1 }); }
    function shake() { if (hasGsap && !reduce) gsap.fromTo(m, { rotation: -7 }, { rotation: 0, duration: .9, ease: "elastic.out(1,.3)", transformOrigin: "50% 90%" }); }
    if (fine) addEventListener("pointermove", e => { if (!held) lookAt(e.clientX, e.clientY); });
    else if (!reduce) setInterval(() => {
      if (held) return;
      const r = m.getBoundingClientRect();
      lookAt(r.left + r.width / 2 + (Math.random() - .5) * 500, r.top + r.height / 2 + (Math.random() - .5) * 260);
    }, 2300);
    // смотрит на поле, в которое пишут, и моргает при наборе
    document.addEventListener("focusin", e => {
      if (!e.target.closest || !e.target.closest("#reqForm")) return;
      held = true; const r = e.target.getBoundingClientRect(); lookAt(r.left + r.width / 2, r.top + r.height / 2);
    });
    document.addEventListener("focusout", e => { if (e.target.closest && e.target.closest("#reqForm")) held = false; });
    document.addEventListener("input", e => { if (e.target.closest && e.target.closest("#reqForm") && Math.random() < .3) blink(); });
    return { say, mood, jump, shake };
  })();

  /* ============ Новости автомира ============ */
  function setupNews() {
    const grid = $("#newsGrid"), box = $("#newsSrc"); if (!grid) return;
    let src = "", started = false, chips = false, timer = 0;
    const esc = s => String(s || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    const ago = t => {
      const d = Date.now() / 1000 - t;
      if (!t) return "";
      if (d < 3600) return Math.max(1, Math.round(d / 60)) + " мин назад";
      if (d < 86400) return Math.round(d / 3600) + " ч назад";
      return new Date(t * 1000).toLocaleDateString("ru-RU", { day: "numeric", month: "long" });
    };
    const card = (x, i) => `<article class="nc${i === 0 ? " nc-big" : ""}"><a href="${esc(x.link)}" target="_blank" rel="noopener noreferrer">
      <div class="img"><span class="noimg">${esc(x.sourceName)}</span>${x.image ? `<img src="${esc(x.image)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onload="this.classList.add('ok')" onerror="this.remove()">` : ""}</div>
      <span class="go" aria-hidden="true">↗</span>
      <div class="body"><div class="meta"><b>${esc(x.sourceName)}</b><span>${ago(x.time)}</span></div>
      <h3><span>${esc(x.title)}</span></h3><p>${esc(x.lead)}</p></div></a></article>`;
    // лента «Все»: по очереди из каждого издания, свежее первым — одно издание не забивает остальные
    const mix = (items, n) => {
      const q = {};
      items.forEach(x => (q[x.source] = q[x.source] || []).push(x));
      const order = Object.keys(q).sort((a, b) => q[b][0].time - q[a][0].time), out = [];
      while (out.length < n && order.some(k => q[k].length)) order.forEach(k => { if (q[k].length && out.length < n) out.push(q[k].shift()); });
      return out;
    };
    // источник 1 — снимок news.json рядом с сайтом (собирает GitHub раз в 30 минут), источник 2 — сервер на ПК
    async function fetchNews() {
      try {
        const r = await fetch("news.json?t=" + Math.floor(Date.now() / 300000), { cache: "no-store" });
        if (r.ok) { const d = await r.json(); if (d.items && d.items.length) return d; }
      } catch (e) { /* снимка нет — спросим сервер */ }
      const r = await ObdApi.json("/api/news?limit=60");
      if (r.status !== 200 || !r.data.items || !r.data.items.length) throw new Error("empty");
      return r.data;
    }
    let all = null;
    async function load(animate) {
      clearTimeout(timer);
      let ok = false;
      try {
        if (!all || !animate) all = await fetchNews();
        const items = src ? all.items.filter(x => x.source === src).slice(0, 9) : mix(all.items, 9);
        if (!items.length) throw new Error("empty");
        if (!chips && all.sources) {
          chips = true;
          const have = new Set(all.items.map(x => x.source));
          box.innerHTML += all.sources.filter(s => have.has(s.id)).map(s => `<button class="src-chip" data-src="${esc(s.id)}" type="button">${esc(s.name)}</button>`).join("");
        }
        grid.innerHTML = items.map(card).join("");
        if (animate && hasGsap && !reduce) gsap.from(grid.children, { y: 50, opacity: 0, duration: .9, ease: "expo.out", stagger: .06 });
        ok = true;
      } catch (e) {
        if (!grid.querySelector(".nc:not(.sk)")) grid.innerHTML = `<div class="news-empty">Загружаю ленту новостей…</div>`;
      }
      if (hasGsap) ScrollTrigger.refresh();
      // не вышло — пробуем снова через 30 с (туннель переезжает за минуту), вышло — обновляем раз в 10 минут
      timer = setTimeout(() => { all = null; load(false); }, ok ? 10 * 60 * 1000 : 30000);
    }
    box.addEventListener("click", async e => {
      const b = e.target.closest(".src-chip"); if (!b || b.classList.contains("on")) return;
      box.querySelectorAll(".src-chip").forEach(c => c.classList.toggle("on", c === b));
      src = b.dataset.src;
      if (hasGsap && !reduce) await gsap.to(grid.children, { y: -20, opacity: 0, duration: .3, stagger: .02, ease: "power2.in" });
      load(true);
    });
    new IntersectionObserver((es, io) => {
      if (es[0].isIntersecting && !started) { started = true; io.disconnect(); load(true); }
    }, { rootMargin: "800px 0px" }).observe(grid);
  }

  /* ============ Телефон в «Как работает» вписывается в экран смартфона ============ */
  function fitHowPhone() {
    const ph = $(".phone-how"), box = $(".how-right"); if (!ph || !box) return;
    if (innerWidth > 760) { ph.style.zoom = ""; return; }
    const natural = 320 * 19.3 / 9 + 20;
    const z = clamp(Math.min(box.clientHeight / natural, (innerWidth - 40) / 320), .3, 1);
    ph.style.zoom = z.toFixed(3);
  }

  heroPhone();
  setupNews();
  if (!hasGsap || reduce) { $$("[data-reveal]").forEach(e => e.style.opacity = 1); setupGate(); boot(); return; }

  /* ============ Заголовки разделов: слова выезжают из-под маски ============ */
  $$(".h2[data-words]").forEach(h => {
    if (h.closest(".hero")) return;
    gsap.from(h.querySelectorAll(".w > span"), {
      yPercent: 110, rotate: 6, duration: 1.1, ease: "expo.out", stagger: .06,
      scrollTrigger: { trigger: h, start: "top 85%" }
    });
  });
  $$(".eyebrow").forEach(e => {
    if (e.closest(".hero")) return;
    gsap.from(e, { opacity: 0, x: -30, duration: .8, ease: "power3.out", scrollTrigger: { trigger: e, start: "top 90%" } });
  });

  // каждый раздел входит с «жёстким срезом»: верхняя кромка проезжает лучом
  $$("main > section:not(.hero):not(.honest)").forEach(s => {
    const beam = document.createElement("div");
    beam.style.cssText = "position:absolute;left:0;right:0;top:0;height:2px;z-index:5;pointer-events:none;background:linear-gradient(90deg,transparent,#b8f15a 40%,#eaffc4 50%,#b8f15a 60%,transparent);box-shadow:0 0 22px rgba(184,241,90,.6);transform:scaleX(0)";
    s.prepend(beam);
    gsap.timeline({ scrollTrigger: { trigger: s, start: "top 92%" } })
      .to(beam, { scaleX: 1, duration: .7, ease: "expo.out" })
      .to(beam, { opacity: 0, duration: 1.2 }, "+=.2");
  });

  // уход героя: заголовок разъезжается, телефон уплывает
  gsap.timeline({ scrollTrigger: { trigger: "#hero", start: "top top", end: "bottom top", scrub: true } })
    .to(".hero-title .line:nth-child(1)", { xPercent: -18 }, 0)
    .to(".hero-title .line:nth-child(2)", { xPercent: 12 }, 0)
    .to(".hero-title .line:nth-child(3)", { xPercent: -8 }, 0)
    .to(".hero-copy", { opacity: 0, filter: "blur(10px)" }, 0)
    .to(".hero-stage", { yPercent: -25, scale: .9, opacity: 0 }, 0);

  const mm = gsap.matchMedia();

  /* ============ Как работает: закреплённый телефон, три экрана (на всех ширинах) ============ */
  mm.add("all", () => {
    fitHowPhone();
    ScrollTrigger.addEventListener("refreshInit", fitHowPhone);
    const steps = $$(".step"), scrs = $$(".scr");
    let cur = 0;
    function show(i) {
      if (i === cur) return;
      const dirDown = i > cur;
      const out = steps[cur], inn = steps[i];
      gsap.to(out, { yPercent: dirDown ? -40 : 40, opacity: 0, duration: .45, ease: "power3.in", onComplete: () => out.classList.remove("is-on") });
      inn.classList.add("is-on");
      gsap.fromTo(inn, { yPercent: dirDown ? 40 : -40, opacity: 0 }, { yPercent: 0, opacity: 1, duration: .7, ease: "expo.out", delay: .2 });
      scrs.forEach((s, k) => s.style.zIndex = k === i ? 2 : 1);
      gsap.fromTo(scrs[i], { clipPath: "circle(0% at 50% 62%)" }, { clipPath: "circle(150% at 50% 62%)", duration: .9, ease: "expo.inOut",
        onComplete: () => scrs.forEach((s, k) => { if (k !== i) gsap.set(s, { clipPath: "circle(0% at 50% 62%)" }); }) });
      gsap.fromTo(".phone-how", { rotationY: dirDown ? 8 : -8 }, { rotationY: 0, duration: 1, ease: "expo.out" });
      cur = i;
      Bus.burst(3);
    }
    const st = ScrollTrigger.create({
      trigger: ".how", start: "top top", end: "+=220%", pin: ".how-pin", scrub: true,
      onUpdate: self => {
        const p = self.progress;
        gsap.set(".rail-fill", { scaleX: p });
        gsap.set(".rail-dot", { left: (p * 100) + "%" });
        show(p < .34 ? 0 : p < .67 ? 1 : 2);
      }
    });
    gsap.from(".phone-how", { y: 120, opacity: 0, rotationX: 30, duration: 1.4, ease: "expo.out", scrollTrigger: { trigger: ".how", start: "top 70%" } });
    return () => { st.kill(); ScrollTrigger.removeEventListener("refreshInit", fitHowPhone); };
  });

  /* ============ Возможности: горизонтальная лента (и на телефоне тоже) ============ */
  mm.add("all", () => {
    const track = $(".track"), now = $(".fc-now"), cards = $$(".track .card");
    const dist = () => track.scrollWidth - innerWidth;
    const tween = gsap.to(track, {
      x: () => -dist(), ease: "none",
      scrollTrigger: {
        trigger: ".features", start: "top top", end: () => "+=" + dist(), pin: ".features-pin", scrub: 1, invalidateOnRefresh: true,
        snap: { snapTo: 1 / (cards.length - 1), duration: { min: .25, max: .7 }, delay: .08, ease: "power2.inOut" },
        onUpdate: self => { now.textContent = String(clamp(Math.round(self.progress * 7) + 1, 1, 8)).padStart(2, "0"); }
      }
    });
    // карточки «въезжают» с наклоном, пока лента едет
    cards.forEach(c => {
      gsap.fromTo(c, { rotationY: -24, z: -160, opacity: .25 }, {
        rotationY: 0, z: 0, opacity: 1, ease: "power2.out",
        scrollTrigger: { trigger: c, containerAnimation: tween, start: "left 100%", end: "left 55%", scrub: true,
          onEnter: () => c.classList.add("in") }
      });
    });
    return () => tween.scrollTrigger && tween.scrollTrigger.kill();
  });

  // живые мини-картинки на карточках
  (function () {
    const mods = $$(".viz-mods span"); let k = 0;
    setInterval(() => { mods.forEach((m, i) => m.classList.toggle("lit", i <= k)); k = (k + 1) % (mods.length + 3); }, 420);
    const buy = $$(".viz-buy span"); let b = 1;
    setInterval(() => { b = (b + 1) % 3; buy.forEach((s, i) => s.classList.toggle("on", i === b)); }, 1800);
    $$(".viz-fuel i").forEach(i => gsap.from(i, { scaleY: 0, duration: 1.4, ease: "expo.out", scrollTrigger: { trigger: ".viz-fuel", start: "top 95%" } }));
  })();

  /* ============ Цифры: одометр ============ */
  $$(".odo").forEach(el => {
    const to = el.dataset.to;
    const str = Number(to).toLocaleString("ru-RU").replace(/\s/g, " ");
    el.textContent = "";
    const cols = [];
    for (const ch of str) {
      if (ch === " ") { const s = document.createElement("span"); s.className = "sp"; el.appendChild(s); continue; }
      const col = document.createElement("span"); col.className = "dg";
      const target = +ch, spins = 2 + cols.length;
      for (let r = 0; r <= spins * 10 + target; r++) { const d = document.createElement("span"); d.textContent = r % 10; col.appendChild(d); }
      el.appendChild(col); cols.push({ col, steps: spins * 10 + target });
    }
    ScrollTrigger.create({
      trigger: el, start: "top 90%", once: true,
      onEnter: () => cols.forEach((c, i) => gsap.to(c.col, { y: () => -c.steps + "em", duration: 1.8 + i * .25, ease: "expo.out", delay: i * .05 }))
    });
  });

  /* ============ Честно: экран заливается лаймом, ложь зачёркивается ============ */
  gsap.timeline({ scrollTrigger: { trigger: ".honest", start: "top 85%", end: "top 10%", scrub: .6 } })
    .fromTo(".honest-fill", { clipPath: "circle(0% at 50% 60%)" }, { clipPath: "circle(110% at 50% 60%)", ease: "power2.in" });
  gsap.timeline({ scrollTrigger: { trigger: ".strike", start: "top 75%", end: "bottom 45%", scrub: .5 } })
    .to(".strike span", { "--s": "100%", stagger: .35, ease: "power2.inOut" })
    .to(".strike li", { color: "rgba(10,12,8,.28)", stagger: .35 }, "<.2");
  gsap.from(".honest-cols p", { y: 50, opacity: 0, duration: 1, ease: "expo.out", stagger: .12, scrollTrigger: { trigger: ".honest-cols", start: "top 85%" } });

  /* ============ Доступ ============ */
  gsap.from(".gate", { y: 100, opacity: 0, rotationX: 18, duration: 1.4, ease: "expo.out", scrollTrigger: { trigger: ".access", start: "top 70%" } });
  gsap.from(".mascot", { y: 80, opacity: 0, scale: .7, duration: 1.6, ease: "elastic.out(1,.55)", scrollTrigger: { trigger: ".access-grid", start: "top 75%" } });
  gsap.from(".bubble", { scale: .4, opacity: 0, duration: .8, ease: "back.out(2.2)", delay: .5, transformOrigin: "10% 100%", scrollTrigger: { trigger: ".access-grid", start: "top 75%" } });

  /* ============ Подвал: буквы поднимаются ============ */
  gsap.from(".wordmark span", { yPercent: 100, duration: 1.2, ease: "expo.out", stagger: .06, scrollTrigger: { trigger: ".foot", start: "top 90%" } });

  setupGate();
  boot().then(played => heroIn(played ? 0 : .1));

  /* ============ Заявка на APK ============ */
  function setupGate() {
    const gate = $("#gate"), form = $("#reqForm"), err = $(".gate-err");
    const KEY = "obdai_ticket";
    const get = () => { try { return localStorage.getItem(KEY) || ""; } catch (e) { return ""; } };
    const set = v => { try { v ? localStorage.setItem(KEY, v) : localStorage.removeItem(KEY); } catch (e) { /* без хранилища заявка живёт до закрытия вкладки */ } };
    let ticket = "", timer = 0;

    // ссылка от администратора: …/#get=<заявка>
    const m = location.hash.match(/get=([\w-]{8,64})/);
    if (m) { set(m[1]); history.replaceState(null, "", location.pathname + location.search + "#access"); }
    ticket = get();

    function state(s) {
      if (gate.dataset.state === s) return;
      const swap = () => { gate.dataset.state = s; };
      if (document.startViewTransition && !reduce && !document.hidden) {
        const vt = document.startViewTransition(swap);
        vt.ready.catch(() => {}); vt.finished.catch(() => {});
      } else swap();
      const talk = {
        form: ["Привет! Я Обиди. Оставь заявку — передам админу лично.", ""],
        wait: ["Заявка у админа. Как ответит — здесь появится кнопка. Страницу можно закрыть.", "busy"],
        ok: ["Доступ открыт! Жми «Скачать» — ссылка одноразовая.", "happy"],
        used: ["Готово! Ставь приложение — увидимся в машине.", "happy"],
        no: ["В этот раз не вышло. Если это ошибка — напиши админу.", "sad"],
        off: ["Сервер заявок прилёг. Я проверю ещё раз сам.", "sad"]
      }[s];
      if (talk) { Obidi.say(talk[0]); Obidi.mood(talk[1]); }
      if (s === "ok" || s === "used") Obidi.jump();
      if (s === "ok" || s === "wait") Bus.burst(4);
    }
    const mb = n => (n / 1048576).toLocaleString("ru-RU", { maximumFractionDigits: 1 }) + " МБ";

    async function poll() {
      clearTimeout(timer);
      if (!ticket) {
        try { const r = await ObdApi.json("/api/apk/info"); state(r.status === 200 && r.data.open ? "form" : "off"); }
        catch (e) { state("off"); timer = setTimeout(poll, 30000); }
        return;
      }
      $(".tk").textContent = ticket.slice(0, 6).toUpperCase();
      try {
        const r = await ObdApi.json("/api/apk/status?ticket=" + encodeURIComponent(ticket));
        if (r.status === 404) { set(""); ticket = ""; return poll(); }
        const st = r.data.status;
        if (st === "pending") { state("wait"); timer = setTimeout(poll, 12000); }
        else if (st === "approved") {
          const base = await ObdApi.resolve();
          const a = $("#dlBtn");
          a.href = base + "/api/apk/download?ticket=" + encodeURIComponent(ticket);
          $(".dl-size").textContent = [r.data.version && "версия " + r.data.version, r.data.size && mb(r.data.size)].filter(Boolean).join(" · ");
          state("ok");
        }
        else if (st === "used") state("used");
        else if (st === "rejected") state("no");
      } catch (e) { state("off"); timer = setTimeout(poll, 20000); }
    }

    // Скачивание: сервер заявок отдаёт только пароль этой сборки (несколько байт через туннель), а сам APK —
    // зашифрованный файл dl/app-<run>.bin на этом же сайте (GitHub Pages, быстро). Расшифровка в браузере (WebCrypto),
    // формат openssl enc: «Salted__» + соль + AES-256-CBC, ключ и IV из PBKDF2-SHA256.
    async function decryptOpenssl(buf, pass, iter) {
      const data = new Uint8Array(buf);
      if (new TextDecoder().decode(data.slice(0, 8)) !== "Salted__") throw new Error("формат");
      const salt = data.slice(8, 16);
      const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(pass), "PBKDF2", false, ["deriveBits"]);
      const bits = new Uint8Array(await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: iter, hash: "SHA-256" }, base, 384));
      const key = await crypto.subtle.importKey("raw", bits.slice(0, 32), "AES-CBC", false, ["decrypt"]);
      return crypto.subtle.decrypt({ name: "AES-CBC", iv: bits.slice(32, 48) }, key, data.slice(16));
    }
    const hex = b => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, "0")).join("");
    let downloading = false;
    $("#dlBtn").addEventListener("click", async e => {
      e.preventDefault();
      if (downloading) return;
      downloading = true;
      const a = $("#dlBtn"), label = a.querySelector("span"), size = $(".dl-size");
      label.textContent = "Скачиваю…";
      try {
        const k = await ObdApi.json("/api/apk/key?ticket=" + encodeURIComponent(ticket));
        if (k.status === 410) { state("used"); return; }
        if (k.status !== 200 || !k.data.pass) throw new Error("key " + k.status);
        const r = await fetch(k.data.file, { cache: "no-store" });
        if (!r.ok || !r.body) throw new Error("file " + r.status);
        const total = +r.headers.get("Content-Length") || 0, reader = r.body.getReader(), parts = [];
        let got = 0;
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          parts.push(value); got += value.length;
          if (total) size.textContent = Math.round(got / total * 100) + "% · " + mb(got) + " из " + mb(total);
        }
        size.textContent = "Распаковываю…";
        const plain = await decryptOpenssl(await new Blob(parts).arrayBuffer(), k.data.pass, k.data.iter || 100000);
        if (k.data.sha256 && hex(await crypto.subtle.digest("SHA-256", plain)) !== k.data.sha256) throw new Error("sha");
        const url = URL.createObjectURL(new Blob([plain], { type: "application/vnd.android.package-archive" }));
        const link = document.createElement("a");
        link.href = url; link.download = "OBD-AI.apk"; document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
        state("used");
      } catch (x) {
        // пароль уже выдан, но 30 минут его можно спросить снова — просто нажать ещё раз
        label.textContent = "Скачать APK";
        size.textContent = "Не получилось — нажмите ещё раз";
      } finally { downloading = false; }
    });
    $$("[data-act=forget]").forEach(b => b.addEventListener("click", () => { set(""); ticket = ""; state("form"); }));

    form.addEventListener("submit", async e => {
      e.preventDefault();
      err.textContent = "";
      const fd = new FormData(form);
      const body = { name: fd.get("name").trim(), contact: fd.get("contact").trim(), car: fd.get("car").trim(), website: fd.get("website") };
      let bad = false;
      [["name", 2], ["contact", 3]].forEach(([n, min]) => {
        const f = form.elements[n].closest(".fld");
        f.classList.remove("bad"); void f.offsetWidth;
        if (body[n].length < min) { f.classList.add("bad"); bad = true; }
      });
      if (bad) { err.textContent = "Заполните имя и контакт — без них не сможем ответить."; Obidi.say("Без имени и контакта админ не сможет ответить."); Obidi.shake(); return; }
      const btn = form.querySelector("button[type=submit]");
      btn.disabled = true; btn.querySelector("span").textContent = "Отправляю…";
      try {
        const r = await ObdApi.json("/api/apk/request", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        if (r.status === 200 && r.data.ticket) { ticket = r.data.ticket; set(ticket); form.reset(); poll(); }
        else if (r.status === 429) err.textContent = "Слишком много заявок с этого адреса. Попробуйте через час.";
        else if (r.status === 503) err.textContent = "Очередь заявок переполнена. Попробуйте позже.";
        else err.textContent = "Не получилось отправить. Проверьте поля и попробуйте ещё раз.";
      } catch (x) { err.textContent = "Сервер заявок сейчас недоступен. Попробуйте через пару минут."; }
      btn.disabled = false; btn.querySelector("span").textContent = "Отправить заявку";
    });

    document.addEventListener("visibilitychange", () => { if (!document.hidden && ticket) poll(); });
    poll();
  }
})();
