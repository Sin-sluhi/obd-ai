"""Сверка нашего разбора марочных параметров с тестами OBDb: сырые ответы ELM327 с живых машин и ожидаемые значения.

    python tools/obdb/verify.py <кеш> [Repo ...]

Разбор здесь — тот же, что в Obdb.kt (бит, длина, порядок байтов, знак, mul/div/add, границы). Скачивает
tests/test_cases/<год>/commands/*.yaml по командам, попавшим в assets, считает совпадения и пишет выборку случаев
в app/src/test/resources/obdb_cases.json — их гоняет ObdbTest на стенде.
"""
import json, os, re, subprocess, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

cache = sys.argv[1]
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
assets = os.path.join(root, "app", "src", "main", "assets", "obdb")
index = json.load(open(os.path.join(assets, "index.json"), encoding="utf-8"))
repos = sys.argv[2:] or ["Hyundai-Tucson", "Kia-Sportage", "Kia-Rio", "Hyundai-Elantra", "Toyota-Corolla", "Toyota-RAV4",
                         "Volkswagen-Polo", "Volkswagen-Tiguan", "Audi-A4", "Skoda-Octavia", "BMW-3-Series", "Ford-Focus",
                         "Nissan-Qashqai", "Mazda-CX-5", "Renault-Duster", "Mitsubishi-Outlander", "Chevrolet-Cruze",
                         "Honda-Civic", "Subaru-Forester", "Lexus-RX", "Haval-Jolion", "Chery-Tiggo-7-Pro", "MINI-Cooper"]
gh = r"C:\Program Files\GitHub CLI\gh.exe" if os.name == "nt" else "gh"


def bits(data, bix, ln, lsb):
    nbytes = (bix % 8 + ln + 7) // 8
    start = bix // 8
    chunk = data[start:start + nbytes]
    if len(chunk) < nbytes:
        return None
    if lsb:
        chunk = chunk[::-1]
    v = int.from_bytes(bytes(chunk), "big")
    total = nbytes * 8
    shift = total - (bix % 8) - ln
    if lsb:
        shift = bix % 8 if False else total - (bix % 8) - ln
    return (v >> shift) & ((1 << ln) - 1)


def decode(data, f):
    bix, ln, flags, mul, div, add = f[:6]
    mn = f[6] if len(f) > 6 and f[6] is not None else 0   # у OBDb нижняя граница по умолчанию — 0
    mx = f[7] if len(f) > 7 else None
    raw = bits(data, bix, ln, "l" in flags)
    if raw is None:
        return None
    if "s" in flags and raw >= 1 << (ln - 1):
        raw -= 1 << ln
    v = raw * mul / div + add
    # как у OBDb: значение за границей прижимается к ней
    if mn is not None and v < mn: v = mn
    if mx is not None and v > mx: v = mx
    return v


def assemble(resp, rax):
    """Кадры ATH1 без пробелов: «7CE100F62B002E00000» → полезная нагрузка ISO-TP."""
    msg, need = [], None
    for line in resp.split():
        line = line.strip().upper()
        if not re.fullmatch(r"[0-9A-F]+", line):
            continue
        idlen = 8 if len(line) > 3 and line[:2] == "18" and len(line) >= 10 and rax and len(rax) > 3 else 3
        b = [int(line[i:i + 2], 16) for i in range(idlen, len(line) - 1, 2)]
        if len(b) < 2:
            continue
        pci = b[0] >> 4
        if pci == 0:
            return b[1:1 + b[0]]
        if pci == 1:
            need = ((b[0] & 0x0F) << 8) | b[1]; msg = b[2:]
        elif pci == 2 and need is not None:
            msg += b[1:]
    return msg[:need] if need else None


def tests_for(repo):
    r = subprocess.run([gh, "api", f"repos/OBDb/{repo}/git/trees/main?recursive=1", "--jq", ".tree[].path"],
                       capture_output=True, text=True)
    return [p for p in r.stdout.split() if "/commands/" in p and p.endswith(".yaml")]


