# -*- coding: utf-8 -*-
"""Собирает справочник кодов в app/src/main/assets/dtc_ru.json и болячки в known_issues.json.

Запуск: python tools/dtc/gen.py  (из корня репозитория или из tools/dtc)
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from families import F  # noqa: E402
import codes_p0, codes_p2, codes_bcu  # noqa: E402,F401
from lib import CODES  # noqa: E402
from brands import BRANDS  # noqa: E402
from issues import ISSUES  # noqa: E402

ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets"))


def check():
    pat = re.compile(r"^[PBCU][0-9A-F]{4}$")
    for c in CODES:
        assert pat.match(c), c
    # ссылки r → существующие коды (предупреждение, не ошибка: брендовые могут ссылаться на брендовые)
    missing = set()
    for c, e in CODES.items():
        for other in e.get("r", {}):
            if other not in CODES:
                missing.add(other)
    for brand, codes in BRANDS.items():
        for c, e in codes.items():
            for other in e.get("r", {}):
                if other not in CODES and other not in codes:
                    missing.add(other)
    if missing:
        print("предупреждение: ссылки на неизвестные коды:", sorted(missing))
    # связи семейств
    for k, fam in F.items():
        for other in fam["links"]:
            assert other in F, (k, other)
    # болячки: коды существуют где-то
    for it in ISSUES:
        for c in it["codes"]:
            if c not in CODES and not any(c in b for b in BRANDS.values()):
                print("предупреждение: болячка «%s» ссылается на неизвестный код %s" % (it["title"], c))


def merge_links():
    """Дополнительные связи семейств (links_extra.py) — в обе стороны, своя формулировка семейства важнее."""
    from links_extra import LINKS
    added = 0
    for (a, b), reason in LINKS.items():
        assert a in F and b in F, "неизвестное семейство в связи %s — %s" % (a, b)
        for x, y in ((a, b), (b, a)):
            if y not in F[x]["links"]:
                F[x]["links"][y] = reason
                added += 1
    print("дополнительных связей семейств: %d" % added)


def merge_detail():
    """Подробная база (detail/) поверх справочника. Общая запись кода дополняет CODES; для марки собирается полная
    запись: справочник + общая подробная + марочная, где у марки текст и причины идут первыми."""
    from detail import DETAIL, BRAND_DETAIL, REQUIRED
    for code, d in DETAIL.items():
        assert code in CODES, "подробная запись для неизвестного кода %s" % code
        miss = [k for k in REQUIRED if not d.get(k)]
        assert not miss, "%s: не заполнено %s" % (code, miss)
        CODES[code].update(d)
    for brand, codes in BRAND_DETAIL.items():
        table = BRANDS.setdefault(brand, {})
        for code, b in codes.items():
            base = dict(CODES.get(code) or table.get(code) or {})
            assert base, "марочная запись %s/%s без кода в справочнике" % (brand, code)
            base.update(table.get(code, {}))
            g = DETAIL.get(code, {})
            rec = dict(base)
            rec.update({k: v for k, v in g.items()})
            if all(b.get(k) for k in REQUIRED):
                # полная марочная запись (Лада, УАЗ/ГАЗ): она самодостаточна, общая лишь заполняет пропущенные поля
                rec.update(b)
                table[code] = rec
                continue
            if b.get("e"):
                rec["e"] = b["e"] + ("\n\n" + g["e"] if g.get("e") else "")
            for k in ("c", "ck", "x", "sy"):
                if b.get(k):
                    rec[k] = list(dict.fromkeys(b[k] + list(g.get(k, []))))
            for k in ("d", "rk", "p", "s", "stop"):
                if k in b:
                    rec[k] = b[k]
            table[code] = rec
    nb = sum(len(v) for v in BRAND_DETAIL.values())
    print("подробная база: общих записей %d, марочных %d" % (len(DETAIL), nb))


def main():
    merge_links()
    check()
    families = {}
    for k, f in F.items():
        families[k] = {"t": f["title"], "s": f["story"], "c": f["causes"], "d": f["do"], "sev": f["severity"],
                       "cf": f["confirm"], "stop": f["stop"], "l": f["links"], "p": f["price"]}
    merge_detail()
    data = {"version": 1, "families": families, "codes": CODES, "brands": BRANDS}
    os.makedirs(ASSETS, exist_ok=True)
    out = os.path.join(ASSETS, "dtc_ru.json")
    with io.open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    out2 = os.path.join(ASSETS, "known_issues.json")
    with io.open(out2, "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"version": 1, "issues": ISSUES}, fh, ensure_ascii=False, separators=(",", ":"))
    nb = sum(len(v) for v in BRANDS.values())
    print("семейств: %d, стандартных кодов: %d, марочных: %d (%s), болячек: %d" % (
        len(F), len(CODES), nb, ", ".join("%s %d" % (k, len(v)) for k, v in BRANDS.items()), len(ISSUES)))
    print("%s: %d КБ; %s: %d КБ" % (out, os.path.getsize(out) // 1024, out2, os.path.getsize(out2) // 1024))


if __name__ == "__main__":
    main()
