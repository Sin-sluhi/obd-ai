# -*- coding: utf-8 -*-
"""Подробная база кодов: правило «сначала база» (KbFirst.kt) берёт разбор отсюда, нейронка — только для того, чего тут нет.

Каждый файл pNN_*.py дописывает записи в DETAIL (общие для всех марок) и BRAND_DETAIL[марка] (дополнения марки).
Формат и эталон качества — p01_pilot.py. gen.py сливает их со справочником: запись кода получает поля
e, sy, c, ck, d, x, rk, p, s, stop; марочная запись складывается из общей и марочной (марочное — первым).
"""
import importlib
import os
import pkgutil
import re

DETAIL = {}
BRAND_DETAIL = {"lada": {}, "uaz_gaz": {}, "hyundai_kia": {}, "toyota": {}, "vag": {}, "ford": {}, "gm": {}, "nissan": {}, "honda": {}}

REQUIRED = ("e", "c", "ck", "d")

_here = os.path.dirname(os.path.abspath(__file__))
for _m in sorted(m.name for m in pkgutil.iter_modules([_here]) if re.match(r"p\d+_", m.name)):
    importlib.import_module("%s.%s" % (__name__, _m))
