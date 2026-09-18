# -*- coding: utf-8 -*-
"""Сервер форума OBD AI: ветки по маркам → моделям → поколениям, сообщения и «кто онлайн».

Только стандартная библиотека Python 3.9+ и SQLite — ставится на любой VPS за минуту:
    python3 server/forum/server.py --port 8080 --db /var/lib/obdai/forum.db
Перед ним обычно nginx с HTTPS (см. README.md рядом). Адрес сервера попадает в приложение через
переменную репозитория FORUM_URL (BuildConfig) или через скрытые настройки разработчика.

API (JSON, UTF-8):
  GET  /api/messages?room=<id>&after=<msgId>&device=<uuid>&name=<ник>
       → {"messages":[{"id","name","text","time","device"}], "online": N}
       (сам запрос отмечает устройство как «онлайн» в этой ветке)
  POST /api/send      {"room","device","name","text"}  → {"id": N}
  POST /api/presence  {"room","device","name"}          → {"online": N}
  GET  /api/online?rooms=a,b,c                          → {"a": N, "b": N}
  GET  /api/health                                      → {"ok": true, "messages": N}
"""
import argparse
import json
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ONLINE_WINDOW = 90          # секунд с последнего запроса, пока считаем устройство онлайн
MAX_TEXT = 1000
MAX_NAME = 24
MIN_INTERVAL = 2.0          # секунд между сообщениями с одного устройства
PAGE = 100                  # сообщений за запрос

_lock = threading.Lock()
_db = None
_last_post = {}


def init_db(path):
    global _db
    _db = sqlite3.connect(path, check_same_thread=False)
    _db.execute("PRAGMA journal_mode=WAL")
    _db.execute("""CREATE TABLE IF NOT EXISTS messages(
        id INTEGER PRIMARY KEY AUTOINCREMENT, room TEXT NOT NULL, device TEXT NOT NULL,
        name TEXT NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL)""")
    _db.execute("CREATE INDEX IF NOT EXISTS idx_messages_room ON messages(room, id)")
    _db.execute("""CREATE TABLE IF NOT EXISTS presence(
        room TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL, seen INTEGER NOT NULL,
        PRIMARY KEY(room, device))""")
    _db.commit()


def clean(s, limit):
    s = (s or "").strip().replace("\r", "")
    return s[:limit]


def touch(room, device, name):
    now = int(time.time())
    with _lock:
        _db.execute("INSERT INTO presence(room, device, name, seen) VALUES(?,?,?,?) "
                    "ON CONFLICT(room, device) DO UPDATE SET name=excluded.name, seen=excluded.seen", (room, device, name, now))
        _db.commit()


def online(room):
    since = int(time.time()) - ONLINE_WINDOW
    with _lock:
        row = _db.execute("SELECT COUNT(*) FROM presence WHERE room=? AND seen>=?", (room, since)).fetchone()
    return row[0] if row else 0


def online_many(rooms):
    since = int(time.time()) - ONLINE_WINDOW
    out = {}
    with _lock:
        for r in rooms:
            row = _db.execute("SELECT COUNT(*) FROM presence WHERE room=? AND seen>=?", (r, since)).fetchone()
            out[r] = row[0] if row else 0
    return out


def messages(room, after):
    with _lock:
        if after > 0:
            rows = _db.execute("SELECT id, name, text, time, device FROM messages WHERE room=? AND id>? ORDER BY id LIMIT ?",
                               (room, after, PAGE)).fetchall()
        else:
            rows = _db.execute("SELECT id, name, text, time, device FROM messages WHERE room=? ORDER BY id DESC LIMIT ?",
                               (room, PAGE)).fetchall()
            rows = list(reversed(rows))
    return [{"id": r[0], "name": r[1], "text": r[2], "time": r[3], "device": r[4]} for r in rows]


def send(room, device, name, text):
    now = time.time()
    last = _last_post.get(device, 0)
    if now - last < MIN_INTERVAL:
        return None
    _last_post[device] = now
    with _lock:
        cur = _db.execute("INSERT INTO messages(room, device, name, text, time) VALUES(?,?,?,?,?)",
                          (room, device, name, text, int(now)))
        _db.commit()
        return cur.lastrowid


class Handler(BaseHTTPRequestHandler):
    server_version = "obdai-forum/1.0"

    def log_message(self, fmt, *args):  # тише
        pass

    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > 65536:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/api/health":
            with _lock:
                n = _db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
            return self._json(200, {"ok": True, "messages": n})
        if u.path == "/api/messages":
            room = clean(q.get("room"), 200)
            if not room:
                return self._json(400, {"error": "room"})
            device = clean(q.get("device"), 64)
            name = clean(q.get("name"), MAX_NAME)
            if device:
                touch(room, device, name or "Водитель")
            try:
                after = int(q.get("after") or 0)
            except ValueError:
                after = 0
            return self._json(200, {"messages": messages(room, after), "online": online(room)})
        if u.path == "/api/online":
            rooms = [r for r in clean(q.get("rooms"), 4000).split(",") if r][:200]
            return self._json(200, online_many(rooms))
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        b = self._body()
        room = clean(b.get("room"), 200)
        device = clean(b.get("device"), 64)
        name = clean(b.get("name"), MAX_NAME) or "Водитель"
        if not room or not device:
            return self._json(400, {"error": "room/device"})
        if u.path == "/api/presence":
            touch(room, device, name)
            return self._json(200, {"online": online(room)})
        if u.path == "/api/send":
            text = clean(b.get("text"), MAX_TEXT)
            if not text:
                return self._json(400, {"error": "text"})
            touch(room, device, name)
            mid = send(room, device, name, text)
            if mid is None:
                return self._json(429, {"error": "slow down"})
            return self._json(200, {"id": mid})
        return self._json(404, {"error": "not found"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--db", default="forum.db")
    args = ap.parse_args()
    init_db(args.db)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print("форум слушает %s:%d, база %s" % (args.host, args.port, args.db))
    srv.serve_forever()


if __name__ == "__main__":
    main()
