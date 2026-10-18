"""Сборка открытого слоя кодов → app/src/main/assets/dtc_open.json.

    python tools/dtc/open/build.py <кеш>      (кеш — из fetch.py)

Слой открытых баз лежит под своей подробной базой (dtc_ru.json) и только заполняет пробелы: код, которого нет у
нас, получает название узла и неисправности. Объяснение, причины и порядок ремонта для таких кодов даёт разбор нейронки
(KbFirst считает их непокрытыми — подробной записи нет).

- Wal33D/dtc-database (MIT, © 2024 Waleed Judah): русское название из ru/*.tsv, где перевода ещё нет — английское.
- Пятизначные VAG: только наш русский пересказ названия узла и типа сбоя (ru/vag5_*.tsv), английский текст источника
  (KLineKWP1281Lib, GPL-3.0) в приложение не попадает. Коды без перевода не включаются.
Формат: {"g": {код: [ru|null, en]}, "b": {марка: {код: [ru|null, en]}}, "v": {код: ru}}.
"""
import glob, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
cache = sys.argv[1]
wal = json.load(open(os.path.join(cache, "wal33d.json"), encoding="utf-8"))
vag = json.load(open(os.path.join(cache, "vag5.json"), encoding="utf-8"))
ours = json.load(open(os.path.join(ROOT, "app", "src", "main", "assets", "dtc_ru.json"), encoding="utf-8"))


def tsv(pattern):
    out = {}
    for f in sorted(glob.glob(os.path.join(HERE, "ru", pattern))):
        for line in open(f, encoding="utf-8"):
            line = line.rstrip("\n")
            if "\t" not in line or line.startswith("#"):
                continue
            code, ru = line.split("\t", 1)
            if ru.strip():
                out[code.strip()] = ru.strip()
    return out


ru_g = tsv("sae_*.tsv")
ru_b = tsv("brand_*.tsv")     # ключ «марка:код»
ru_v = tsv("vag5_*.tsv")

g = {c: [ru_g.get(c), en] for c, en in sorted(wal["generic"].items()) if c not in ours["codes"]}
# Таблицы марок у Wal33D во многом копии друг друга (Chrysler ≈ GM, Lincoln ≈ Ford): перевод с тем же английским
# названием берётся у уже переведённой марки, чтобы не пересказывать одно и то же дважды
by_en = {}
for key, table in wal["brands"].items():
    for c, en in table.items():
        ru = ru_b.get(f"{key}:{c}")
        if ru:
            by_en.setdefault(en.strip().lower(), ru)
b = {}
for key, table in wal["brands"].items():
    have = ours["brands"].get(key, {})
    rows = {c: [ru_b.get(f"{key}:{c}") or by_en.get(en.strip().lower()), en] for c, en in sorted(table.items()) if c not in have}
    if rows:
        b[key] = rows
v = {c: ru_v[c] for c in sorted(vag) if c in ru_v}

out = os.path.join(ROOT, "app", "src", "main", "assets", "dtc_open.json")
json.dump({"src": "Wal33D/dtc-database (MIT, © 2024 Waleed Judah); названия VAG — пересказ OBD AI", "g": g, "b": b, "v": v},
          open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
gt = sum(1 for x in g.values() if x[0])
bt = sum(1 for t in b.values() for x in t.values() if x[0])
bn = sum(len(t) for t in b.values())
print(f"общих новых: {len(g)} (по-русски {gt}); марочных новых: {bn} (по-русски {bt}); VAG пятизначных: {len(v)} из {len(vag)}; "
      f"файл {os.path.getsize(out) // 1024} КБ")
