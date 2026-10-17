"""Дополняет кеш OBDb подтверждениями с живых машин: tests/test_cases/<год>/command_support.yaml по каждой модели
(какие команды и сигналы реально ответили на машине этого года). python tools/obdb/fetch_support.py <папка-кеша>"""
import json, os, subprocess, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

out = sys.argv[1]
gh = r"C:\Program Files\GitHub CLI\gh.exe" if os.name == "nt" else "gh"

def get(name):
    d = os.path.join(out, name)
    mark = os.path.join(d, "support.done")
    if not os.path.exists(os.path.join(d, "default.json")) or os.path.exists(mark):
        return 0
    r = subprocess.run([gh, "api", f"repos/OBDb/{name}/git/trees/main?recursive=1", "--jq", ".tree[].path"],
                       capture_output=True, text=True)
    paths = [p for p in r.stdout.split() if p.endswith("/command_support.yaml")]
    n = 0
    for p in paths:
        year = p.split("/")[-2]
        try:
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/OBDb/{name}/main/{p}", timeout=30) as resp:
                open(os.path.join(d, f"support_{year}.yaml"), "wb").write(resp.read()); n += 1
        except Exception:
            pass
    if r.returncode == 0:
        open(mark, "w").write(str(n))
    return n

names = sorted(os.listdir(out))
with ThreadPoolExecutor(8) as ex:
    res = list(ex.map(get, names))
print("моделей с подтверждениями:", sum(1 for x in res if x), "файлов:", sum(res))
