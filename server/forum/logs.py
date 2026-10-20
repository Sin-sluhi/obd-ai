# -*- coding: utf-8 -*-
"""Логи проверок от приложений (шаг 1 плана «логи сами», 18.10.2026).

Приложение после каждой проверки живой машины (и после неудачного подключения) само присылает кусок обмена с адаптером
и журнал (LogShare.kt). Серийный номер VIN вырезан ещё на телефоне, геолокации нет. Сервер хранит лог сжатым и сам
считает, где опрос споткнулся (`analyze`) — владелец смотрит сводку по моделям в site/admin.html, а не просит логи.

Публичная часть (приложение):
  POST /api/logs/put {"install","app","meta":{…},"gz": base64(gzip(текст))} → {"id"}
Администратор (заголовок X-Admin-Key, тот же ключ, что у заявок на APK):
  GET  /api/admin/logs?limit=&brand=&bad=1   → {"logs":[…]}
  GET  /api/admin/logs/models                → {"models":[{"brand","model","year","n","bad","flags":{…},"last"}]}
  GET  /api/admin/logs/file?id=              → текст лога (text/plain)

Профиль модели (шаг 3 плана, 18.10.2026): в meta лога приходит список блоков и сработавших ступенек лестницы повторов
(`ecus`: req, resp, b29, ext, via, rung). Следующая машина той же модели перед поиском блоков спрашивает
  GET /api/profile?brand=&model=&year=                    → {"ecus":[{req,resp,b29,ext,rung,n}]}
и спрашивает эти блоки сразу правильным способом. Только подсказки чтения, никаких команд записи.

Лимиты: 400 КБ сжатого текста на лог, 40 логов в сутки с одной установки, 3000 логов всего (старые удаляются).
"""
import base64
import gzip
import json
import os
import re
import time

import apk

MAX_GZ = 400 * 1024
PER_INSTALL_DAY = 40
KEEP = 3000
LOG_DIR = ""

_hits = {}      # install → [время]
_told = {}      # (марка, модель, год, флаги) → когда сказали в Telegram


def configure(base_dir):
    global LOG_DIR
    LOG_DIR = os.path.join(base_dir, "logs")
    os.makedirs(LOG_DIR, exist_ok=True)


def init_db(db):
    db.execute("""CREATE TABLE IF NOT EXISTS logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, install TEXT NOT NULL, app TEXT NOT NULL,
        kind TEXT NOT NULL, ok INTEGER NOT NULL, error TEXT NOT NULL, vin TEXT NOT NULL, brand TEXT NOT NULL,
        model TEXT NOT NULL, year INTEGER NOT NULL, protocol TEXT NOT NULL, adapter TEXT NOT NULL,
        modules INTEGER NOT NULL, codes INTEGER NOT NULL, bus_only INTEGER NOT NULL, duration INTEGER NOT NULL,
        size INTEGER NOT NULL, flags TEXT NOT NULL, counts TEXT NOT NULL)""")
    db.execute("CREATE INDEX IF NOT EXISTS idx_logs_model ON logs(brand, model, year)")
    try:
        db.execute("ALTER TABLE logs ADD COLUMN ecus TEXT NOT NULL DEFAULT '[]'")
    except Exception:
        pass   # колонка уже есть


# ---- разбор: где опрос споткнулся ----

RX = re.compile(r"<< \[(\d+) мс\] ?(.*)$")
TX = re.compile(r">> (.*)$")
VIN_RE = re.compile(r"(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")
NEG = re.compile(r"\b7F ([0-9A-F]{2}) ([0-9A-F]{2})\b")

# коды отказа UDS/KWP, которые что-то говорят об опросе (ISO 14229 таблица A.1)
NRC = {"11": "сервис не поддерживается", "12": "подфункция не поддерживается", "22": "условия не выполнены",
       "31": "запрос вне диапазона", "33": "доступ закрыт", "78": "блок просит подождать",
       "7E": "не в этой сессии", "7F": "сервис не в этой сессии"}


DTC_SVC = ("19", "18", "17", "03", "07", "0A")
DTC_POS = ("59", "58", "57", "43", "47", "4A")
HEX2 = re.compile(r"^[0-9A-F]{2}$")
HEX3 = re.compile(r"^[0-9A-F]{3}$")


