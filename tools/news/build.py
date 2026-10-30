# -*- coding: utf-8 -*-
"""Снимок новостей автомира для сайта: python tools/news/build.py site/news.json

Запускается в pages.yml (по расписанию раз в 30 минут и при каждой выкладке сайта): тот же разбор RSS, что у сервера
(server/forum/news.py), но результат кладётся файлом рядом с сайтом — так лента не зависит от туннеля и домашнего ПК.
Если ни одна лента не прочиталась, файл не перезаписывается и скрипт завершается с ошибкой (сайт тогда спросит сервер).
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import news  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "news.json"
news.refresh()
if not news._items:
    print("ни одна лента не прочиталась")
    sys.exit(1)
data = {"items": news._items, "updated": news._updated or int(time.time()),
        "sources": [{"id": f, "name": n} for f, n, _ in news.FEEDS]}
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False)
by = {}
for x in news._items:
    by[x["source"]] = by.get(x["source"], 0) + 1
print("новостей: %d %s → %s" % (len(news._items), by, out))
