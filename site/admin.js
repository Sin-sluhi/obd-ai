// Панель владельца: заявки на APK. Ключ хранится только в этом браузере.
(function () {
  "use strict";
  const $ = s => document.querySelector(s);
  const KEY = "obdai_admin";
  const LABEL = { pending: "ждёт", approved: "одобрена", used: "скачано", rejected: "отклонена" };
  let key = "", filter = "pending", rows = null, timer = 0, seenPending = -1;

  const store = {
    get() { try { return localStorage.getItem(KEY) || ""; } catch (e) { return ""; } },
    set(v) { try { v ? localStorage.setItem(KEY, v) : localStorage.removeItem(KEY); } catch (e) { /* только на сессию */ } }
  };
  const esc = s => String(s || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const when = t => t ? new Date(t * 1000).toLocaleString("ru-RU", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
  const mb = n => (n / 1048576).toLocaleString("ru-RU", { maximumFractionDigits: 1 }) + " МБ";

  function toast(t) {
    const el = $("#toast"); el.textContent = t; el.classList.add("on");
    clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove("on"), 2200);
  }
  const hdr = () => ({ "X-Admin-Key": key, "Content-Type": "application/json" });

  function show(logged) {
    $("#login").hidden = logged; $("#panel").hidden = !logged;
  }

  async function load() {
    clearTimeout(timer);
    try {
      const r = await ObdApi.json("/api/admin/apk", { headers: hdr() });
      if (r.status === 401) { store.set(""); key = ""; show(false); $(".gate-err").textContent = "Ключ не подошёл."; return; }
      const fresh = r.data.requests || [];
      const changed = JSON.stringify(fresh) !== JSON.stringify(rows);
      rows = fresh;
      const a = r.data.apk;
      $("#apkInfo").textContent = a ? [a.version && "версия " + a.version, mb(a.size), "обновлён " + when(a.updated)].filter(Boolean).join(" · ")
        : "файла нет — заявки принимаются, но скачать будет нечего";
      $("#srv").textContent = ObdApi.base || "";
      if (changed) render();
      const p = rows.filter(x => x.status === "pending").length;
      if (seenPending >= 0 && p > seenPending) toast("Новая заявка");
      seenPending = p;
      document.title = (p ? "(" + p + ") " : "") + "Заявки — OBD AI";
    } catch (e) {
      $("#srv").textContent = "сервер недоступен";
    }
    timer = setTimeout(load, 10000);
  }

  function render() {
    if (!rows) return;
    const count = f => f === "all" ? rows.length : rows.filter(x => x.status === f).length;
    document.querySelectorAll(".tab").forEach(t => { t.classList.toggle("on", t.dataset.f === filter); t.querySelector("em").textContent = count(t.dataset.f); });
    const list = rows.filter(x => filter === "all" || x.status === filter);
    if (!list.length) { $("#list").innerHTML = `<div class="empty">Здесь пусто</div>`; return; }
    $("#list").innerHTML = list.map(x => {
      const acts = [];
      if (x.status === "pending") {
        acts.push(`<button class="btn btn-lime btn-sm" data-a="approve" data-id="${x.id}"><span>Открыть доступ</span></button>`);
        acts.push(`<button class="btn btn-no btn-sm" data-a="reject" data-id="${x.id}"><span>Отклонить</span></button>`);
      }
      if (x.status === "approved") acts.push(`<button class="btn btn-ghost btn-sm" data-a="copy" data-t="${esc(x.ticket)}"><span>Скопировать ссылку</span></button>`);
      if (x.status === "used" || x.status === "rejected") acts.push(`<button class="btn btn-ghost btn-sm" data-a="reset" data-id="${x.id}"><span>Выдать ещё раз</span></button>`);
      acts.push(`<button class="link-btn" data-a="delete" data-id="${x.id}">удалить</button>`);
      return `<article class="req ${x.status}">
        <div>
          <h3>${esc(x.name)} <span class="pill ${x.status}">${LABEL[x.status] || x.status}</span></h3>
          <div class="meta"><span>${esc(x.contact)}</span>${x.car ? `<span>${esc(x.car)}</span>` : ""}
            <span class="mono">${when(x.created)}${x.used ? " · скачал " + when(x.used) : ""}${x.downloads > 1 ? " · раз: " + x.downloads : ""}</span></div>
        </div>
        <div class="acts">${acts.join("")}</div>
      </article>`;
    }).join("");
  }

  async function act(a, id) {
    if (a === "delete" && !confirm("Удалить заявку насовсем?")) return;
    const r = await ObdApi.json("/api/admin/apk/decide", { method: "POST", headers: hdr(), body: JSON.stringify({ id: +id, action: a }) });
    toast(r.status === 200 ? ({ approve: "Доступ открыт", reject: "Отклонено", reset: "Ссылка снова работает", delete: "Удалено" }[a]) : "Не получилось");
    load();
  }

  $("#list").addEventListener("click", async e => {
    const b = e.target.closest("[data-a]"); if (!b) return;
    if (b.dataset.a === "copy") {
      const link = location.href.replace(/admin\.html.*$/, "") + "#get=" + b.dataset.t;
      try { await navigator.clipboard.writeText(link); toast("Ссылка скопирована — её можно отправить человеку"); }
      catch (x) { prompt("Ссылка для человека:", link); }
      return;
    }
    act(b.dataset.a, b.dataset.id);
  });
  $("#tabs").addEventListener("click", e => { const t = e.target.closest(".tab"); if (t) { filter = t.dataset.f; render(); } });
  $("#logout").addEventListener("click", () => { store.set(""); key = ""; clearTimeout(timer); show(false); });

  // fetch + blob: ключ уходит в заголовке, а не в адресе ссылки
  async function selfDownload(btnId, path, fileName, label) {
    const b = $(btnId); b.disabled = true; b.querySelector("span").textContent = "Качаю…";
    try {
      const r = await ObdApi.call(path, { headers: { "X-Admin-Key": key } });
      if (!r.ok) throw new Error(r.status);
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a"); a.href = url; a.download = fileName; document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) { toast("Файла нет на сервере"); }
    b.disabled = false; b.querySelector("span").textContent = label;
  }
  $("#selfDl").addEventListener("click", () => selfDownload("#selfDl", "/api/admin/apk/file", "OBD-AI.apk", "Скачать себе"));
  // версия разработчика: та же сборка, но с режимом разработчика (7 нажатий на версию в настройках)
  $("#devDl").addEventListener("click", () => selfDownload("#devDl", "/api/admin/apk/dev", "OBD-AI-dev.apk", "Версия разработчика"));

  // ---- сообщения от пользователей (server/forum/dev.py): треды по устройствам, ответ уходит в приложение ----
  let dvThreads = [], dvOpen = null;
  async function loadDev() {
    try {
      const r = await ObdApi.json("/api/admin/dev", { headers: hdr() });
      if (r.status !== 200) throw new Error(r.status);
      dvThreads = r.data.threads || [];
      renderDev();
      if (dvOpen) openThread(dvOpen, true);
    } catch (e) {
      $("#dvList").innerHTML = `<div class="empty">Сообщения не загрузились — сервер старой версии или недоступен</div>`;
    }
  }
  function renderDev() {
    if (!dvThreads.length) { $("#dvList").innerHTML = `<div class="empty">Пока никто не писал</div>`; return; }
    $("#dvList").innerHTML = dvThreads.map(t => `<article class="req ${t.unread ? "pending" : "clean"}" data-dv="${esc(t.device)}" style="cursor:pointer">
        <div>
          <h3>${esc(t.name || "Водитель")}${t.unread ? `<span class="unread">${t.unread}</span>` : ""}</h3>
          <div class="meta"><span>${esc(t.car || "машина не указана")}</span><span class="mono">${esc(t.app)} · ${when(t.last)} · сообщений ${t.count}</span></div>
          <div class="meta" style="margin-top:6px"><span>${t.owner ? "Вы: " : ""}${esc((t.text || "").slice(0, 140))}</span></div>
        </div>
      </article>`).join("");
  }
  async function openThread(device, quiet) {
    dvOpen = device;
    const t = dvThreads.find(x => x.device === device) || {};
    $("#dv").classList.add("open"); $("#dvThread").hidden = false;
    $("#dvWho").textContent = t.name || "Водитель";
    $("#dvMeta").textContent = [t.car, t.app].filter(Boolean).join(" · ");
    const r = await ObdApi.json("/api/admin/dev/thread?device=" + encodeURIComponent(device), { headers: hdr() });
    if (r.status !== 200) { if (!quiet) toast("Не открылось"); return; }
    const box = $("#dvMsgs");
    box.innerHTML = (r.data.messages || []).map(m => `<div class="dv-m ${m.owner ? "me" : ""}">${esc(m.text)}<small>${m.owner ? "вы" : esc(t.name || "водитель")} · ${when(m.time)}</small></div>`).join("");
    box.scrollTop = box.scrollHeight;
    if (t.unread) { t.unread = 0; renderDev(); }
  }
  $("#dvList").addEventListener("click", e => { const a = e.target.closest("[data-dv]"); if (a) openThread(a.dataset.dv); });
  $("#dvClose").addEventListener("click", () => { dvOpen = null; $("#dvThread").hidden = true; $("#dv").classList.remove("open"); });
  $("#dvRefresh").addEventListener("click", loadDev);
  $("#dvForm").addEventListener("submit", async e => {
    e.preventDefault();
    const ta = e.target.elements.t, text = ta.value.trim();
    if (!text || !dvOpen) return;
    const r = await ObdApi.json("/api/admin/dev/reply", { method: "POST", headers: hdr(), body: JSON.stringify({ device: dvOpen, text }) });
    if (r.status === 200) { ta.value = ""; toast("Ответ ушёл — человек увидит его в приложении"); loadDev(); }
    else toast("Не отправилось");
  });

  // ---- аккаунты (server/forum/accounts.py): кто может пользоваться приложением ----
  let acView = "wait", acRows = [];
  const until = a => a.forever ? "бессрочно" : a.access ? "до " + new Date(a.until * 1000).toLocaleDateString("ru-RU") : "нет доступа";
  async function loadAcc() {
    try {
      const r = await ObdApi.json("/api/admin/accounts", { headers: hdr() });
      if (r.status !== 200) throw new Error(r.status);
      acRows = r.data.accounts || [];
      renderAcc();
    } catch (e) {
      $("#acList").innerHTML = `<div class="empty">Аккаунты не загрузились — сервер старой версии или недоступен</div>`;
    }
  }
  function renderAcc() {
    const pick = v => acRows.filter(a => v === "all" || (v === "on" ? a.access : !a.access));
    document.querySelectorAll("#acTabs .tab").forEach(t => { t.classList.toggle("on", t.dataset.v === acView); t.querySelector("em").textContent = pick(t.dataset.v).length; });
    const list = pick(acView);
    if (!list.length) { $("#acList").innerHTML = `<div class="empty">Здесь пусто</div>`; return; }
    $("#acList").innerHTML = list.map(a => `<article class="req ${a.access ? "approved" : "pending"}">
        <div>
          <h3>${esc(a.login)}${a.name ? " · " + esc(a.name) : ""} <span class="pill ${a.access ? "approved" : "pending"}">${until(a)}</span></h3>
          <div class="meta"><span>${esc(a.plan || "без тарифа")}</span><span>телефонов: ${a.devices}</span>
            <span class="mono">создан ${when(a.created)}${a.last_seen ? " · был " + when(a.last_seen) : ""}</span></div>
        </div>
        <div class="acts">
          <select data-days="${a.id}" class="btn btn-ghost btn-sm"><option value="30">30 дн.</option><option value="90">90 дн.</option><option value="365">год</option><option value="0">бессрочно</option></select>
          <button class="btn btn-lime btn-sm" data-ac="grant" data-id="${a.id}"><span>${a.access ? "Продлить" : "Выдать доступ"}</span></button>
          ${a.access ? `<button class="btn btn-no btn-sm" data-ac="revoke" data-id="${a.id}"><span>Забрать</span></button>` : ""}
          <button class="link-btn" data-open="${a.id}">подробно</button>
          <button class="link-btn" data-ac="password" data-id="${a.id}">новый пароль</button>
          <button class="link-btn" data-ac="delete" data-id="${a.id}">удалить</button>
        </div>
      </article>`).join("");
  }
  $("#acList").addEventListener("click", async e => {
    const o = e.target.closest("[data-open]"); if (o) { openAcc(+o.dataset.open); return; }
    const b = e.target.closest("[data-ac]"); if (!b) return;
    const id = +b.dataset.id, a = b.dataset.ac;
    if (a === "delete" && !confirm("Удалить аккаунт насовсем? Человек больше не сможет войти.")) return;
    if (a === "revoke" && !confirm("Забрать доступ? Приложение у человека закроется при следующей проверке.")) return;
    const days = (document.querySelector(`[data-days="${id}"]`) || {}).value || "30";
    const r = await ObdApi.json("/api/admin/accounts/" + a, { method: "POST", headers: hdr(), body: JSON.stringify({ id, days: +days }) });
    if (r.status !== 200) { toast("Не получилось"); return; }
    if (a === "password") prompt("Новый пароль — отправьте человеку (старый больше не работает, телефоны вышли из аккаунта):", r.data.password);
    else toast({ grant: "Доступ выдан", revoke: "Доступ забран", delete: "Удалено" }[a]);
    loadAcc();
  });
  $("#acTabs").addEventListener("click", e => { const t = e.target.closest(".tab"); if (t) { acView = t.dataset.v; renderAcc(); } });
  $("#acRefresh").addEventListener("click", loadAcc);
  $("#acForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = e.target.elements;
    const r = await ObdApi.json("/api/admin/accounts/create", { method: "POST", headers: hdr(),
      body: JSON.stringify({ login: f.login.value.trim(), name: f.name.value.trim(), days: +f.days.value, plan: "tester" }) });
    if (r.status === 409) { toast("Такой логин уже есть"); return; }
    if (r.status !== 200) { toast("Логин: 3–64 символа, буквы, цифры, . @ + -"); return; }
    prompt("Аккаунт создан. Отправьте человеку логин и пароль:", "логин: " + r.data.account.login + "  пароль: " + r.data.password);
    e.target.reset(); acView = "on"; loadAcc();
  });

  // ---- приглашения (accounts.py, бета): код при регистрации сразу открывает доступ ----
  let inView = "free", inRows = [];
  async function loadInv() {
    try {
      const r = await ObdApi.json("/api/admin/accounts/invites", { headers: hdr() });
      if (r.status !== 200) throw new Error(r.status);
      inRows = r.data.invites || [];
      renderInv();
    } catch (e) {
      $("#inList").innerHTML = `<div class="empty">Приглашения не загрузились — сервер старой версии или недоступен</div>`;
    }
  }
  function renderInv() {
    const pick = v => inRows.filter(i => v === "used" ? i.used_by : !i.used_by);
    document.querySelectorAll("#inTabs .tab").forEach(t => { t.classList.toggle("on", t.dataset.v === inView); t.querySelector("em").textContent = pick(t.dataset.v).length; });
    const list = pick(inView);
    if (!list.length) { $("#inList").innerHTML = `<div class="empty">Здесь пусто</div>`; return; }
    const term = i => i.days > 0 ? i.days + " дн." : "бессрочно";
    $("#inList").innerHTML = (inView === "free" ? `<div class="acts" style="margin:0 0 10px"><button class="btn btn-ghost btn-sm" data-in-copy="all"><span>Скопировать все свободные</span></button></div>` : "") +
      list.map(i => `<article class="req ${i.used_by ? "approved" : "pending"}">
        <div>
          <h3 class="mono">${esc(i.code)} <span class="pill ${i.used_by ? "approved" : "pending"}">${term(i)}</span></h3>
          <div class="meta">${i.note ? `<span>${esc(i.note)}</span>` : ""}<span class="mono">создан ${when(i.created)}</span>
            ${i.used_by ? `<span>взял ${esc(i.login || "#" + i.used_by)} · ${when(i.used_at)}</span>` : ""}</div>
        </div>
        <div class="acts">${i.used_by ? "" : `<button class="btn btn-ghost btn-sm" data-in-copy="${esc(i.code)}"><span>Скопировать</span></button>
          <button class="link-btn" data-in-del="${esc(i.code)}">удалить</button>`}</div>
      </article>`).join("");
  }
  async function copy(text, done) {
    try { await navigator.clipboard.writeText(text); toast(done); } catch (e) { prompt("Скопируйте:", text); }
  }
  $("#inList").addEventListener("click", async e => {
    const c = e.target.closest("[data-in-copy]");
    if (c) {
      const v = c.dataset.inCopy;
      if (v === "all") copy(inRows.filter(i => !i.used_by).map(i => i.code).join(String.fromCharCode(10)), "Скопированы все свободные коды");
      else copy(v, "Код скопирован");
      return;
    }
    const d = e.target.closest("[data-in-del]"); if (!d || !confirm("Удалить код? Им больше нельзя будет зарегистрироваться.")) return;
    const r = await ObdApi.json("/api/admin/accounts/invites/delete", { method: "POST", headers: hdr(), body: JSON.stringify({ code: d.dataset.inDel }) });
    if (r.status !== 200) { toast("Не получилось"); return; }
    loadInv();
  });
  $("#inTabs").addEventListener("click", e => { const t = e.target.closest(".tab"); if (t) { inView = t.dataset.v; renderInv(); } });
  $("#inRefresh").addEventListener("click", loadInv);
  $("#inForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = e.target.elements;
    const r = await ObdApi.json("/api/admin/accounts/invites/create", { method: "POST", headers: hdr(),
      body: JSON.stringify({ count: +f.count.value || 1, days: +f.days.value, note: f.note.value.trim() }) });
    if (r.status !== 200) { toast("Не получилось — сервер старой версии?"); return; }
    copy(r.data.codes.join(String.fromCharCode(10)), "Создано кодов: " + r.data.codes.length + " — скопированы");
    inView = "free"; loadInv();
  });

  // ---- удалённая проверка (remote.py): консоль к адаптеру человека, только чтение ----
  let rmRows = [], rmOpen = "", rmTimer = 0;
  async function loadRemote() {
    try {
      const r = await ObdApi.json("/api/admin/remote", { headers: hdr() });
      if (r.status !== 200) throw new Error(r.status);
      rmRows = r.data.sessions || [];
      renderRemote();
    } catch (e) {
      $("#rmList").innerHTML = `<div class="empty">Сессии не загрузились — сервер старой версии или недоступен</div>`;
    }
  }
  function renderRemote() {
    if (!rmRows.length) { $("#rmList").innerHTML = `<div class="empty">Ещё никто не открывал</div>`; return; }
    $("#rmList").innerHTML = rmRows.slice(0, 8).map(s => `<article class="req ${s.open ? "approved" : "rejected"}">
        <div>
          <h3>${esc(s.car || "машина")} <span class="pill ${s.open ? "approved" : "rejected"}">${s.open ? (s.online ? "на связи" : "открыта, телефон молчит") : "закрыта"}</span></h3>
          <div class="meta"><span class="mono">${esc(s.device)} · ${esc(s.app)}</span><span class="mono">открыта ${when(s.opened)}</span><span>команд: ${s.cmds}</span></div>
        </div>
        <div class="acts"><button class="btn ${s.open ? "btn-lime" : "btn-ghost"} btn-sm" data-rm="${esc(s.id)}"><span>${s.open ? "Консоль" : "Журнал"}</span></button></div>
      </article>`).join("");
  }
  async function rmLoadLog() {
    if (!rmOpen) return;
    const r = await ObdApi.json("/api/admin/remote/log?session=" + encodeURIComponent(rmOpen), { headers: hdr() });
    if (r.status !== 200) return;
    const box = $("#rmLog"), stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    box.innerHTML = (r.data.cmds || []).map(c => `<div class="tx">&gt; ${esc(c.cmd)}</div>` +
      (c.refused ? `<div class="no">отклонено телефоном: ${esc(c.refused)}</div>` :
       c.done ? `<div class="rx">${esc(c.out || "(пусто)")} <span class="lg-bad">${c.ms} мс</span></div>` :
       `<div class="no">${c.sent ? "выполняется…" : "ждёт телефон…"}</div>`)).join("") || `<div class="no">Команд ещё не было</div>`;
    if (stick) box.scrollTop = box.scrollHeight;
  }
  function rmShow(id) {
    rmOpen = id;
    const s = rmRows.find(x => x.id === id) || {};
    $("#rmCon").hidden = false;
    $("#rmWho").textContent = s.car || "машина";
    $("#rmMeta").textContent = (s.device || "") + " · до " + (s.expires ? new Date(s.expires * 1000).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) : "");
    $("#rmForm").hidden = !s.open; $("#rmQuick").hidden = !s.open; $("#rmClose").hidden = !s.open;
    clearInterval(rmTimer); rmLoadLog();
    if (s.open) rmTimer = setInterval(() => { if (!document.hidden) rmLoadLog(); }, 1500);
    $("#rmCon").scrollIntoView({ behavior: "smooth", block: "nearest" });
  }
  async function rmSend(cmd) {
    if (!rmOpen || !cmd.trim()) return;
    const r = await ObdApi.json("/api/admin/remote/send", { method: "POST", headers: hdr(), body: JSON.stringify({ session: rmOpen, cmd }) });
    if (r.status === 400) { toast("Не отправлено: " + (r.data && r.data.error || "команда не для чтения")); return; }
    if (r.status === 410) { toast("Сессия уже закрыта"); loadRemote(); return; }
    if (r.status !== 200) { toast("Не получилось"); return; }
    rmLoadLog();
  }
  $("#rmList").addEventListener("click", e => { const b = e.target.closest("[data-rm]"); if (b) rmShow(b.dataset.rm); });
  $("#rmQuick").addEventListener("click", e => { const b = e.target.closest("[data-q]"); if (b) rmSend(b.dataset.q); });
  $("#rmForm").addEventListener("submit", e => { e.preventDefault(); const f = e.target.elements.cmd; rmSend(f.value); f.value = ""; f.focus(); });
  $("#rmClose").addEventListener("click", async () => {
    if (!confirm("Закончить сессию? Телефон перестанет принимать команды.")) return;
    await ObdApi.json("/api/admin/remote/close", { method: "POST", headers: hdr(), body: JSON.stringify({ session: rmOpen }) });
    clearInterval(rmTimer); loadRemote(); setTimeout(() => rmShow(rmOpen), 400);
  });
  $("#rmRefresh").addEventListener("click", loadRemote);

  // ---- скрипты опроса (remote.py): команды чтения после проверки подходящей машины ----
  let scRows = [];
  async function loadScripts() {
    try {
      const r = await ObdApi.json("/api/admin/scripts", { headers: hdr() });
      if (r.status !== 200) throw new Error(r.status);
      scRows = r.data.scripts || [];
      renderScripts();
    } catch (e) {
      $("#scList").innerHTML = `<div class="empty">Скрипты не загрузились — сервер старой версии или недоступен</div>`;
    }
  }
  function renderScripts() {
    if (!scRows.length) { $("#scList").innerHTML = `<div class="empty">Скриптов нет</div>`; return; }
    const years = s => s.year_from || s.year_to ? ` · ${s.year_from || "…"}–${s.year_to || "…"}` : "";
    $("#scList").innerHTML = scRows.map(s => `<article class="req ${s.enabled ? "approved" : "rejected"}">
        <div>
          <h3>${esc(s.brand || "все марки")}${s.model ? " " + esc(s.model) : ""}${years(s)} <span class="pill ${s.enabled ? "approved" : "rejected"}">${s.enabled ? "включён" : "выключен"}</span></h3>
          <div class="meta">${s.note ? `<span>${esc(s.note)}</span>` : ""}<span>шагов: ${s.steps.length}</span><span class="mono">изменён ${when(s.updated)}</span></div>
          <div class="sc-steps">${s.steps.slice(0, 6).map(x => esc((x.tx ? x.tx + " → " : "") + x.cmd + (x.label ? " (" + x.label + ")" : ""))).join("<br>")}${s.steps.length > 6 ? "<br>…" : ""}</div>
        </div>
        <div class="acts"><button class="btn btn-ghost btn-sm" data-sc-edit="${s.id}"><span>Править</span></button><button class="link-btn" data-sc-del="${s.id}">удалить</button></div>
      </article>`).join("");
  }
  $("#scList").addEventListener("click", async e => {
    const ed = e.target.closest("[data-sc-edit]");
    if (ed) {
      const s = scRows.find(x => x.id === +ed.dataset.scEdit); if (!s) return;
      const f = $("#scForm").elements;
      f.id.value = s.id; f.brand.value = s.brand; f.model.value = s.model; f.year_from.value = s.year_from || ""; f.year_to.value = s.year_to || "";
      f.note.value = s.note; f.enabled.checked = s.enabled; f.steps.value = JSON.stringify(s.steps, null, 1);
      $("#scForm").scrollIntoView({ behavior: "smooth", block: "nearest" });
      return;
    }
    const d = e.target.closest("[data-sc-del]"); if (!d || !confirm("Удалить скрипт?")) return;
    await ObdApi.json("/api/admin/scripts/delete", { method: "POST", headers: hdr(), body: JSON.stringify({ id: +d.dataset.scDel }) });
    loadScripts();
  });
  $("#scForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = e.target.elements;
    let steps;
    try { steps = JSON.parse(f.steps.value); } catch (err) { toast("Шаги — не JSON: " + err.message); return; }
    const r = await ObdApi.json("/api/admin/scripts/save", { method: "POST", headers: hdr(), body: JSON.stringify({
      id: +f.id.value || 0, brand: f.brand.value.trim(), model: f.model.value.trim(), year_from: +f.year_from.value || 0,
      year_to: +f.year_to.value || 0, note: f.note.value.trim(), enabled: f.enabled.checked, steps }) });
    if (r.status !== 200) { toast(r.data && r.data.error || "Не сохранилось"); return; }
    toast("Скрипт сохранён"); e.target.reset(); f.id.value = ""; loadScripts();
  });
  $("#scForm").addEventListener("reset", () => { setTimeout(() => { $("#scForm").elements.id.value = ""; }, 0); });
  $("#scRefresh").addEventListener("click", loadScripts);

  const PLAN = { m1: "1 месяц", m3: "3 месяца", m6: "6 месяцев", m12: "12 месяцев" };
  const PAYST = { pending: "ждёт проверки", confirmed: "подтверждена", rejected: "отклонена", new: "не оплачена" };

  // ---- карточка пользователя: всё, что о нём известно ----
  async function openAcc(id) {
    const r = await ObdApi.json("/api/admin/accounts/detail?id=" + id, { headers: hdr() });
    if (r.status !== 200) { toast("Не открылось"); return; }
    const a = r.data.account, card = $("#acCard");
    const row = (k, v) => `<tr><th>${k}</th><td>${v}</td></tr>`;
    card.innerHTML = `<div class="dv-head"><h3>${esc(a.login)}${a.name ? " · " + esc(a.name) : ""}</h3><span class="sp"></span><button class="link-btn" id="acCardClose" type="button">закрыть</button></div>
      <table>
        ${row("Доступ", until(a) + (a.plan ? " · тариф " + (PLAN[a.plan] || esc(a.plan)) : ""))}
        ${row("Зарегистрирован", when(a.created))}
        ${row("Последний раз в приложении", a.last_seen ? when(a.last_seen) : "—")}
        ${row("Соглашение", a.agreement ? "принято, редакция " + esc(a.agreement) : "—")}
        ${row("Согласие на обработку данных", a.pd_at ? when(a.pd_at) : "—")}
        ${row("Писал разработчику", r.data.dev_messages ? r.data.dev_messages + " сообщ." : "нет")}
        ${a.note ? row("Заметка", esc(a.note)) : ""}
      </table>
      <b>Телефоны</b>
      <table><tr><th>Телефон</th><th>Android</th><th>Версия приложения</th><th>Вошёл</th><th>Был</th></tr>
        ${(r.data.sessions || []).map(x => `<tr><td>${esc(x.model || "—")}</td><td>${esc(x.os || "—")}</td><td>${esc(x.app || "—")}</td><td class="mono">${when(x.created)}</td><td class="mono">${when(x.last_seen)}</td></tr>`).join("") || `<tr><td colspan="5">не входил</td></tr>`}
      </table>
      <b>Оплаты</b>
      <table><tr><th>Код</th><th>Тариф</th><th>Сумма</th><th>Статус</th><th>Когда</th></tr>
        ${(r.data.payments || []).map(x => `<tr><td class="mono">${esc(x.code)}</td><td>${PLAN[x.plan] || esc(x.plan)}</td><td>${x.amount.toLocaleString("ru-RU")} ₽</td><td>${PAYST[x.status] || x.status}</td><td class="mono">${when(x.paid_at || x.created)}</td></tr>`).join("") || `<tr><td colspan="5">оплат не было</td></tr>`}
      </table>`;
    card.hidden = false; card.scrollIntoView({ behavior: "smooth", block: "start" });
    $("#acCardClose").addEventListener("click", () => { card.hidden = true; });
  }

  // ---- логи с машин (server/forum/logs.py): сводка по моделям и последние проверки ----
  let lgView = "models", lgModels = [], lgLogs = [];
  const flagLi = f => `<li class="${f.startsWith("блоков найдено") ? "info" : ""}">${esc(f)}</li>`;
  const carName = x => [x.brand, x.model, x.year || ""].filter(Boolean).join(" ") || "машина без VIN";

  async function loadLogs() {
    try {
      const bad = $("#lgBad").checked ? "&bad=1" : "";
      const [m, l] = await Promise.all([
        ObdApi.json("/api/admin/logs/models", { headers: hdr() }),
        ObdApi.json("/api/admin/logs?limit=150" + bad, { headers: hdr() })
      ]);
      if (m.status !== 200 || l.status !== 200) throw new Error(m.status + "/" + l.status);
      lgModels = m.data.models || []; lgLogs = l.data.logs || [];
      renderLogs();
    } catch (e) {
      $("#lgList").innerHTML = `<div class="empty">Логи не загрузились — сервер старой версии или недоступен</div>`;
    }
  }

  function renderLogs() {
    const models = $("#lgBad").checked ? lgModels.filter(g => g.bad) : lgModels;
    document.querySelectorAll("#lgTabs .tab").forEach(t => {
      t.classList.toggle("on", t.dataset.v === lgView);
      t.querySelector("em").textContent = t.dataset.v === "models" ? models.length : lgLogs.length;
    });
    if (lgView === "models") {
      if (!models.length) { $("#lgList").innerHTML = `<div class="empty">Логов пока нет</div>`; return; }
      $("#lgList").innerHTML = models.map(g => {
        const fl = Object.entries(g.flags).sort((a, b) => b[1] - a[1]).map(([f, n]) => flagLi(f + (n > 1 ? " — в " + n + " логах" : "")));
        return `<article class="req ${g.bad ? "pending" : "clean"}">
          <div>
            <h3>${esc(carName(g))}</h3>
            <div class="meta"><span>проверок: ${g.n}</span><span>споткнулся: ${g.bad}</span><span class="mono">последний ${when(g.last)}</span></div>
            ${fl.length ? `<ul class="flags">${fl.join("")}</ul>` : ""}
          </div>
          <div class="acts"><button class="btn btn-ghost btn-sm" data-lg="filter" data-b="${esc(g.brand)}"><span>Логи марки</span></button></div>
        </article>`;
      }).join("");
      return;
    }
    if (!lgLogs.length) { $("#lgList").innerHTML = `<div class="empty">Здесь пусто</div>`; return; }
    $("#lgList").innerHTML = lgLogs.map(x => `<article class="req ${x.flags.some(f => !f.startsWith("блоков найдено")) || !x.ok ? "pending" : "clean"}">
        <div>
          <h3>${esc(carName(x))} <span class="pill ${x.ok ? "approved" : "rejected"}">${x.kind === "connect" ? "подключение" : "проверка"}${x.ok ? "" : " · сбой"}</span></h3>
          <div class="meta"><span>${esc(x.protocol || "протокол ?")}</span><span>${esc(x.adapter)}</span>
            <span>блоков ${x.modules} · кодов ${x.codes}</span>
            <span class="mono">№${x.id} · ${when(x.time)} · ${esc(x.app)} · ${esc(x.vin)} · уст. ${esc(x.install)} · ${Math.round(x.duration / 1000)} с</span></div>
          ${x.flags.length ? `<ul class="flags">${x.flags.map(flagLi).join("")}</ul>` : ""}
        </div>
        <div class="acts"><button class="btn btn-ghost btn-sm" data-lg="open" data-id="${x.id}"><span>Открыть лог</span></button></div>
      </article>`).join("");
  }

  $("#lgList").addEventListener("click", async e => {
    const b = e.target.closest("[data-lg]"); if (!b) return;
    if (b.dataset.lg === "filter") {
      const r = await ObdApi.json("/api/admin/logs?limit=150&brand=" + encodeURIComponent(b.dataset.b), { headers: hdr() });
      if (r.status === 200) { lgLogs = r.data.logs || []; lgView = "logs"; renderLogs(); }
      return;
    }
    // ключ в заголовке, лог открывается как файл из blob
    const r = await ObdApi.call("/api/admin/logs/file?id=" + b.dataset.id, { headers: { "X-Admin-Key": key } });
    if (!r.ok) { toast("Лога нет на сервере"); return; }
    const url = URL.createObjectURL(new Blob([await r.text()], { type: "text/plain;charset=utf-8" }));
    const a = document.createElement("a"); a.href = url; a.download = "obd-ai-log-" + b.dataset.id + ".txt"; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  });
  $("#lgTabs").addEventListener("click", e => { const t = e.target.closest(".tab"); if (t) { lgView = t.dataset.v; renderLogs(); } });
  $("#lgBad").addEventListener("change", loadLogs);
  $("#lgRefresh").addEventListener("click", loadLogs);

  $("#loginForm").addEventListener("submit", e => {
    e.preventDefault();
    key = e.target.elements.key.value.trim();
    if (!key) return;
    store.set(key); show(true); load(); loadLogs(); loadDev(); loadAcc(); loadInv(); loadRemote(); loadScripts();
  });
  document.addEventListener("visibilitychange", () => { if (!document.hidden && key) { load(); loadLogs(); loadDev(); loadAcc(); loadInv(); loadRemote(); loadScripts(); } });

  key = store.get();
  show(!!key);
  if (key) { load(); loadLogs(); loadDev(); loadAcc(); loadInv(); loadRemote(); loadScripts(); setInterval(() => { if (!document.hidden) loadDev(); }, 30000); }
})();
