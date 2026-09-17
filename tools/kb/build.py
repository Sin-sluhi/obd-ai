# -*- coding: utf-8 -*-
"""Наполняет docs/kb.json — общую базу опыта владельцев по парам «машина × код».

Для каждой пары спрашивает Groq compound (встроенный поиск, только drive2.ru / drom.ru) и сохраняет краткую выжимку:
что у владельцев оказалось причиной, что помогло, цены, ссылки. Ссылки берутся только те, которые реально были
в результатах поиска (executed_tools), а не из головы модели. Записи без подтверждённых ссылок не сохраняются.

Запуск (GitHub Action или локально):
  GROQ_API_KEY=... python tools/kb/build.py --limit 60 [--max-age-days 90] [--car "Hyundai Solaris"]
"""
import argparse
import datetime as dt
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from targets import TARGETS  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
KB_PATH = os.path.join(ROOT, "docs", "kb.json")
API = "https://api.groq.com/openai/v1/chat/completions"
# compound-mini делает один вызов поиска за запрос: в разы меньше токенов, влезает в минутный лимит бесплатного тарифа
MODEL = os.environ.get("KB_MODEL") or "groq/compound-mini"
FORUMS = ("drive2.ru", "drom.ru")

SYSTEM = """Ты — опытный автодиагност. Тебе дают марку/модель машины и код ошибки OBD-II.
Найди на drive2.ru и drom.ru записи владельцев ИМЕННО этой модели про этот код и выжми из них факты: что оказалось причиной,
что реально помогло, что меняли зря, сколько это стоило. Одиночные догадки без результата не считай.
Ответ строго в JSON без markdown:
{
  "summary": "2–4 предложения по-русски: что у владельцев оказалось причиной и что помогло; пустая строка, если по этой модели ничего не нашёл",
  "causes": ["причины по убыванию частоты у владельцев"],
  "fixes": ["что реально помогло"],
  "wasted": ["что меняли зря"],
  "price_from": 0,
  "price_to": 0,
  "mileage": "на каком пробеге обычно, или пустая строка",
  "sources": ["адреса записей, которые ты реально открывал"]
}
Цены в рублях по упоминаниям владельцев; неизвестно — 0. Не выдумывай ссылки."""


def load_kb():
    if not os.path.exists(KB_PATH):
        return {"version": 1, "updated": "", "entries": []}
    with io.open(KB_PATH, encoding="utf-8") as fh:
        return json.load(fh)


