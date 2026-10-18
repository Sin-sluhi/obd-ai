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
    store.set(key); show(true); load(); loadLogs(); loadDev();
  });
  document.addEventListener("visibilitychange", () => { if (!document.hidden && key) { load(); loadLogs(); loadDev(); } });

  key = store.get();
  show(!!key);
  if (key) { load(); loadLogs(); loadDev(); setInterval(() => { if (!document.hidden) loadDev(); }, 30000); }
})();
