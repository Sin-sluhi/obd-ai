# -*- coding: utf-8 -*-
"""Режет assets/i18n/ru.json на куски по N ключей: tools/i18n/chunks/01.json, 02.json… — по куску на агента-переводчика.

  python tools/i18n/split.py [--size 250]   → печатает число кусков
"""
import io
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
RU = os.path.join(ROOT, "app", "src", "main", "assets", "i18n", "ru.json")
CHUNKS = os.path.join(HERE, "chunks")


def main():
    size = int(sys.argv[sys.argv.index("--size") + 1]) if "--size" in sys.argv else 250
    with io.open(RU, encoding="utf-8") as fh:
        ru = json.load(fh)
    items = sorted(ru.items())
    if os.path.isdir(CHUNKS):
        shutil.rmtree(CHUNKS)
    os.makedirs(CHUNKS)
    n = 0
    for i in range(0, len(items), size):
        n += 1
        with io.open(os.path.join(CHUNKS, "%02d.json" % n), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(dict(items[i:i + size]), fh, ensure_ascii=False, indent=1)
    print(n)


if __name__ == "__main__":
    main()