def fetch(repo, path):
    d = os.path.join(cache, repo, "tests")
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, path.replace("/", "_"))
    if not os.path.exists(dst):
        try:
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/OBDb/{repo}/main/{path}", timeout=30) as r:
                open(dst, "wb").write(r.read())
        except Exception:
            return None
    return open(dst, encoding="utf-8").read()


def parse_cases(text):
    cases, cur, in_resp = [], None, False
    for line in text.splitlines():
        if line.startswith("- expected_values:") or line.startswith("- response:"):
            if cur: cases.append(cur)
            cur = {"exp": {}, "resp": ""}; in_resp = line.startswith("- response:"); continue
        if cur is None:
            continue
        m = re.match(r"^\s{4}([A-Za-z0-9_]+):\s*(.+)$", line)
        if m and not in_resp:
            try: cur["exp"][m.group(1)] = float(m.group(2))
            except ValueError: pass
            continue
        if re.match(r"^\s{2}response:", line):
            in_resp = True; continue
        if re.match(r"^\s{2}expected_values:", line):
            in_resp = False; continue
        if in_resp and line.strip():
            cur["resp"] += line.strip() + "\n"
    if cur: cases.append(cur)
    return cases


names = index["names"]
total = ok = 0
bad = []
sample = []
for repo in repos:
    p = os.path.join(assets, repo + ".json")
    sp = os.path.join(cache, repo, "default.json")
    if not os.path.exists(p) or not os.path.exists(sp):
        print(repo, "— нет в assets"); continue
    model = json.load(open(p, encoding="utf-8"))
    ids = {}
    for c in json.load(open(sp, encoding="utf-8"))["commands"]:
        (svc, pid), = c["cmd"].items()
        for s in c["signals"]:
            ids[(c["hdr"].upper(), (svc + pid).upper(), s.get("name", s["id"]), s["fmt"].get("bix", 0))] = s["id"]
    by_key = {}
    for c in model["cmd"]:
        by_key[(c["h"], c.get("r"), c["c"])] = c
    paths = tests_for(repo)
    want = []
    for path in paths:
        cid = os.path.basename(path)[:-5].upper().split(".")
        hdr, rax, cmd = (cid[0], None, cid[1]) if len(cid) == 2 else (cid[0], cid[1], cid[2])
        c = by_key.get((hdr, rax, cmd)) or by_key.get((hdr, None, cmd))
        if c: want.append((path, c))
    with ThreadPoolExecutor(8) as ex:
        texts = list(ex.map(lambda w: fetch(repo, w[0]), want))
    r_ok = r_tot = 0
    for (path, c), text in zip(want, texts):
        if not text:
            continue
        strip = 1 + (len(c["c"]) - 2) // 2
        for case in parse_cases(text):
            data = assemble(case["resp"], c.get("r"))
            if not data:
                continue
            for s in c["s"]:
                sid = ids.get((c["h"], c["c"], names[s[0]], s[3]))
                if sid not in case["exp"]:
                    continue
                got = decode(data[strip:], s[3:])
                exp = case["exp"][sid]
                r_tot += 1
                if got is not None and abs(got - exp) <= max(1e-3, abs(exp) * 1e-4):
                    r_ok += 1
                    if len(sample) < 400 and (len(sample) < 20 or r_ok % 25 == 0):
                        sample.append({"repo": repo, "c": c["c"], "h": c["h"], "resp": case["resp"], "fmt": s[3:],
                                       "strip": strip, "exp": exp})
                else:
                    bad.append((repo, c["c"], names[s[0]], s[3:], exp, got))
    total += r_tot; ok += r_ok
    print(f"{repo}: {r_ok}/{r_tot}")
print(f"ИТОГО совпало {ok} из {total}")
print("неверное число:", sum(1 for b in bad if b[5] is not None), "нет значения:", sum(1 for b in bad if b[5] is None))
for b in [x for x in bad if x[5] is not None][:12]:
    print("  ≠", b)
json.dump(sample, open(os.path.join(root, "app", "src", "test", "resources", "obdb_cases.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=0)
