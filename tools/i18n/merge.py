# -*- coding: utf-8 -*-
"""Сборка файлов переводов.

  python tools/i18n/merge.py            — tools/i18n/keys/*.json (ключ → русский, по файлу на модуль) → assets/i18n/ru.json
  python tools/i18n/merge.py --lang en  — tools/i18n/parts/en/*.json (куски перевода) → assets/i18n/en.json

Одинаковый ключ с разным текстом в двух файлах — ошибка (код возврата 1), чтобы не потерять строку молча.
"""
import glob
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")


def merge(files):
    merged = {}
    origin = {}
    bad = 0
    for f in sorted(files):
        with io.open(f, encoding="utf-8") as fh:
            data = json.load(fh)
        for k, v in data.items():
            if k in merged and merged[k] != v:
                print("конфликт ключа %s: %s (%s) != %s (%s)" % (k, merged[k][:40], origin[k], str(v)[:40], os.path.basename(f)))
                bad += 1
            merged[k] = v
            origin[k] = os.path.basename(f)
    return merged, bad


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(dict(sorted(data.items())), fh, ensure_ascii=False, indent=1)
        fh.write("\n")


def main():
    if "--lang" in sys.argv:
        code = sys.argv[sys.argv.index("--lang") + 1]
        files = glob.glob(os.path.join(HERE, "parts", code, "*.json"))
        if not files:
            print("нет кусков для", code)
            sys.exit(1)
        data, bad = merge(files)
        write(os.path.join(OUT, code + ".json"), data)
        print("%s.json: %d ключей из %d кусков" % (code, len(data), len(files)))
    else:
        files = glob.glob(os.path.join(HERE, "keys", "*.json"))
        if not files:
            print("нет файлов ключей в tools/i18n/keys")
            sys.exit(1)
        data, bad = merge(files)
        write(os.path.join(OUT, "ru.json"), data)
        print("ru.json: %d ключей из %d файлов" % (len(data), len(files)))
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
