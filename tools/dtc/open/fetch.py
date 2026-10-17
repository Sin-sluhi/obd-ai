"""Открытые источники кодов → кеш вне репозитория (нормализованный английский), для перевода и сборки.

    python tools/dtc/open/fetch.py <кеш>

1. Wal33D/dtc-database (MIT, © 2024 Waleed Judah): data/source-data/*.txt «КОД - Описание».
   Общие коды берём только из диапазонов SAE J2012 (P0, P2, P34, B0, B3, C0, C3, U0, U3): в их «generic»
   попали заводские P1xxx Ford — такие как общие не годятся. Марочные — по таблицам марок, «other» пропускаем
   (марка неизвестна).
2. domnulvlad/KLineKWP1281Lib (GPL-3.0): src/fault_code_description_EN.h — пятизначные коды VAG (KWP1281/KWP2000)
   → название узла. В приложение их английский текст не идёт: только наш русский пересказ (tools/dtc/open/ru_vag5_*.tsv).

Выход: <кеш>/wal33d.json {"generic": {код: en}, "brands": {наш_ключ: {код: en}}}, <кеш>/vag5.json {"00181": en}.
"""
import json, os, re, sys, urllib.request

cache = sys.argv[1]
os.makedirs(cache, exist_ok=True)

WAL = "https://raw.githubusercontent.com/Wal33D/dtc-database/main/data/source-data/"
# файл марки → ключ нашей марочной таблицы (Catalog.brandKey); первая в списке марка — главная при расхождениях
BRAND_FILES = {
    "hyundai_kia": ["kia"], "toyota": ["toyota", "lexus"], "vag": ["volkswagen", "audi"],
    "ford": ["ford", "lincoln", "mercury"], "gm": ["gm", "chevy", "buick", "cadillac", "gmc", "pontiac", "saturn", "oldsmobile", "geo"],
    "nissan": ["nissan", "infiniti"], "honda": ["honda", "acura"], "bmw": ["bmw"], "mazda": ["mazda"],
    "chrysler": ["chrysler", "dodge", "jeep", "plymouth"], "mitsubishi": ["mitsubishi"], "subaru": ["subaru"],
    "mercedes": ["mercedes"], "jaguar": ["jaguar"], "suzuki": ["suzuki"],
}
GENERIC_FILES = ["p_codes", "b_codes", "c_codes", "u_codes"]
SAE = re.compile(r"^(P0|P2|P34|B0|B3|C0|C3|U0|U3)")
LINE = re.compile(r"^([PBCU][0-9A-F]{4})\s*-\s*(.+?)\s*$")


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def parse(text):
    out = {}
    for line in text.splitlines():
        m = LINE.match(line.strip())
        if m and m.group(1) not in out:
            out[m.group(1)] = re.sub(r"\s+", " ", m.group(2))
    return out


generic = {}
for f in GENERIC_FILES:
    for c, d in parse(get(WAL + f + ".txt")).items():
        if SAE.match(c):
            generic.setdefault(c, d)
brands = {}
for key, files in BRAND_FILES.items():
    table = {}
    for f in files:
        for c, d in parse(get(WAL + f + "_codes.txt")).items():
            # марочная таблица — только не-SAE коды: стандартный код марка не переопределяет
            if not SAE.match(c):
                table.setdefault(c, d)
    brands[key] = table
json.dump({"generic": generic, "brands": brands}, open(os.path.join(cache, "wal33d.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=0)

h = get("https://raw.githubusercontent.com/domnulvlad/KLineKWP1281Lib/main/src/fault_code_description_EN.h")
vag = {}
for m in re.finditer(r'KWP_FAULT_([0-9A-F]{4})\[\]\s*PROGMEM\s*=\s*"(.*?)";', h):
    n = int(m.group(1), 16)
    if 16384 <= n <= 18407:
        continue   # это P0xxx/P1xxx — переводятся алгоритмом (ModuleDecoder.vagCode) и ищутся в таблицах P-кодов
    vag["%05d" % n] = m.group(2)
json.dump(vag, open(os.path.join(cache, "vag5.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print("общих SAE:", len(generic), "марочных:", {k: len(v) for k, v in brands.items()}, "VAG пятизначных:", len(vag))
