# -*- coding: utf-8 -*-
"""Выдача APK по заявке: сайт → заявка → владелец одобряет → ссылка срабатывает один раз.

Публичная часть (для сайта, без ключа):
  POST /api/apk/request {"name","contact","car","note"}  → {"ticket","status"}
  GET  /api/apk/status?ticket=<t>                         → {"status","created","decided","size","version"}
  GET  /api/apk/download?ticket=<t>                       → сам APK, только при status=approved, ровно один раз
  GET  /api/apk/info                                      → {"open": bool, "size", "version"} — принимаем ли заявки

Администратор (заголовок X-Admin-Key):
  GET  /api/admin/apk                                     → {"requests":[…], "apk":{…}}
  POST /api/admin/apk/decide {"id","action"}              → action: approve | reject | reset | delete
  GET  /api/admin/apk/file                                → APK для себя (не тратит ничьих ссылок)

Статусы: pending → approved → used; pending → rejected. reset возвращает used/rejected в approved (выдать ещё раз).
Ссылка помечается использованной в момент начала скачивания (чтобы две вкладки не скачали параллельно);
если передача оборвалась, статус возвращается в approved.
Новая заявка может уйти владельцу в Telegram, если заданы OBDAI_TG_TOKEN и OBDAI_TG_CHAT.
"""
import hmac
import json
import os
import secrets
import threading
import time
import urllib.parse
import urllib.request

APK_PATH = ""
ADMIN_KEY = ""
MAX_PENDING = 300           # больше висящих заявок не принимаем — защита от спама
PER_IP_HOUR = 4             # заявок с одного адреса в час
CHUNK = 64 * 1024

_ip_hits = {}               # ip → [время заявок]
_bad_admin = {}             # ip → [время неверных ключей]


def configure(base_dir, apk_path):
    global APK_PATH, ADMIN_KEY
    APK_PATH = apk_path
    ADMIN_KEY = os.environ.get("OBDAI_ADMIN_KEY", "").strip()
    if not ADMIN_KEY:
        f = os.path.join(base_dir, "admin.key")
        if os.path.exists(f):
            ADMIN_KEY = open(f, encoding="utf-8").read().strip()
        if not ADMIN_KEY:
            ADMIN_KEY = secrets.token_urlsafe(18)
            with open(f, "w", encoding="utf-8") as h:
                h.write(ADMIN_KEY + "\n")
            print("создан ключ администратора: %s (файл %s)" % (ADMIN_KEY, f))


def init_db(db):
    db.execute("""CREATE TABLE IF NOT EXISTS apk_requests(
        id INTEGER PRIMARY KEY AUTOINCREMENT, ticket TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL, contact TEXT NOT NULL, car TEXT NOT NULL, note TEXT NOT NULL,
        ip TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL,
        decided INTEGER NOT NULL DEFAULT 0, used INTEGER NOT NULL DEFAULT 0, downloads INTEGER NOT NULL DEFAULT 0)""")
    db.execute("CREATE INDEX IF NOT EXISTS idx_apk_status ON apk_requests(status, id)")


def _clean(s, limit):
    return str(s or "").strip().replace("\r", "")[:limit]


def _ip(h):
    # localhost.run и nginx передают настоящий адрес в X-Forwarded-For
    fwd = h.headers.get("X-Forwarded-For", "")
    return (fwd.split(",")[0].strip() if fwd else h.client_address[0])[:64]


def _apk_meta():
    try:
        st = os.stat(APK_PATH)
    except OSError:
        return None
    version = ""
    vf = os.path.join(os.path.dirname(APK_PATH), "version.txt")
    if os.path.exists(vf):
        version = open(vf, encoding="utf-8").read().strip()[:40]
    return {"size": st.st_size, "updated": int(st.st_mtime), "version": version}


def _row(r):
    return {"id": r[0], "ticket": r[1], "name": r[2], "contact": r[3], "car": r[4], "note": r[5], "ip": r[6],
            "created": r[7], "status": r[8], "decided": r[9], "used": r[10], "downloads": r[11]}


COLS = "id, ticket, name, contact, car, note, ip, created, status, decided, used, downloads"


def _is_admin(h):
    ip = _ip(h)
    now = time.time()
    bad = [t for t in _bad_admin.get(ip, []) if now - t < 600]
    if len(bad) >= 8:                       # 8 неверных ключей за 10 минут — пауза
        _bad_admin[ip] = bad
        return False
    key = h.headers.get("X-Admin-Key", "")
    if ADMIN_KEY and key and hmac.compare_digest(key.encode(), ADMIN_KEY.encode()):
        return True
    bad.append(now)
    _bad_admin[ip] = bad
    return False


def _notify(text):
    token = os.environ.get("OBDAI_TG_TOKEN", "").strip()
    chat = os.environ.get("OBDAI_TG_CHAT", "").strip()
    if not token or not chat:
        return

    def go():
        try:
            data = urllib.parse.urlencode({"chat_id": chat, "text": text}).encode()
            urllib.request.urlopen("https://api.telegram.org/bot%s/sendMessage" % token, data, timeout=10).read()
        except Exception:
            pass
    threading.Thread(target=go, daemon=True).start()


