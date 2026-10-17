"""Собирает марочные параметры из OBDb (github.com/OBDb, лицензия CC BY-SA 4.0) в assets приложения.

    python tools/obdb/fetch.py <кеш>           # наборы параметров по моделям
    python tools/obdb/fetch_support.py <кеш>   # подтверждения с живых машин по годам
    python tools/obdb/build.py <кеш>           # → app/src/main/assets/obdb/

Правило базы — только уверенное. В приложение идут только команды и сигналы, которые OBDb подтвердил на живой машине
конкретного года (tests/test_cases/<год>/command_support.yaml). Пометка `dbg` («ещё отлаживается») и всё, что ни на одной
машине не подтверждено, отбрасываются. Только чтение: сервисы 21/22. Отбрасываются команды, которым нужна другая
диагностическая сессия (din/dout), K-line (proto/baud/init), чужой адрес тестера или приоритет (tst/pri).

Выход: obdb/index.json (модели: марка, модель, годы с подтверждениями, поколения) и obdb/<Repo>.json по модели —
блоки (адрес → тип) и команды с сигналами в компактном виде (см. Obdb.kt). Переработка данных OBDb остаётся под
CC BY-SA 4.0, авторство — в index.json и в настройках приложения.
"""
import json, os, re, sys
from collections import defaultdict

cache = sys.argv[1]
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = os.path.join(root, "app", "src", "main", "assets", "obdb")
os.makedirs(out, exist_ok=True)
for f in os.listdir(out):
    if f != "names_ru.json":   # переводы названий ведутся отдельно (names_ru.py)
        os.remove(os.path.join(out, f))

SKIP_KEYS = {"din", "dout", "proto", "baud", "init", "tst", "pri"}
stats = defaultdict(int)


def read_support(d):
    """{год: {(hdr, rax|None, cmd): set(сигналов) | None}} — None: команда подтверждена без списка сигналов."""
    res = {}
    for fn in os.listdir(d):
        m = re.match(r"support_(\d{4})\.yaml$", fn)
        if not m:
            continue
        year = int(m.group(1))
        ecu = None
        got = {}
        for line in open(os.path.join(d, fn), encoding="utf-8"):
            s = line.rstrip()
            k = re.match(r'^\s+"?([0-9A-Fa-f]{3,4})"?:\s*$', s)
            if k:
                ecu = k.group(1).upper(); continue
            it = re.match(r"^\s+-\s+(\S+)", s)
            if not it or ecu is None:
                continue
            key, _, sigs = it.group(1).partition(":")
            parts = key.upper().split(".")
            if len(parts) == 1:
                hdr, rax, cmd = ecu, None, parts[0]
            elif len(parts) == 2:
                hdr, rax, cmd = parts[0], None, parts[1]
            else:
                hdr, rax, cmd = parts[0], parts[1], parts[2]
            got[(hdr, rax, cmd)] = set(sigs.split(",")) if sigs else None
        res[year] = got
    return res


def generations(d):
    """Поколения из generations.yaml: [(начало, конец|None, имя)]."""
    p = os.path.join(d, "generations.yaml")
    if not os.path.exists(p):
        return []
    gens, cur = [], {}
    for line in open(p, encoding="utf-8"):
        m = re.match(r'^\s*-\s*name:\s*"?(.*?)"?\s*$', line)
        if m:
            if cur: gens.append(cur)
            cur = {"name": m.group(1)}; continue
        m = re.match(r"^\s*(start_year|end_year):\s*(\d{4}|null)?\s*$", line)
        if m and cur is not None:
            cur[m.group(1)] = int(m.group(2)) if m.group(2) and m.group(2) != "null" else None
    if cur: gens.append(cur)
    return [[g.get("start_year"), g.get("end_year"), g.get("name", "")] for g in gens if g.get("start_year")]


def compact_fmt(f):
    """Формат сигнала строкой полей: бит, длина, флаги (s — знаковый, l — младший байт первым), mul, div, add, min, max,
    nullmin, nullmax. Значение = raw * mul / div + add; вне [min, max] или на null-границе — нет значения."""
    flags = ("s" if f.get("sign") else "") + ("l" if f.get("blsb") else "")
    v = [f.get("bix", 0), f["len"], flags, f.get("mul", 1), f.get("div", 1), f.get("add", 0),
         f.get("min"), f.get("max"), f.get("nullmin"), f.get("nullmax")]
    while v and v[-1] is None: v.pop()   # хвост из пустых границ не пишем
    return v