def _frames(resp):
    """Кадры с заголовками ATH1: (ID, байты данных без PCI) только для первых/одиночных кадров."""
    for fr in resp.split("|"):
        t = fr.split()
        if len(t) < 3:
            continue
        if HEX3.match(t[0]):
            rid, rest = t[0], t[1:]
        elif len(t) >= 6 and t[0] == "18" and t[1] in ("DA", "DB"):
            rid, rest = "".join(t[:4]), t[4:]
        else:
            continue
        if rest and rest[0] == "F1" and len(rest) > 2:          # расширенная адресация (шлюз BMW): адрес тестера
            rest = rest[1:]
        if not rest or not HEX2.match(rest[0]):
            continue
        pci = int(rest[0], 16) >> 4
        if pci == 0:
            yield rid, rest[1:]
        elif pci == 1:
            yield rid, rest[2:]


def analyze(text, meta):
    """Счётчики по обмену и флаги «где споткнулся». Флаг — только итог, а не шаг перебора: UNABLE TO CONNECT при
    поиске протокола, STOPPED и 7F 19 11 при переборе блоков — норма, если потом всё нашлось."""
    c = {"tx": 0, "rx": 0, "no_data": 0, "can_error": 0, "buffer_full": 0, "stopped": 0, "unable": 0,
         "bus_init_err": 0, "question": 0, "empty": 0, "slow": 0, "nrc": {}}
    ids = {}            # ID ответа → {"pos": n, "neg": {"19 11": n}}
    last_cmd = ""
    for line in text.splitlines():
        m = TX.search(line)
        if m:
            c["tx"] += 1
            last_cmd = m.group(1).strip()
            continue
        m = RX.search(line)
        if not m:
            continue
        c["rx"] += 1
        ms, r = int(m.group(1)), m.group(2).upper()
        if ms >= 2900 and not last_cmd.upper().startswith("AT"):
            c["slow"] += 1
        for k, pat in (("no_data", "NO DATA"), ("can_error", "CAN ERROR"), ("buffer_full", "BUFFER FULL"),
                       ("stopped", "STOPPED"), ("unable", "UNABLE TO CONNECT")):
            if pat in r:
                c[k] += 1
        if "BUS INIT" in r and "ERROR" in r:
            c["bus_init_err"] += 1
        if r.strip() == "?":
            c["question"] += 1
        if not r.strip():
            c["empty"] += 1
        for svc, code in NEG.findall(r):
            k = svc + " " + code
            c["nrc"][k] = c["nrc"].get(k, 0) + 1
        for rid, data in _frames(r):
            if not data:
                continue
            e = ids.setdefault(rid, {"pos": 0, "neg": {}})
            if data[0] in DTC_POS:
                e["pos"] += 1
            elif data[0] == "7F" and len(data) >= 3 and data[1] in DTC_SVC:
                k = data[1] + " " + data[2]
                e["neg"][k] = e["neg"].get(k, 0) + 1

    flags = []
    err = (meta.get("error") or "").strip()
    if meta.get("kind") == "connect":
        flags.append("не подключился: " + (err or "без текста ошибки"))
        tried = ["%s ×%d" % (n, c[k]) for k, n in (("unable", "UNABLE TO CONNECT"), ("bus_init_err", "BUS INIT ERROR"),
                                                     ("can_error", "CAN ERROR"), ("no_data", "NO DATA"), ("question", "«?»")) if c[k]]
        if tried:
            flags.append("ответы при подключении: " + ", ".join(tried))
        return flags, c
    if err:
        flags.append("проверка оборвалась: " + err)
    if meta.get("busOnly"):
        flags.append("стандартный OBD молчит, шина жива — искали блоки")
    if c["buffer_full"]:
        flags.append("BUFFER FULL ×%d — ответ не влез в адаптер" % c["buffer_full"])
    # двигатель прочитан стандартным OBD (ответ 43 на режим 03) — его отказ на заводской запрос не сбой опроса
    engine_obd = re.search(r"<< \[\d+ мс\] 43( |$)", text, re.M) is not None
    unread = []
    for rid, e in sorted(ids.items()):
        if rid == "7E8" and engine_obd:
            continue
        if e["neg"] and not e["pos"]:
            top = max(e["neg"].items(), key=lambda kv: kv[1])[0]
            unread.append("%s 7F %s%s" % (rid, top, " (" + NRC[top.split()[1]] + ")" if top.split()[1] in NRC else ""))
    if unread:
        flags.append("коды не прочитаны в %d блоках: %s" % (len(unread), "; ".join(unread[:6])))
    if not err and meta.get("modules", 0) <= 1:
        flags.append("блоков найдено: %d" % meta.get("modules", 0))
    if c["rx"] >= 20 and c["no_data"] > c["rx"] * 0.6 and meta.get("modules", 0) <= 1:
        flags.append("почти всё NO DATA (%d из %d)" % (c["no_data"], c["rx"]))
    return flags, c


