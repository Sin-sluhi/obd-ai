// Где сейчас сервер заявок. Адрес туннеля меняется — его публикует forum.ps1 в docs/forum_url.txt.
// Порядок: ?api=… в адресе страницы (для проверки) → сохранённый вручную → файл в репозитории.
window.ObdApi = (function () {
  const RAW = "https://raw.githubusercontent.com/Sin-sluhi/obd-ai/main/docs/forum_url.txt";
  let base = null;

  function stored() { try { return localStorage.getItem("obdai_api") || ""; } catch (e) { return ""; } }

  async function resolve(force) {
    if (base && !force) return base;
    const q = new URLSearchParams(location.search).get("api");
    if (q) { base = q.replace(/\/+$/, ""); return base; }
    const s = stored();
    if (s) { base = s.replace(/\/+$/, ""); return base; }
    const r = await fetch(RAW + "?t=" + Date.now(), { cache: "no-store" });
    if (!r.ok) throw new Error("locator " + r.status);
    base = (await r.text()).trim().replace(/\/+$/, "");
    if (!/^https?:\/\//.test(base)) throw new Error("locator empty");
    return base;
  }

  async function call(path, opts = {}, retry = true) {
    try {
      const b = await resolve();
      const r = await fetch(b + path, Object.assign({ cache: "no-store" }, opts));
      // туннель переехал: старый адрес отдаёт 502/503 или не отвечает — перечитываем адрес один раз
      if ((r.status === 502 || r.status === 503 || r.status === 530) && retry) { base = null; return call(path, opts, false); }
      return r;
    } catch (e) {
      if (retry) { base = null; return call(path, opts, false); }
      throw e;
    }
  }

  async function json(path, opts) {
    const r = await call(path, opts);
    let data = {};
    try { data = await r.json(); } catch (e) { /* пустой ответ */ }
    return { status: r.status, data };
  }

  return { resolve, call, json, get base() { return base; } };
})();
