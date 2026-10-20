# -*- coding: utf-8 -*-
"""Проверка пачек второй волны. Запуск: python tools/dtc/detail2/validate.py [имя_пачки ...]

Что проверяется для каждой пачки batches/<имя>.json ↔ s_<имя>.py:
  - каждый код пачки описан, лишних кодов нет;
  - объём и обязательные поля (пороги как в detail/validate.py);
  - внутри одного узла (поле node) цена p и серьёзность s одинаковые — писатель не должен «плавать»;
  - в тексте нет английских кусков длиннее 25 букв подряд (признак непереведённого названия);
  - число в поле p кратно 100, не меньше 300.
Код выхода 1, если есть замечания.
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

MIN = {"e": 350, "d": 200, "rk": 80}
MIN_LIST = {"sy": 2, "c": 3, "ck": 3, "x": 1}
LEVELS = {"low", "medium", "high"}
LATIN = re.compile(r"[A-Za-z][A-Za-z ,.'/-]{25,}")


def check_entry(e):
    errs = []
    for k, n in MIN.items():
        if len(e.get(k, "")) < n:
            errs.append("%s короче %d символов (%d)" % (k, n, len(e.get(k, ""))))
    for k, n in MIN_LIST.items():
        if len(e.get(k, [])) < n:
            errs.append("%s: меньше %d пунктов" % (k, n))
    for k in ("c", "ck"):
        short = [x for x in e.get(k, []) if len(x) < 40]
        if short:
            errs.append("%s: слишком короткие пункты: %s" % (k, short[:2]))
    if e.get("s") not in LEVELS:
        errs.append("s должно быть low/medium/high")
    p = e.get("p")
    if not isinstance(p, int) or p < 300 or p % 100:
        errs.append("p — целое число рублей, кратное 100, не меньше 300")
    if not isinstance(e.get("stop"), bool):
        errs.append("stop — True/False")
    text = " ".join([e.get("e", ""), e.get("d", ""), e.get("rk", "")] + list(e.get("c", [])) + list(e.get("ck", [])) + list(e.get("x", [])) + list(e.get("sy", [])))
    m = LATIN.search(text)
    if m:
        errs.append("английский кусок в тексте: «%s…»" % m.group(0)[:30])
    return errs


def check_batch(name, detail):
    b = json.load(io.open(os.path.join(HERE, "batches", name + ".json"), encoding="utf-8"))
    errs = []
    want = {(b["series"], b["brand"], x["code"]): x for x in b["codes"]}
    have = {k: v for k, v in detail.items() if k[0] == b["series"] and k[1] == b["brand"] and k[2] in {x["code"] for x in b["codes"]}}
    for k in want:
        if k not in have:
            errs.append("%s: не описан" % k[2])
    for k, e in have.items():
        for err in check_entry(e):
            errs.append("%s: %s" % (k[2], err))
    by_node = {}
    for k, e in have.items():
        by_node.setdefault(want[k]["node"], []).append((k[2], e.get("p"), e.get("s")))
    for node, lst in by_node.items():
        ps = {p for _, p, _ in lst}
        ss = {s for _, _, s in lst}
        if len(ps) > 2:
            errs.append("узел «%s»: цена плавает: %s" % (node[:40], sorted(x for x in ps if x is not None)))
        if len(ss) > 1:
            errs.append("узел «%s»: серьёзность разная: %s" % (node[:40], sorted(x for x in ss if x)))
    return len(have), errs


def main(names):
    from detail2 import DETAIL2 as detail
    if not names:
        names = sorted(f[2:-3] for f in os.listdir(HERE) if f.startswith("s_") and f.endswith(".py"))
    total, bad = 0, 0
    for n in names:
        cnt, errs = check_batch(n, detail)
        total += cnt
        bad += len(errs)
        for e in errs:
            print("%s: %s" % (n, e))
    print("записей: %d, замечаний: %d" % (total, bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