def _send_file(h, path, name, on_fail=None):
    size = os.path.getsize(path)
    h.send_response(200)
    h.send_header("Content-Type", "application/vnd.android.package-archive")
    h.send_header("Content-Length", str(size))
    h.send_header("Content-Disposition", 'attachment; filename="%s"' % name)
    h.send_header("Cache-Control", "no-store")
    h._cors()
    h.end_headers()
    sent = 0
    try:
        with open(path, "rb") as f:
            while True:
                b = f.read(CHUNK)
                if not b:
                    break
                h.wfile.write(b)
                sent += len(b)
        h.wfile.flush()
    except Exception:
        if on_fail:
            on_fail()
        return False
    if sent < size and on_fail:
        on_fail()
        return False
    return True


def handle_get(h, db, lock, path, q):
    if path == "/api/apk/info":
        meta = _apk_meta()
        return h._json(200, {"open": meta is not None, "size": meta["size"] if meta else 0,
                             "version": meta["version"] if meta else ""})

    if path == "/api/apk/status":
        t = _clean(q.get("ticket"), 64)
        with lock:
            r = db.execute("SELECT status, created, decided FROM apk_requests WHERE ticket=?", (t,)).fetchone()
        if not r:
            return h._json(404, {"error": "ticket"})
        meta = _apk_meta() or {}
        return h._json(200, {"status": r[0], "created": r[1], "decided": r[2],
                             "size": meta.get("size", 0), "version": meta.get("version", "")})

    if path == "/api/apk/download":
        t = _clean(q.get("ticket"), 64)
        if not t or not os.path.exists(APK_PATH):
            return h._json(404, {"error": "нет файла"})
        now = int(time.time())
        with lock:
            # атомарно: из approved в used — вторая вкладка уже не пройдёт
            cur = db.execute("UPDATE apk_requests SET status='used', used=?, downloads=downloads+1 "
                             "WHERE ticket=? AND status='approved'", (now, t))
            db.commit()
        if cur.rowcount != 1:
            with lock:
                r = db.execute("SELECT status FROM apk_requests WHERE ticket=?", (t,)).fetchone()
            return h._json(410 if r and r[0] == "used" else 403, {"error": r[0] if r else "ticket"})

        def back():
            with lock:
                db.execute("UPDATE apk_requests SET status='approved', used=0 WHERE ticket=? AND status='used'", (t,))
                db.commit()
        _send_file(h, APK_PATH, "OBD-AI.apk", back)
        return

    if path == "/api/admin/apk":
        if not _is_admin(h):
            return h._json(401, {"error": "key"})
        with lock:
            rows = db.execute("SELECT %s FROM apk_requests ORDER BY id DESC LIMIT 500" % COLS).fetchall()
        return h._json(200, {"requests": [_row(r) for r in rows], "apk": _apk_meta()})

    if path == "/api/admin/apk/file":
        if not _is_admin(h):
            return h._json(401, {"error": "key"})
        if not os.path.exists(APK_PATH):
            return h._json(404, {"error": "нет файла"})
        _send_file(h, APK_PATH, "OBD-AI.apk")
        return

    return h._json(404, {"error": "not found"})


def handle_post(h, db, lock, path, b):
    if path == "/api/apk/request":
        name = _clean(b.get("name"), 40)
        contact = _clean(b.get("contact"), 80)
        car = _clean(b.get("car"), 80)
        note = _clean(b.get("note"), 300)
        if len(name) < 2 or len(contact) < 3:
            return h._json(400, {"error": "name/contact"})
        if _clean(b.get("website"), 10):             # невидимое поле-ловушка для ботов
            return h._json(200, {"ticket": secrets.token_urlsafe(16), "status": "pending"})
        ip = _ip(h)
        now = time.time()
        hits = [t for t in _ip_hits.get(ip, []) if now - t < 3600]
        if len(hits) >= PER_IP_HOUR:
            return h._json(429, {"error": "slow down"})
        with lock:
            pending = db.execute("SELECT COUNT(*) FROM apk_requests WHERE status='pending'").fetchone()[0]
            if pending >= MAX_PENDING:
                return h._json(503, {"error": "full"})
            ticket = secrets.token_urlsafe(16)
            db.execute("INSERT INTO apk_requests(ticket, name, contact, car, note, ip, created, status) "
                       "VALUES(?,?,?,?,?,?,?, 'pending')", (ticket, name, contact, car, note, ip, int(now)))
            db.commit()
        hits.append(now)
        _ip_hits[ip] = hits
        _notify("OBD AI: заявка на APK\n%s · %s%s" % (name, contact, ("\n" + car) if car else ""))
        return h._json(200, {"ticket": ticket, "status": "pending"})

    if path == "/api/admin/apk/decide":
        if not _is_admin(h):
            return h._json(401, {"error": "key"})
        try:
            rid = int(b.get("id"))
        except (TypeError, ValueError):
            return h._json(400, {"error": "id"})
        action = _clean(b.get("action"), 10)
        now = int(time.time())
        with lock:
            if action == "approve" or action == "reset":
                cur = db.execute("UPDATE apk_requests SET status='approved', decided=?, used=0 WHERE id=?", (now, rid))
            elif action == "reject":
                cur = db.execute("UPDATE apk_requests SET status='rejected', decided=? WHERE id=?", (now, rid))
            elif action == "delete":
                cur = db.execute("DELETE FROM apk_requests WHERE id=?", (rid,))
            else:
                return h._json(400, {"error": "action"})
            db.commit()
        return h._json(200 if cur.rowcount == 1 else 404, {"ok": cur.rowcount == 1})

    return h._json(404, {"error": "not found"})
