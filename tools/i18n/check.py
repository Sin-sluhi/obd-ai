# -*- coding: utf-8 -*-
"""Проверка переводов app/src/main/assets/i18n/*.json.

- ru.json — источник правды; в каждом другом языке ищем: недостающие ключи, лишние ключи, расхождение плейсхолдеров
  ({0}, {1}…), пустые строки, непереведённые строки (совпадают с русским и содержат кириллицу — для языков не на кириллице).
- Заодно ищем в Kotlin-файлах русские строковые литералы, которые ещё не вынесены в tr(): список файлов задаётся в SCOPE.

Запуск: python tools/i18n/check.py [--strict]   (strict: код возврата 1 при любой проблеме)
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
I18N = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")
KT = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "sinsluhi", "obdai")

# Файлы, где русских литералов быть не должно (кроме строк, помеченных комментарием `// i18n-ignore`)
SCOPE = [
    "ui/Screens.kt", "ui/Extra.kt", "ui/ForumScreen.kt", "ui/GarageScreen.kt", "ui/PurchaseScreen.kt", "ui/Journal.kt",
    "ui/FuelCard.kt", "ui/Theme.kt", "ui/Gauges.kt", "MainActivity.kt", "TripService.kt", "AppState.kt", "Models.kt",
    "Purchase.kt", "Service.kt", "Health.kt", "Drive.kt", "Fuel.kt", "Blackbox.kt", "Deep.kt", "ObdDecoder.kt",
    "ObdLink.kt", "Elm327.kt", "Transport.kt", "Garage.kt", "Forum.kt", "OpenAiClient.kt", "AiConfig.kt", "Catalog.kt",
]
LATIN_LANGS = {"en", "es", "fr", "de", "uz", "tk", "az", "ro"}
PH = re.compile(r"\{\d+\}")
CYR = re.compile(r"[А-Яа-яЁё]")
LIT = re.compile(r'"(?:[^"\\]|\\.)*[А-Яа-яЁё](?:[^"\\]|\\.)*"')


def load(code):
    with io.open(os.path.join(I18N, code + ".json"), encoding="utf-8") as fh:
        return json.load(fh)


def main():
    strict = "--strict" in sys.argv
    problems = 0
    if not os.path.isdir(I18N):
        print("нет папки", I18N)
        sys.exit(1)
    ru = load("ru")
    print("ru.json: %d ключей" % len(ru))
    for name in sorted(os.listdir(I18N)):
        if not name.endswith(".json") or name == "ru.json":
            continue
        code = name[:-5]
        try:
            tr = load(code)
        except ValueError as ex:
            print("%s: битый JSON: %s" % (name, ex))
            problems += 1
            continue
        missing = [k for k in ru if k not in tr]
        extra = [k for k in tr if k not in ru]
        empty = [k for k, v in tr.items() if not str(v).strip()]
        ph = [k for k in ru if k in tr and sorted(PH.findall(ru[k])) != sorted(PH.findall(str(tr[k])))]
        same = [k for k in ru if k in tr and tr[k] == ru[k] and CYR.search(ru[k]) and code in LATIN_LANGS]
        print("%s: %d ключей; нет %d, лишних %d, пустых %d, плейсхолдеры %d, непереведённых %d"
              % (name, len(tr), len(missing), len(extra), len(empty), len(ph), len(same)))
        for label, lst in (("нет", missing), ("плейсхолдеры", ph), ("пустые", empty), ("непереведённые", same)):
            for k in lst[:5]:
                print("   %s: %s" % (label, k))
        problems += len(missing) + len(empty) + len(ph)
    left = 0
    for rel in SCOPE:
        p = os.path.join(KT, rel.replace("/", os.sep))
        if not os.path.exists(p):
            continue
        n = 0
        with io.open(p, encoding="utf-8") as fh:
            for line in fh:
                if "i18n-ignore" in line or line.strip().startswith("//") or line.strip().startswith("*"):
                    continue
                n += len(LIT.findall(line))
        if n:
            print("русских литералов осталось: %3d  %s" % (n, rel))
            left += n
    print("итого литералов вне tr(): %d" % left)
    if strict and (problems or left):
        sys.exit(1)


if __name__ == "__main__":
    main()
