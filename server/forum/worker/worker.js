// Форум OBD AI на Cloudflare Workers + D1: тот же API, что у server.py, без своего сервера.
// Бесплатный план: 100 000 запросов в день (опрос чата раз в 4 с ≈ 20 000 запросов на активного читателя в сутки),
// поэтому это запасной вариант на первое время; VPS с server.py лимитов не имеет.
//   npm i -g wrangler && wrangler login
//   wrangler d1 create obdai-forum        → database_id в wrangler.toml
//   wrangler d1 execute obdai-forum --file schema.sql --remote
//   wrangler deploy                       → адрес вида https://obdai-forum.<аккаунт>.workers.dev → FORUM_URL

const ONLINE_WINDOW = 90;
const MAX_TEXT = 1000;
const MAX_NAME = 24;
const MIN_INTERVAL = 2;
const PAGE = 100;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });

const clean = (s, limit) => (typeof s === "string" ? s.trim().replace(/\r/g, "").slice(0, limit) : "");
const now = () => Math.floor(Date.now() / 1000);

async function touch(db, room, device, name) {
  await db.prepare("INSERT INTO presence(room, device, name, seen) VALUES(?1,?2,?3,?4) ON CONFLICT(room, device) DO UPDATE SET name=?3, seen=?4")
    .bind(room, device, name, now()).run();
}

async function online(db, room) {
  const r = await db.prepare("SELECT COUNT(*) AS n FROM presence WHERE room=?1 AND seen>=?2").bind(room, now() - ONLINE_WINDOW).first();
  return r ? r.n : 0;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const db = env.DB;
    const p = url.pathname;

    if (request.method === "GET" && p === "/api/health") {
      const r = await db.prepare("SELECT COUNT(*) AS n FROM messages").first();
      return json({ ok: true, messages: r ? r.n : 0 });
    }

    if (request.method === "GET" && p === "/api/messages") {
      const room = clean(url.searchParams.get("room"), 200);
      if (!room) return json({ error: "room" }, 400);
      const device = clean(url.searchParams.get("device"), 64);
      const name = clean(url.searchParams.get("name"), MAX_NAME) || "Водитель";
      const after = parseInt(url.searchParams.get("after") || "0", 10) || 0;
      if (device) await touch(db, room, device, name);
      let rows;
      if (after > 0) {
        rows = (await db.prepare("SELECT id, name, text, time, device FROM messages WHERE room=?1 AND id>?2 ORDER BY id LIMIT ?3").bind(room, after, PAGE).all()).results;
      } else {
        rows = (await db.prepare("SELECT id, name, text, time, device FROM messages WHERE room=?1 ORDER BY id DESC LIMIT ?2").bind(room, PAGE).all()).results.reverse();
      }
      return json({ messages: rows, online: await online(db, room) });
    }

    if (request.method === "GET" && p === "/api/online") {
      const rooms = clean(url.searchParams.get("rooms"), 4000).split(",").filter(Boolean).slice(0, 200);
      const out = {};
      for (const r of rooms) out[r] = await online(db, r);
      return json(out);
    }

    if (request.method === "POST" && (p === "/api/send" || p === "/api/presence")) {
      let b = {};
      try { b = await request.json(); } catch (e) { b = {}; }
      const room = clean(b.room, 200);
      const device = clean(b.device, 64);
      const name = clean(b.name, MAX_NAME) || "Водитель";
      if (!room || !device) return json({ error: "room/device" }, 400);
      await touch(db, room, device, name);
      if (p === "/api/presence") return json({ online: await online(db, room) });
      const text = clean(b.text, MAX_TEXT);
      if (!text) return json({ error: "text" }, 400);
      const last = await db.prepare("SELECT MAX(time) AS t FROM messages WHERE device=?1").bind(device).first();
      if (last && last.t && now() - last.t < MIN_INTERVAL) return json({ error: "slow down" }, 429);
      const res = await db.prepare("INSERT INTO messages(room, device, name, text, time) VALUES(?1,?2,?3,?4,?5)").bind(room, device, name, text, now()).run();
      return json({ id: res.meta.last_row_id });
    }

    return json({ error: "not found" }, 404);
  },
};