# ---- хранение ----

def _clean(s, n):
    return str(s or "").strip().replace("\r", " ").replace("\n", " ")[:n]


def put(db, lock, b):
    install = _clean(b.get("install"), 64)
    meta = b.get("meta") if isinstance(b.get("meta"), dict) else {}
    gz_b64 = b.get("gz") or ""
    if not install or not isinstance(gz_b64, str) or not gz_b64:
        return 400, {"error": "install/gz"}
    try:
        raw = base64.b64decode(gz_b64, validate=True)
    except Exception:
        return 400, {"error": "base64"}
    if len(raw) > MAX_GZ:
        return 413, {"error": "too big"}
    try:
        text = gzip.decompress(raw).decode("utf-8", "replace")
    except Exception:
        return 400, {"error": "gzip"}
    text = VIN_RE.sub(lambda m: m.group(0)[:11] + "XXXXXX" if re.search(r"\d", m.group(0)) and re.search(r"[A-Z]", m.group(0)) else m.group(0), text)
    raw = gzip.compress(text.encode("utf-8"))
    now = time.time()
    hits = [t for t in _hits.get(install, []) if now - t < 86400]
    if len(hits) >= PER_INSTALL_DAY:
        return 429, {"error": "too many"}
    hits.append(now)
    _hits[install] = hits

    m = {k: meta.get(k) for k in ("kind", "ok", "error", "busOnly", "modules", "codes")}
    m["modules"] = int(m.get("modules") or 0)
    flags, counts = analyze(text, m)
    vin = _clean(meta.get("vin"), 17)
    if len(vin) == 17:
        vin = vin[:11] + "XXXXXX"       # на случай старой версии приложения: серийный номер не храним
    ecus = []
    for e in (meta.get("ecus") or [])[:80]:
        if isinstance(e, dict) and isinstance(e.get("req"), int) and isinstance(e.get("resp"), int):
            ecus.append({"req": e["req"], "resp": e["resp"], "b29": bool(e.get("b29")),
                         "ext": e.get("ext") if isinstance(e.get("ext"), int) else None,
                         "via": _clean(e.get("via"), 8), "rung": _clean(e.get("rung"), 16)})
    row = (int(now), install, _clean(b.get("app"), 32), _clean(meta.get("kind"), 16) or "check",
           1 if meta.get("ok") else 0, _clean(meta.get("error"), 300), vin, _clean(meta.get("brand"), 40),
           _clean(meta.get("model"), 60), int(meta.get("year") or 0), _clean(meta.get("protocol"), 60),
           _clean(meta.get("adapter"), 80), m["modules"], int(meta.get("codes") or 0),
           1 if meta.get("busOnly") else 0, int(meta.get("durationMs") or 0), len(raw),
           json.dumps(flags, ensure_ascii=False), json.dumps(counts, ensure_ascii=False))
    with lock:
        cur = db.execute("""INSERT INTO logs(time, install, app, kind, ok, error, vin, brand, model, year, protocol,
            adapter, modules, codes, bus_only, duration, size, flags, counts, ecus) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                         row + (json.dumps(ecus),))
        lid = cur.lastrowid
        old = db.execute("SELECT id FROM logs ORDER BY id DESC LIMIT -1 OFFSET ?", (KEEP,)).fetchall()
        for (oid,) in old:
            db.execute("DELETE FROM logs WHERE id=?", (oid,))
        db.commit()
    with open(os.path.join(LOG_DIR, "%d.txt.gz" % lid), "wb") as f:
        f.write(raw)
    for (oid,) in old:
        try:
            os.remove(os.path.join(LOG_DIR, "%d.txt.gz" % oid))
        except OSError:
            pass
    real = [f for f in flags if not f.startswith("блоков найдено")]   # одиночный двигатель у старых машин — норма
    key = (row[7], row[8], row[9], tuple(re.sub(r" ×\d+.*$|: .*$", "", f) for f in real))
    if real and now - _told.get(key, 0) > 86400:                         # одно и то же по модели — раз в сутки
        _told[key] = now
        car = " ".join(x for x in (row[7], row[8], str(row[9]) if row[9] else "") if x) or "машина без VIN"
        apk._notify("Лог №%d: %s · %s\n%s" % (lid, car, row[10] or "протокол ?", "\n".join("• " + f for f in flags[:6])))
    return 200, {"id": lid}


COLS = ("id, time, install, app, kind, ok, error, vin, brand, model, year, protocol, adapter, modules, codes, "
        "bus_only, duration, size, flags")


def _row(r):
    keys = [k.strip() for k in COLS.split(",")]
    d = dict(zip(keys, r))
    d["flags"] = json.loads(d["flags"] or "[]")
    d["install"] = d["install"][:8]
    return d


def handle_get(h, db, lock, path, q):
    if not apk._is_admin(h):
        return h._json(401, {"error": "admin"})
    if path == "/api/admin/logs":
        limit = max(1, min(500, int(q.get("limit") or 100)))
        where, args = [], []
        if q.get("brand"):
            where.append("brand=?")
            args.append(q["brand"])
        bad = q.get("bad") == "1"
        sql = "SELECT %s FROM logs %s ORDER BY id DESC LIMIT ?" % (COLS, ("WHERE " + " AND ".join(where)) if where else "")
        with lock:
            rows = db.execute(sql, args + [limit * 5 if bad else limit]).fetchall()
        out = [_row(r) for r in rows]
        if bad:     # «блоков найдено: 1» у старой машины — пометка, а не сбой
            out = [x for x in out if not x["ok"] or any(not f.startswith("блоков найдено") for f in x["flags"])][:limit]
        return h._json(200, {"logs": out})
    if path == "/api/admin/logs/models":
        with lock:
            rows = db.execute("SELECT brand, model, year, ok, flags, time FROM logs").fetchall()
        groups = {}
        for brand, model, year, ok, flags, t in rows:
            k = (brand or "?", model or "", year or 0)
            g = groups.setdefault(k, {"brand": k[0], "model": k[1], "year": k[2], "n": 0, "bad": 0, "flags": {}, "last": 0})
            g["n"] += 1
            fl = json.loads(flags or "[]")
            if not ok or any(not f.startswith("блоков найдено") for f in fl):
                g["bad"] += 1
            for f in fl:
                key = re.sub(r" ×\d+.*$| \(\d+ из \d+\)| в \d+ блоках|: .*$", "", f)   # «7F 19 11 ×3 — …» → «7F 19 11»
                g["flags"][key] = g["flags"].get(key, 0) + 1
            g["last"] = max(g["last"], t)
        out = sorted(groups.values(), key=lambda g: (-g["bad"], -g["n"]))
        return h._json(200, {"models": out})
    if path == "/api/admin/logs/file":
        try:
            lid = int(q.get("id") or 0)
            data = gzip.decompress(open(os.path.join(LOG_DIR, "%d.txt.gz" % lid), "rb").read())
        except Exception:
            return h._json(404, {"error": "not found"})
        h.send_response(200)
        h.send_header("Content-Type", "text/plain; charset=utf-8")
        h.send_header("Content-Length", str(len(data)))
        h.send_header("Content-Disposition", 'inline; filename="obd-ai-log-%d.txt"' % lid)
        h.send_header("Cache-Control", "no-store")
        h._cors()
        h.end_headers()
        h.wfile.write(data)
        return None
    return h._json(404, {"error": "not found"})


RUNG = re.compile(r"^(mask:[0-9A-F]{2}|session(:[0-9A-F]{2})?|wait|late|deep)$")


def profile(db, lock, q):
    """Подсказки по модели: для каждого блока — ступенька, которая чаще всего срабатывала у владельцев этой модели."""
    brand = _clean(q.get("brand"), 40)
    model = _clean(q.get("model"), 60)
    try:
        year = int(q.get("year") or 0)
    except ValueError:
        year = 0
    if not brand:
        return 400, {"error": "brand"}
    with lock:
        rows = db.execute("""SELECT ecus FROM logs WHERE brand=? AND model=? AND (year=? OR ?=0 OR year=0)
                             ORDER BY id DESC LIMIT 300""", (brand, model, year, year)).fetchall()
    votes = {}
    for (raw,) in rows:
        for e in json.loads(raw or "[]"):
            rung = e.get("rung") or ""
            if e.get("via") == "?" or not RUNG.match(rung) or rung in ("wait", "late", "deep"):
                continue   # подсказка — только то, что меняет первый запрос: маска и/или сессия
            key = (e["req"], e["resp"], bool(e.get("b29")), e.get("ext"))
            votes.setdefault(key, {}).setdefault(rung, 0)
            votes[key][rung] += 1
    out = []
    for (req, resp, b29, ext), rv in votes.items():
        rung, n = max(rv.items(), key=lambda kv: kv[1])
        out.append({"req": req, "resp": resp, "b29": b29, "ext": ext, "rung": rung, "n": n})
    return 200, {"ecus": out}
