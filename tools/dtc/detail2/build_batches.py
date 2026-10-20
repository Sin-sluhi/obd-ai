# -*- coding: utf-8 -*-
"""Пачки для подробной базы второй волны (все коды, у которых есть только название).

Берёт коды без развёрнутой записи: стандартные из справочника (CODES без поля e), открытый слой SAE (dtc_open g),
пятизначные VAG (dtc_open v) и марочные (dtc_open b + BRANDS без e). Группирует по узлу — по основе английского
названия без слов про тип отказа, банк, датчик и цилиндр, — чтобы один писатель вёл весь узел и цифры в пачке не
расползались. Пачки — batches/<серия>_<NNN>.json, по ~45 кодов, узел не режется между пачками.

Запуск: python tools/dtc/detail2/build_batches.py   (пишет batches/ и index.json)
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "dtc"))

ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SIZE = 45

STRIP = [
    r"\b(circuit|cct)\b.*$", r"\b(sensor|switch|solenoid|valve|relay|motor|heater|control|module)\s*[\"']?[A-Z]?\d*\s*$",
    r"\bbank\s*\d+\b", r"\bsensor\s*\d+\b", r"\bcylinder\s*\d+\b", r"\bcyl\.?\s*\d+\b", r"\b(low|high|open|short|intermittent|erratic|range|performance|rationality|stuck|slow|response|malfunction|plausibility|implausible|signal|input|output)\b.*$",
    r"\b(to|-)\s*(ground|battery|b\+|vbatt)\b", r"\(.*?\)", r"[\"'/,:\-]", r"\s+\d+\s*$",
]


def stem(en, ru, fam):
    s = (en or "").lower()
    for p in STRIP:
        s = re.sub(p, " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    if len(s) >= 6:
        return s
    # нет английского (свои коды) — по семейству справочника, иначе по русскому названию до двоеточия
    if fam:
        return "fam:" + fam
    r = (ru or "").split(":")[0].lower()
    r = re.sub(r"\b(банк|датчик|цилиндр|зонд)\s*\d+", "", r)
    return "ru:" + r.strip()


def main():
    from lib import CODES  # noqa  — справочник (через gen импортируется всё)
    import gen  # noqa: F401
    from brands import BRANDS
    op = json.load(io.open(os.path.join(ASSETS, "dtc_open.json"), encoding="utf-8"))
    items = []  # (series, code, brand, ru, en, fam)
    for code, v in CODES.items():
        if v.get("e"):
            continue
        items.append(("sae", code, "", v.get("t", ""), "", v.get("f", "")))
    for code, (ru, en) in op["g"].items():
        items.append(("sae", code, "", ru, en, ""))
    for code, ru in op["v"].items():
        items.append(("vag5", code, "vag", ru, "", ""))
    for brand, codes in op["b"].items():
        for code, (ru, en) in codes.items():
            items.append(("brand", code, brand, ru, en, ""))
    for brand, codes in BRANDS.items():
        for code, v in codes.items():
            if v.get("e") or any(c == code and b == brand for _, c, b, *_ in items):
                continue
            items.append(("brand", code, brand, v.get("t", ""), "", v.get("f", "")))

    groups = {}
    for series, code, brand, ru, en, fam in items:
        key = (series, brand, stem(en, ru, fam))
        groups.setdefault(key, []).append(dict(code=code, brand=brand, ru=ru, en=en, fam=fam))
    # порядок: по серии, марке, первому коду узла
    order = sorted(groups.items(), key=lambda kv: (kv[0][0], kv[0][1], min(x["code"] for x in kv[1])))

    out = os.path.join(HERE, "batches")
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))
    index = []
    cur, cur_key, n = [], None, {}
    def flush():
        if not cur:
            return
        series, brand = cur_key
        n[series] = n.get(series, 0) + 1
        name = "%s%s_%03d" % (series, ("-" + brand) if brand else "", n[series])
        nodes = sorted({x["node"] for x in cur})
        json.dump(dict(name=name, series=series, brand=brand, nodes=nodes, codes=cur),
                  io.open(os.path.join(out, name + ".json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        index.append(dict(name=name, series=series, brand=brand, n=len(cur), nodes=len(nodes)))
        cur.clear()
    for (series, brand, st), lst in order:
        key = (series, brand)
        if cur and (key != cur_key or len(cur) + len(lst) > SIZE and len(cur) >= SIZE * 0.6):
            flush()
        cur_key = key
        for x in sorted(lst, key=lambda x: x["code"]):
            x["node"] = st
            cur.append(x)
            if len(cur) >= SIZE * 1.3:   # огромный узел (одинаковые VAG-строки) всё же режем
                flush()
                cur_key = key
        if len(cur) >= SIZE:
            flush()
    flush()
    json.dump(index, io.open(os.path.join(HERE, "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    by = {}
    for i in index:
        by[i["series"]] = by.get(i["series"], 0) + i["n"]
    print("кодов без разбора: %d; пачек: %d; по сериям: %s" % (len(items), len(index), by))


if __name__ == "__main__":
    main()
