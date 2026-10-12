# -*- coding: utf-8 -*-
"""Новости автомира для сайта: RSS нескольких изданий → один список.

  GET /api/news?limit=N&source=<id>  → {"items":[{"title","link","source","sourceName","time","image","lead"}], "sources":[…], "updated"}

Ленты перечитываются в фоне раз в 20 минут; браузер сам читать чужие RSS не может (CORS), поэтому через сервер.
Отдаём только заголовок, короткий анонс (до 220 символов) и ссылку на оригинал — читать статью человек идёт к изданию.
"""
import email.utils
import html
import re
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

FEEDS = [
    ("motor", "Мотор", "https://motor.ru/export/rss.xml"),
    ("kolesa", "Колёса.ру", "https://www.kolesa.ru/rss"),
    ("5koleso", "5 колесо", "https://www.5koleso.ru/rss/"),
    ("gazeta", "Газета.Ru · Авто", "https://www.gazeta.ru/export/rss/auto.xml"),
]
REFRESH = 20 * 60
PER_FEED = 25
KEEP = 60
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
CONTENT = "{http://purl.org/rss/1.0/modules/content/}encoded"
MEDIA = "{http://search.yahoo.com/mrss/}"

_items = []
_updated = 0
_lock = threading.Lock()
_started = False


def _text(s):
    s = re.sub(r"<[^>]+>", " ", s or "")
    s = html.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


def _lead(s, n=220):
    s = _text(s)
    if len(s) <= n:
        return s
    cut = s[:n].rsplit(" ", 1)[0].rstrip(",.;:—- ")
    return cut + "…"


def _image(item):
    for enc in item.findall("enclosure"):
        if (enc.get("type") or "").startswith("image") and enc.get("url"):
            return enc.get("url")
    for tag in (MEDIA + "content", MEDIA + "thumbnail"):
        m = item.find(tag)
        if m is not None and m.get("url"):
            return m.get("url")
    for src in (item.findtext(CONTENT), item.findtext("description")):
        m = re.search(r'<img[^>]+src="(https?://[^"]+)"', src or "")
        if m:
            return m.group(1)
    return ""


def _fetch(fid, name, url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/rss+xml, application/xml, text/xml"})
    data = urllib.request.urlopen(req, timeout=15).read()
    root = ET.fromstring(data)
    out = []
    for it in root.iter("item"):
        title = _text(it.findtext("title"))
        link = (it.findtext("link") or "").strip()
        if not title or not link.startswith("http"):
            continue
        try:
            t = int(email.utils.parsedate_to_datetime(it.findtext("pubDate") or "").timestamp())
        except Exception:
            t = 0
        img = _image(it)
        out.append({"title": title, "link": link, "source": fid, "sourceName": name, "time": t,
                    "image": img if img.startswith("https://") else "", "lead": _lead(it.findtext("description"))})
        if len(out) >= PER_FEED:
            break
    return out


def refresh():
    global _items, _updated
    got = []
    for fid, name, url in FEEDS:
        try:
            got.extend(_fetch(fid, name, url))
        except Exception as e:
            print("новости: %s не прочиталась: %s" % (fid, e))
    if not got:
        return
    seen, uniq = set(), []
    for x in sorted(got, key=lambda x: -x["time"]):
        key = re.sub(r"\W+", "", x["title"].lower())[:60]
        if key in seen:
            continue
        seen.add(key)
        uniq.append(x)
    with _lock:
        _items = uniq[:KEEP]
        _updated = int(time.time())


def start():
    global _started
    if _started:
        return
    _started = True

    def loop():
        while True:
            refresh()
            time.sleep(REFRESH)
    threading.Thread(target=loop, daemon=True).start()


def _mix(items, limit):
    """Лента «Все»: по очереди из каждого издания (свежее первым), чтобы одно не забивало остальные."""
    queues = {}
    for x in items:
        queues.setdefault(x["source"], []).append(x)
    order = sorted(queues, key=lambda k: -queues[k][0]["time"])
    out = []
    while len(out) < limit and any(queues.values()):
        for k in order:
            if queues[k] and len(out) < limit:
                out.append(queues[k].pop(0))
    return out


def handle_get(h, q):
    try:
        limit = max(1, min(int(q.get("limit") or 12), KEEP))
    except ValueError:
        limit = 12
    src = (q.get("source") or "").strip()
    with _lock:
        if src:
            items = [x for x in _items if x["source"] == src][:limit]
        else:
            items = _mix(_items, limit)
        updated = _updated
    return h._json(200, {"items": items, "updated": updated,
                         "sources": [{"id": f, "name": n} for f, n, _ in FEEDS]})
