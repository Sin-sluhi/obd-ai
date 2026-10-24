# Справочник кодов для Windows-версии: те же данные, что в APK (assets/dtc_ru.json + dtc2 + открытый слой),
# собранные в один файл. Порядок поиска как в Catalog.kt: таблица марки → общие SAE → dtc2 марки → dtc2 SAE.
# Запуск: python tools/obdai-win/prep_catalog.py  →  tools/obdai-win/out/catalog.json.gz (вшивается в exe).
import gzip, json, os, re

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, '..', '..', 'app', 'src', 'main', 'assets')

def load(name):
    with open(os.path.join(ASSETS, name), encoding='utf-8') as f:
        return json.load(f)

main = load('dtc_ru.json')
codes = dict(main['codes'])
brands = {k: dict(v) for k, v in main['brands'].items()}

d2 = os.path.join(ASSETS, 'dtc2')
for fn in sorted(os.listdir(d2)):
    data = load('dtc2/' + fn)
    m = re.match(r'brand-(\w+?)_\w{3}\.json$', fn)
    if fn.startswith('sae_'):
        for c, e in data.items(): codes.setdefault(c, e)
    elif m:
        b = brands.setdefault(m.group(1), {})
        for c, e in data.items(): b.setdefault(c, e)
    elif fn.startswith('vag5-'):
        b = brands.setdefault('vag', {})
        for c, e in data.items(): b.setdefault(c, e)

op = load('dtc_open.json')
# открытый слой: только русское название (английское — запасное, если русского нет)
def title(row):
    if isinstance(row, list): return (row[0] or (row[1] if len(row) > 1 else '')) or ''
    return row or ''
open_g = {c: title(r) for c, r in op.get('g', {}).items()}
open_b = {b: {c: title(r) for c, r in t.items()} for b, t in op.get('b', {}).items()}
open_v = dict(op.get('v', {}))

out = {'families': main['families'], 'codes': codes, 'brands': brands,
       'open': {'g': open_g, 'b': open_b, 'v': open_v}, 'version': main.get('version', '')}
os.makedirs(os.path.join(ROOT, 'out'), exist_ok=True)
path = os.path.join(ROOT, 'out', 'catalog.json.gz')
with gzip.open(path, 'wt', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
n = len(codes) + sum(len(v) for v in brands.values())
print('codes', len(codes), 'brand', sum(len(v) for v in brands.values()), 'open', len(open_g), 'bytes', os.path.getsize(path))
