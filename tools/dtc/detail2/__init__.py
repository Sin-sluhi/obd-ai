# -*- coding: utf-8 -*-
"""Подробная база, вторая волна: развёрнутые записи для всех кодов, у которых было только название.

Файлы s_<пачка>.py дописывают DETAIL2[(серия, марка, код)] — поля те же, что в detail/ (e, sy, c, ck, d, x, rk, p, s, stop).
Серия: sae — стандартные коды (марка пустая), vag5 — пятизначные VAG (марка vag), brand — заводские коды марки.
Правила написания — PROMPT.md, проверка — validate.py, пачки — batches/ (build_batches.py).
"""
import importlib
import os
import pkgutil
import re

DETAIL2 = {}
REQUIRED = ("e", "sy", "c", "ck", "d", "x", "rk", "p", "s", "stop")

def put(series, brand, code, **rec):
    key = (series, brand or "", code)
    assert key not in DETAIL2, "повтор записи %s" % (key,)
    DETAIL2[key] = rec


_here = os.path.dirname(os.path.abspath(__file__))
for _m in sorted(m.name for m in pkgutil.iter_modules([_here]) if re.match(r"s_", m.name)):
    importlib.import_module("%s.%s" % (__name__, _m))
