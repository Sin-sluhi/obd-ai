# -*- coding: utf-8 -*-
"""Запасной путь приложения до нейронки Groq (20.10.2026).

У части телефонов прямое соединение с api.groq.com сбрасывается (лог Nissan 20.10.2026: «Connection reset» на обеих
моделях, разбор не удался). Тогда приложение шлёт тот же запрос сюда, а сервер пересылает его в Groq как есть —
с ключом из заголовка Authorization самого запроса. Своего ключа у сервера нет, ответ Groq отдаётся без изменений.

  POST /api/ai/groq/chat/completions   (тело и заголовки — как у OpenAI-совместимого /chat/completions)

Пересылаем только в Groq и только chat/completions; ключ должен выглядеть как ключ Groq (gsk_…), тело — до 2 МБ
(с фото приборки), не больше 40 запросов в минуту с одного адреса.
"""
import time
import urllib.error
import urllib.request

GROQ = "https://api.groq.com/openai/v1/chat/completions"
MAX_BODY = 2 * 1024 * 1024
PER_MINUTE = 40
_hits = {}


def handle(h):
    ip = h.headers.get("X-Forwarded-For", "").split(",")[0].strip() or h.client_address[0]
    now = time.time()
    hits = [t for t in _hits.get(ip, []) if now - t < 60]
    if len(hits) >= PER_MINUTE:
        return h._json(429, {"error": {"message": "relay: too many requests, try again in 30s"}})
    hits.append(now)
    _hits[ip] = hits
    auth = h.headers.get("Authorization", "")
    if not auth.startswith("Bearer gsk_"):
        return h._json(401, {"error": {"message": "relay: groq key required"}})
    n = int(h.headers.get("Content-Length") or 0)
    if n <= 0 or n > MAX_BODY:
        return h._json(413, {"error": {"message": "relay: body size"}})
    body = h.rfile.read(n)
    req = urllib.request.Request(GROQ, data=body, method="POST", headers={
        "Authorization": auth, "Content-Type": "application/json",
        # без браузерного User-Agent Cloudflare перед Groq отвечает 1010 (см. CLAUDE.md, грабли)
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
    })
    try:
        with urllib.request.urlopen(req, timeout=240) as r:
            status, data, retry = r.status, r.read(), r.headers.get("retry-after")
    except urllib.error.HTTPError as e:
        status, data, retry = e.code, e.read(), e.headers.get("retry-after")
    except Exception as e:
        return h._json(502, {"error": {"message": "relay: groq unreachable: %s" % e}})
    h.send_response(status)
    h.send_header("Content-Type", "application/json; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    if retry:
        h.send_header("Retry-After", retry)
    h._cors()
    h.end_headers()
    h.wfile.write(data)
    return None
