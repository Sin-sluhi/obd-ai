# -*- coding: utf-8 -*-
"""Связь с разработчиком: личная переписка пользователя приложения с владельцем (18.10.2026).

Кнопка с Обиди справа от нижней панели приложения → экран чата. Каждое устройство — свой тред (ключ — случайный
UUID устройства из приложения, тот же, что у форума); комнаты форума тут не годятся: их видят все.
Новое сообщение уходит владельцу в Telegram (OBDAI_TG_TOKEN/OBDAI_TG_CHAT), ответ он пишет в site/admin.html.

Приложение:
  POST /api/dev/send {"device","name","car","app","text"}        → {"id"}
  GET  /api/dev/messages?device=<uuid>&after=<id>               → {"messages":[{"id","owner","text","time"}]}
Владелец (заголовок X-Admin-Key):
  GET  /api/admin/dev                                           → {"threads":[{device,name,car,app,last,text,count,unread}]}
  GET  /api/admin/dev/thread?device=<uuid>                      → {"messages":[…]} (сообщения пользователя помечаются прочитанными)
  POST /api/admin/dev/reply {"device","text"}                   → {"id"}
"""
import time

import apk

MAX_TEXT = 2000
MIN_INTERVAL = 2.0
_last = {}


def init_db(db):
    db.execute("""CREATE TABLE IF NOT EXISTS dev_msgs(
        id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, owner INTEGER NOT NULL, name TEXT NOT NULL,
        car TEXT NOT NULL, app TEXT NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL, seen INTEGER NOT NULL DEFAULT 0)""")
    db.execute("CREATE INDEX IF NOT EXISTS idx_dev_device ON dev_msgs(device, id)")


def _clean(s, n):
    return str(s or "").strip().replace("\r", "")[:n]


def _row(r):
    return {"id": r[0], "owner": bool(r[1]), "text": r[2], "time": r[3]}


def send(db, lock, b):
    device = _clean(b.get("device"), 64)
    text = _clean(b.get("text"), MAX_TEXT)
    if len(device) < 8 or not text:
        return 400, {"error": "device/text"}
    now = time.time()
    if now - _last.get(device, 0) < MIN_INTERVAL:
        return 429, {"error": "slow down"}
    _last[device] = now
    name = _clean(b.get("name"), 40) or "Водитель"
    car = _clean(b.get("car"), 80)
    app = _clean(b.get("app"), 32)
    with lock:
        cur = db.execute("INSERT INTO dev_msgs(device, owner, name, car, app, text, time) VALUES(?,?,?,?,?,?,?)",
                         (device, 0, name, car, app, text, int(now)))
        db.commit()
        mid = cur.lastrowid
    apk._notify("✉️ Разработчику от %s%s%s:\n%s" % (name, " · " + car if car else "", " · " + app if app else "", text[:900]))
    return 200, {"id": mid}


def messages(db, lock, q):
    device = _clean(q.get("device"), 64)
    if len(device) < 8:
        return 400, {"error": "device"}
    try:
        after = int(q.get("after") or 0)
    except ValueError:
        after = 0
    with lock:
        rows = db.execute("SELECT id, owner, text, time FROM dev_msgs WHERE device=? AND id>? ORDER BY id LIMIT 200",
                          (device, after)).fetchall()
    return 200, {"messages": [_row(r) for r in rows]}


def handle_admin_get(h, db, lock, path, q):
    if not apk._is_admin(h):
        return h._json(401, {"error": "admin"})
    if path == "/api/admin/dev":
        with lock:
            rows = db.execute("""SELECT device, MAX(id), COUNT(*), SUM(CASE WHEN owner=0 AND seen=0 THEN 1 ELSE 0 END)
                                 FROM dev_msgs GROUP BY device ORDER BY MAX(id) DESC LIMIT 300""").fetchall()
            out = []
            for device, last_id, count, unread in rows:
                last = db.execute("SELECT owner, text, time FROM dev_msgs WHERE id=?", (last_id,)).fetchone()
                who = db.execute("SELECT name, car, app FROM dev_msgs WHERE device=? AND owner=0 ORDER BY id DESC LIMIT 1",
                                 (device,)).fetchone() or ("", "", "")
                out.append({"device": device, "name": who[0], "car": who[1], "app": who[2], "count": count,
                            "unread": unread or 0, "text": last[1], "owner": bool(last[0]), "last": last[2]})
        return h._json(200, {"threads": out})
    if path == "/api/admin/dev/thread":
        device = _clean(q.get("device"), 64)
        with lock:
            rows = db.execute("SELECT id, owner, text, time FROM dev_msgs WHERE device=? ORDER BY id", (device,)).fetchall()
            db.execute("UPDATE dev_msgs SET seen=1 WHERE device=? AND owner=0", (device,))
            db.commit()
        return h._json(200, {"messages": [_row(r) for r in rows]})
    return h._json(404, {"error": "not found"})


def handle_admin_post(h, db, lock, path, b):
    if not apk._is_admin(h):
        return h._json(401, {"error": "admin"})
    if path == "/api/admin/dev/reply":
        device = _clean(b.get("device"), 64)
        text = _clean(b.get("text"), MAX_TEXT)
        if len(device) < 8 or not text:
            return h._json(400, {"error": "device/text"})
        with lock:
            cur = db.execute("INSERT INTO dev_msgs(device, owner, name, car, app, text, time, seen) VALUES(?,?,?,?,?,?,?,1)",
                             (device, 1, "", "", "", text, int(time.time())))
            db.execute("UPDATE dev_msgs SET seen=1 WHERE device=? AND owner=0", (device,))
            db.commit()
        return h._json(200, {"id": cur.lastrowid})
    return h._json(404, {"error": "not found"})
