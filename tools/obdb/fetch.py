"""Скачивает наборы параметров OBDb (github.com/OBDb, CC BY-SA 4.0) в кеш вне репозитория.

python tools/obdb/fetch.py <папка-кеша>
Каждая модель: <кеш>/<Repo>/default.json (+ generations.yaml). Список репозиториев — через `gh api`.
"""
import json, os, subprocess, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
gh = r"C:\Program Files\GitHub CLI\gh.exe" if os.name == "nt" else "gh"
names = subprocess.run([gh, "api", "orgs/OBDb/repos?per_page=100", "--paginate", "--jq", ".[].name"],
                       capture_output=True, text=True, check=True).stdout.split()
names = [n for n in names if not n.startswith(".")]

def get(name):
    d = os.path.join(out, name)
    os.makedirs(d, exist_ok=True)
    got = 0
    for path, fn in (("signalsets/v3/default.json", "default.json"), ("generations.yaml", "generations.yaml")):
        dst = os.path.join(d, fn)
        if os.path.exists(dst):
            got += 1; continue
        try:
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/OBDb/{name}/main/{path}", timeout=30) as r:
                open(dst, "wb").write(r.read()); got += 1
        except Exception:
            pass
    return name, got

with ThreadPoolExecutor(16) as ex:
    res = list(ex.map(get, names))
print(len(names), "репозиториев;", sum(1 for _, g in res if g), "скачано")
