// Где сейчас сервер заявок. Адрес туннеля меняется — его публикует forum.ps1 в docs/forum_url.txt.
// Порядок: ?api=… в адресе страницы (для проверки) → сохранённый вручную → файл в репозитории.
// raw.githubusercontent.com держит старую копию файла до 5 минут, поэтому если адрес не отвечает,
// перечитываем его через GitHub API (кэш там ~минута; лимит 60 запросов в час на посетителя — нам хватает).
window.ObdApi = (function () {
  const RAW = "https://raw.githubusercontent.com/Sin-sluhi/obd-ai/main/docs/forum_url.txt";
  const API = "https://api.github.com/repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt?ref=main";
  let base = null, fixed = false;

  function stored() { try { return localStorage.getItem("obdai_api") || ""; } catch (e) { return ""; } }
  const clean = s => (s || "").trim().replace(/\/+$/, "");

  async function resolve(force, fresh) {
    if (base && !force) return base;
    const q = new URLSearchParams(location.search).get("api") || stored();
    if (q) { base = clean(q); fixed = true; return base; }
    let txt = "";
    if (fresh) {
      const r = await fetch(API + "&t=" + Date.now(), { cache: "no-store", headers: { Accept: "application/vnd.github.raw" } });
      if (r.ok) txt = await r.text();
    }
    if (!txt) {
      const r = await fetch(RAW + "?t=" + Date.now(), { cache: "no-store" });
      if (!r.ok) throw new Error("locator " + r.status);
      txt = await r.text();
    }
    const b = clean(txt);
    if (!/^https?:\/\//.test(b)) throw new Error("locator empty");
    base = b;
    return base;
  }

  async function call(path, opts = {}, attempt = 0) {
    const retry = async () => {
      if (fixed || attempt >= 2) return null;
      const old = base;
      await resolve(true, true);                     // свежий адрес из GitHub API
      if (base === old && attempt >= 1) return null;
      return call(path, opts, attempt + 1);
    };
    try {
      const b = await resolve();
      const r = await fetch(b + path, Object.assign({ cache: "no-store" }, opts));
      // туннель переехал: старый адрес отдаёт 502/503/404 «no tunnel» — перечитываем адрес
      if ([404, 502, 503, 530].includes(r.status) && !r.headers.get("content-type")?.includes("json")) {
        const again = await retry();
        if (again) return again;
      }
      return r;
    } catch (e) {
      const again = await retry().catch(() => null);
      if (again) return again;
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