def save_kb(kb):
    kb["updated"] = dt.date.today().isoformat()
    kb["entries"].sort(key=lambda e: (e["brand"], e["model"], e["code"]))
    os.makedirs(os.path.dirname(KB_PATH), exist_ok=True)
    with io.open(KB_PATH, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(kb, fh, ensure_ascii=False, indent=1)


def split_car(car):
    parts = car.split(" ", 1)
    return parts[0], (parts[1] if len(parts) > 1 else "")


def ask(key, car, code):
    body = {
        "model": MODEL,
        "temperature": 0.2,
        "max_tokens": 1000,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": "Машина: %s. Код: %s. Поищи «%s %s drive2» и «%s %s drom», прочитай записи и ответь JSON." % (car, code, code, car, code, car)},
        ],
        "search_settings": {"country": "Russia", "include_domains": ["drive2.ru", "*.drive2.ru", "drom.ru", "*.drom.ru"]},
    }
    # Cloudflare перед Groq режет стандартный User-Agent urllib (error code 1010): представляемся по-человечески
    req = urllib.request.Request(API, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key,
                                          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                                          "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.loads(resp.read().decode("utf-8"))


def extract_json(text):
    text = text.strip()
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except ValueError:
        return None


def seen_urls(resp):
    out = []
    msg = resp.get("choices", [{}])[0].get("message", {})
    for tool in msg.get("executed_tools") or []:
        results = (tool.get("search_results") or {}).get("results") or []
        for r in results:
            url = r.get("url") or ""
            if url and any(f in url for f in FORUMS):
                out.append((url, (r.get("title") or "") + " " + (r.get("content") or "")))
    return out


def research(key, car, code):
    resp = ask(key, car, code)
    msg = resp.get("choices", [{}])[0].get("message", {})
    data = extract_json(msg.get("content") or "")
    seen = seen_urls(resp)
    seen_set = {u for u, _ in seen}
    if not data or not (data.get("summary") or "").strip():
        return None
    sources = [u for u in data.get("sources") or [] if isinstance(u, str) and u in seen_set]
    if not sources:
        # модель не назвала ссылок, но поиск их видел: берём те, где упоминается код
        sources = [u for u, txt in seen if code.lower() in txt.lower()][:3]
    if not sources:
        return None
    brand, model = split_car(car)
    return {
        "car": car, "brand": brand, "model": model, "code": code,
        "summary": data["summary"].strip(),
        "causes": [c for c in data.get("causes") or [] if isinstance(c, str)][:6],
        "fixes": [c for c in data.get("fixes") or [] if isinstance(c, str)][:6],
        "wasted": [c for c in data.get("wasted") or [] if isinstance(c, str)][:4],
        "price_from": int(data.get("price_from") or 0), "price_to": int(data.get("price_to") or 0),
        "mileage": (data.get("mileage") or "").strip() if isinstance(data.get("mileage"), str) else "",
        "sources": sources[:4],
        "updated": dt.date.today().isoformat(),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=60, help="сколько пар обработать за запуск")
    ap.add_argument("--max-age-days", type=int, default=90, help="обновлять записи старше N дней")
    ap.add_argument("--car", default=None, help="только эта машина")
    ap.add_argument("--pause", type=float, default=20.0, help="пауза между парами, с (минутный лимит токенов)")
    args = ap.parse_args()

    key = os.environ.get("GROQ_API_KEY") or os.environ.get("AI_API_KEY")
    if not key:
        print("нет ключа: задайте GROQ_API_KEY", file=sys.stderr)
        sys.exit(2)

    kb = load_kb()
    index = {(e["car"], e["code"]): e for e in kb["entries"]}
    today = dt.date.today()

    def stale(e):
        try:
            return (today - dt.date.fromisoformat(e["updated"])).days > args.max_age_days
        except Exception:
            return True

    queue = []
    for car, codes in TARGETS:
        if args.car and args.car.lower() != car.lower():
            continue
        for code in codes:
            e = index.get((car, code))
            if e is None:
                queue.append((0, car, code))       # новых — первыми
            elif stale(e):
                queue.append((1, car, code))
    queue.sort(key=lambda x: x[0])
    queue = queue[: args.limit]
    print("в очереди пар: %d (всего в базе %d)" % (len(queue), len(kb["entries"])))

    done = added = failed = 0
    stop = False
    for _, car, code in queue:
        entry = None
        ok = False
        for attempt in range(5):
            try:
                entry = research(key, car, code)
                ok = True
                break
            except urllib.error.HTTPError as ex:
                body = ex.read()[:400].decode("utf-8", "replace")
                if ex.code == 429:
                    if "per day" in body or "TPD" in body or "RPD" in body:
                        print("дневной лимит: %s" % body[:200])
                        stop = True
                        break
                    m = re.search(r"try again in ([0-9.]+)s", body)
                    wait = float(m.group(1)) if m else float(ex.headers.get("retry-after") or 30)
                    wait = min(wait + 2, 180)
                    print("429 для %s %s, жду %.0f с (попытка %d)" % (car, code, wait, attempt + 1))
                    time.sleep(wait)
                    continue
                print("ошибка %s для %s %s: %s" % (ex.code, car, code, body[:200]))
                break
            except Exception as ex:  # noqa: BLE001
                print("ошибка для %s %s: %s" % (car, code, ex))
                break
        if stop:
            break
        if not ok:
            failed += 1
            time.sleep(args.pause)
            continue
        done += 1
        if entry:
            old = index.get((car, code))
            if old:
                kb["entries"].remove(old)
            kb["entries"].append(entry)
            index[(car, code)] = entry
            added += 1
            print("+ %s %s: %d ссылок" % (car, code, len(entry["sources"])))
        else:
            print("- %s %s: без подтверждённых записей" % (car, code))
        if done % 5 == 0:
            save_kb(kb)
        time.sleep(args.pause)

    save_kb(kb)
    print("обработано %d, сохранено %d, ошибок %d, всего записей %d" % (done, added, failed, len(kb["entries"])))
    if queue and done == 0:
        sys.exit(1)   # ни одного ответа — ключ, сеть или блокировка; пусть запуск будет красным


if __name__ == "__main__":
    main()
