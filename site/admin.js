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

  $("#selfDl").addEventListener("click", async () => {
    // fetch + blob: ключ уходит в заголовке, а не в адресе ссылки
    const b = $("#selfDl"); b.disabled = true; b.querySelector("span").textContent = "Качаю…";
    try {
      const r = await ObdApi.call("/api/admin/apk/file", { headers: { "X-Admin-Key": key } });
      if (!r.ok) throw new Error(r.status);
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a"); a.href = url; a.download = "OBD-AI.apk"; document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) { toast("Файла нет на сервере"); }
    b.disabled = false; b.querySelector("span").textContent = "Скачать себе";
  });

  $("#loginForm").addEventListener("submit", e => {
    e.preventDefault();
    key = e.target.elements.key.value.trim();
    if (!key) return;
    store.set(key); show(true); load();
  });
  document.addEventListener("visibilitychange", () => { if (!document.hidden && key) load(); });

  key = store.get();
  show(!!key);
  if (key) load();
})();
