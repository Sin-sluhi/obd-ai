# -*- coding: utf-8 -*-
"""Проверка подробной базы на полноту и объём. Запуск: python tools/dtc/detail/validate.py [файл.py ...]

Правило владельца: описания развёрнутые и практичные, короткие отписки не годятся. Поэтому здесь пороги по объёму
и обязательные поля; запись, которая их не проходит, считается недописанной.
"""
import io
import os
import re
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

MIN = {"e": 350, "d": 200, "rk": 80}
MIN_LIST = {"sy": 2, "c": 3, "ck": 3, "x": 1}
LEVELS = {"low", "medium", "high"}
CODE = re.compile(r"^[PBCU][0-9A-F]{4}$")


def check_entry(code, e, brand_overlay=False):
    errs = []
    if not CODE.match(code):
        errs.append("неверный код")
    if brand_overlay:
        # марочное дополнение к общей записи: обязателен свой текст марки и хотя бы причины или проверки
        if len(e.get("e", "")) < 150:
            errs.append("марочный текст e короче 150 символов")
        if not (e.get("c") or e.get("ck")):
            errs.append("нет марочных причин или проверок")
        return errs
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
    if not isinstance(e.get("p", None), int):
        errs.append("p — целое число рублей")
    if not isinstance(e.get("stop", None), bool):
        errs.append("stop — True/False")
    return errs


def main(files):
    from detail import DETAIL, BRAND_DETAIL  # загружает все pNN_*.py
    import gen  # noqa: F401  — справочник CODES/BRANDS
    from lib import CODES
    from brands import BRANDS
    bad = 0
    for code, e in DETAIL.items():
        if code not in CODES:
            print("%s: кода нет в общем справочнике (марочный код пишется в BRAND_DETAIL)" % code); bad += 1; continue
        for err in check_entry(code, e):
            print("%s: %s" % (code, err)); bad += 1
    for brand, codes in BRAND_DETAIL.items():
        for code, e in codes.items():
            # полная марочная запись (все обязательные поля) проверяется как полная, даже если есть общая
            full = all(e.get(k) for k in ("e", "c", "ck", "d"))
            overlay = code in DETAIL and not full
            if not overlay and code not in BRANDS.get(brand, {}) and code not in CODES:
                print("%s/%s: кода нет ни в марочной таблице, ни в общем справочнике" % (brand, code)); bad += 1; continue
            for err in check_entry(code, e, brand_overlay=overlay):
                print("%s/%s: %s" % (brand, code, err)); bad += 1
    total = len(DETAIL) + sum(len(v) for v in BRAND_DETAIL.values())
    print("записей: %d, замечаний: %d" % (total, bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
