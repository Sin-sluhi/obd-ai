# -*- coding: utf-8 -*-
"""Зонд Groq: какие модели доступны ключу и какие лимиты у бесплатного тарифа.

Запуск из GitHub Actions (groq-probe.yml) или локально: GROQ_API_KEY=... python tools/kb/probe.py
Печатает список моделей и заголовки x-ratelimit-* после короткого запроса к каждой модели из PROBE.
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = "https://api.groq.com/openai/v1"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
PROBE = ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"]


def call(key, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data else "GET",
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json",
                                          "User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, dict(resp.headers), json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        return ex.code, dict(ex.headers), ex.read()[:500].decode("utf-8", "replace")


def main():
    key = os.environ.get("GROQ_API_KEY") or os.environ.get("AI_API_KEY")
    if not key:
        print("нет ключа", file=sys.stderr)
        sys.exit(2)
    status, _, models = call(key, "/models")
    print("== модели (%s)" % status)
    if isinstance(models, dict):
        for m in sorted(x.get("id", "") for x in models.get("data", [])):
            print("  " + m)
    else:
        print(models)
    for model in PROBE:
        body = {"model": model, "max_tokens": 40, "temperature": 0,
                "messages": [{"role": "user", "content": 'Ответь одним словом: столица России? Ответ в JSON: {"answer": "..."}'}],
                "response_format": {"type": "json_object"}}
        status, headers, data = call(key, "/chat/completions", body)
        print("== %s -> %s" % (model, status))
        for k, v in sorted(headers.items()):
            if k.lower().startswith("x-ratelimit") or k.lower() == "retry-after":
                print("  %s: %s" % (k, v))
        if isinstance(data, dict):
            print("  ответ: %s" % json.dumps(data.get("choices", [{}])[0].get("message", {}).get("content", ""), ensure_ascii=False)[:200])
            print("  usage: %s" % json.dumps(data.get("usage", {})))
        else:
            print("  " + str(data)[:300])


if __name__ == "__main__":
    main()