names, units, groups = {}, {}, {}
def _intern(table, v):
    if v not in table: table[v] = len(table)
    return table[v]
intern = lambda v: _intern(names, v)
intern_unit = lambda v: _intern(units, v)
# группа — первый уровень пути OBDb (Engine, Battery, Tires…): на экране параметры собраны по узлам
intern_group = lambda v: _intern(groups, v.split(".")[0])

index = []
for repo in sorted(os.listdir(cache)):
    d = os.path.join(cache, repo)
    sp = os.path.join(d, "default.json")
    if not os.path.exists(sp):
        continue
    try:
        sigset = json.load(open(sp, encoding="utf-8"))
    except Exception:
        stats["bad_json"] += 1; continue
    support = read_support(d)
    stats["models_total"] += 1
    stats["signals_total"] += sum(len(c.get("signals", [])) for c in sigset.get("commands", []))
    if not support:
        stats["models_no_support"] += 1
        continue
    cmds = []
    for c in sigset.get("commands", []):
        (svc, pid), = c["cmd"].items()
        if svc not in ("21", "22"):
            continue
        stats["cmd_brand"] += 1
        if SKIP_KEYS & c.keys():
            stats["cmd_skipped_session"] += 1; continue
        hdr = c["hdr"].upper(); rax = c.get("rax", "").upper() or None
        full = (svc + pid).upper()
        years, sigs_ok = [], set()
        for y, got in support.items():
            for key in ((hdr, rax, full), (hdr, None, full)):
                if key in got:
                    years.append(y)
                    sigs_ok |= {s["id"] for s in c["signals"]} if got[key] is None else got[key]
                    break
        if not years:
            stats["cmd_unconfirmed"] += 1; continue
        dbg_years = c.get("dbgfilter")
        if c.get("dbg") and not dbg_years:
            # «отлаживается» без ограничения по годам — доверяем только подтверждённым сигналам; сами подтверждения
            # получены на машинах, поэтому команда остаётся, но с пометкой
            pass
        signals = []
        for s in c["signals"]:
            if s["id"] not in sigs_ok:
                stats["sig_unconfirmed"] += 1; continue
            f = s["fmt"]
            if "len" not in f or f.get("map"):
                stats["sig_enum_skipped"] += 1; continue   # перечисления (положение селектора и т. п.) — позже
            signals.append([intern(s.get("name", s["id"])), intern_unit(f.get("unit", "")), intern_group(s.get("path", "")),
                            *compact_fmt(f)])
        if not signals:
            continue
        flt = c.get("filter")
        cmd = {"h": hdr, "c": full, "y": sorted(set(years)), "s": signals}
        if rax: cmd["r"] = rax
        if c.get("eax"): cmd["e"] = c["eax"].upper()
        if c.get("fcm1"): cmd["f"] = 1
        if flt: cmd["flt"] = flt
        cmds.append(cmd)
        stats["cmd_confirmed"] += 1
        stats["sig_confirmed"] += len(signals)
    ecus = [[e["hdr"].upper(), e.get("type", "")] for e in sigset.get("ecu", [])]
    if not cmds and not ecus:
        continue
    make, _, model = repo.partition("-")
    gens = generations(d)
    json.dump({"ecu": ecus, "cmd": cmds}, open(os.path.join(out, repo + ".json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    index.append({"r": repo, "mk": make, "md": model.replace("-", " "), "y": sorted(support), "g": gens,
                  "n": sum(len(c["s"]) for c in cmds)})
    stats["models_out"] += 1
    stats["ecu_names"] += len(ecus)

json.dump({"source": "OBDb (github.com/OBDb), CC BY-SA 4.0 — переработано OBD AI: только подтверждённые на машинах команды",
           "names": list(names), "units": list(units), "groups": list(groups), "models": index}, open(os.path.join(out, "index.json"), "w", encoding="utf-8"),
          ensure_ascii=False, separators=(",", ":"))
size = sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out))
stats["asset_kb"] = size // 1024
stats["models_with_params"] = sum(1 for m in index if m["n"])
for k, v in sorted(stats.items()):
    print(f"{k}: {v}")
