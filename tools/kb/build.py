# -*- coding: utf-8 -*-
"""Наполняет docs/kb.json — общую базу опыта владельцев по парам «машина × код».

Поиск свой (тот же, что в приложении, ForumSearch.kt): DuckDuckGo html → Bing, только записи с drive2.ru / drom.ru,
скачиваем страницу и вырезаем текст вокруг кода. Модель (Groq openai/gpt-oss-120b, при неудаче qwen/qwen3.8-27b)
получает выдержки и отдаёт JSON-выжимку: причины, что помогло, цены. В sources попадают только адреса, которые
реально скачали и показали модели, — придумать ссылку модель не может. Записи без выдержек не сохраняются.
groq/compound с собственным поиском отключён 21.09.2026, поэтому от поиска провайдера не зависим.

Запуск (GitHub Action или локально):
  GROQ_API_KEY=... python tools/kb/build.py --limit 25 [--max-age-days 90] [--car "Hyundai Solaris"]
"""
import argparse
import base64
import datetime as dt
import html as htmllib
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from targets import TARGETS  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
KB_PATH = os.path.join(ROOT, "docs", "kb.json")
API = "https://api.groq.com/openai/v1/chat/completions"
# Бесплатный тариф Groq: 8 тыс. токенов в минуту на модель (считаются вместе с max_tokens), 200 тыс. в день.
# Выдержки держим в пределах NOTES_BUDGET символов, ответ до 700 токенов, между парами пауза (--pause).
MODELS = [m for m in (os.environ.get("KB_MODEL") or "openai/gpt-oss-120b,qwen/qwen3.8-27b").split(",") if m]
FORUMS = ("drive2.ru", "drom.ru")
NOTES_BUDGET = 4500
NOTES_BUDGET_TIGHT = 1800
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

SYSTEM = """Ты — опытный автодиагност. Тебе дают марку/модель машины, код ошибки OBD-II и выдержки из записей владельцев
на drive2.ru и drom.ru (каждая с адресом). Выжми из них факты про ИМЕННО эту модель и этот код: что оказалось причиной,
что реально помогло, что меняли зря, сколько это стоило. Одиночные догадки без результата не считай. Не добавляй ничего,
чего нет в выдержках. Ответ строго в JSON без markdown:
{
  "summary": "2–4 предложения по-русски: что у владельцев оказалось причиной и что помогло; пустая строка, если в выдержках нет ничего по делу",
  "causes": ["причины по убыванию частоты у владельцев"],
  "fixes": ["что реально помогло"],
  "wasted": ["что меняли зря"],
  "price_from": 0,
  "price_to": 0,
  "mileage": "на каком пробеге обычно, или пустая строка",
  "sources": ["адреса выдержек, из которых взяты факты"]
}
Цены в рублях по упоминаниям владельцев; неизвестно — 0."""

ISSUES_SYSTEM = """Ты — опытный автодиагност. Тебе дают марку и модель и выдержки из записей владельцев на drive2.ru и drom.ru
(каждая с адресом). Выпиши, на что владельцы ИМЕННО этой модели жалуются чаще всего (двигатель, коробка, электрика, ходовая),
с пробегом, на котором это обычно случается, и что делали. Только повторяющиеся проблемы, не единичные случаи, и только то,
что есть в выдержках. Ответ строго в JSON без markdown:
{
  "problems": [{"issue": "что ломается и как проявляется, 1 фраза", "mileage": "на каком пробеге", "fix": "что делали", "price_from": 0}],
  "sources": ["адреса выдержек, из которых взяты факты"]
}
Не больше 8 проблем, по убыванию частоты. Цены в рублях по упоминаниям владельцев; неизвестно — 0."""


# ---------- база ----------

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


# ---------- свой поиск (зеркало ForumSearch.kt) ----------

def http_get(url, max_bytes=300000, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru-RU,ru;q=0.9",
                                               "Accept": "text/html,application/xhtml+xml"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read(max_bytes).decode("utf-8", "replace")


class Blocked(Exception):
    """Поисковик показал капчу: этот IP он считает ботом, дальше его в этом запуске не трогаем."""


def search_ddg(query):
    page = http_get("https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(query))
    if "bots use DuckDuckGo" in page or "challenge" in page[:20000] and "result__a" not in page:
        raise Blocked("duckduckgo")
    out = []
    for m in re.finditer(r'class="result__a"[^>]*href="([^"]+)"', page):
        href = htmllib.unescape(m.group(1))
        if href.startswith("//"):
            href = "https:" + href
        u = re.search(r"[?&]uddg=([^&]+)", href)
        if u:
            href = urllib.parse.unquote(u.group(1))
        if href.startswith("http") and href not in out:
            out.append(href)
    return out


def search_bing(query):
    page = http_get("https://www.bing.com/search?mkt=ru-RU&cc=RU&setlang=ru&q=" + urllib.parse.quote(query))
    if '<li class="b_algo"' not in page:
        raise Blocked("bing")
    out = []
    # в каждом результате ссылка в заголовке <h2><a …> либо в плашке сайта <a class="tilk" …>, обе через bing.com/ck/a
    for block in page.split('<li class="b_algo"')[1:]:
        m = re.search(r'<h2>\s*<a[^>]*href="([^"]+)"', block) or re.search(r'class="tilk"[^>]*href="([^"]+)"', block)
        if not m:
            continue
        href = htmllib.unescape(m.group(1))
        if "bing.com/ck/a" in href:
            u = re.search(r"[?&]u=a1([^&]+)", href)
            if u:
                s = u.group(1)
                try:
                    href = base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode("utf-8", "replace")
                except Exception:  # noqa: BLE001
                    href = ""
        if href.startswith("http") and href not in out:
            out.append(href)
    return out


BLOCKED = set()
KEY = ""                     # ключ Groq, задаёт main(); нужен третьему поисковику
SEARCH_MODEL = "openai/gpt-oss-20b"   # у каждой модели свой дневной лимит: поиск ссылок на 20b, выжимка на 120b
SEARCH_PAUSE = 6.0           # DuckDuckGo ставит капчу на серию быстрых запросов с одного IP


def search_groq(query):
    """Третий поисковик: встроенный browser_search у Groq gpt-oss. Просим только адреса записей на drive2/drom;
    страницы всё равно скачиваем и проверяем сами, так что модель не может подсунуть выдуманную ссылку."""
    if not KEY:
        raise Blocked("groq")
    body = {"model": SEARCH_MODEL, "temperature": 0, "max_tokens": 1500, "reasoning_effort": "low",
            "tool_choice": "required", "tools": [{"type": "browser_search"}],
            "messages": [{"role": "user", "content":
                          "Найди в интернете записи владельцев на drive2.ru и drom.ru по запросу «%s». "
                          "Ответь только списком полных адресов найденных страниц (до 6 штук), по одному в строке, без пояснений." % query}]}
    req = urllib.request.Request(API, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY,
                                          "User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=240) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        body = ex.read()[:300].decode("utf-8", "replace")
        if ex.code == 429 and ("per day" in body or "TPD" in body or "RPD" in body):
            raise Blocked("groq")
        raise
    text = (data.get("choices", [{}])[0].get("message", {}).get("content") or "")
    out = []
    for u in re.findall(r"https?://[^\s\]\)>\"'«»]+", text):
        u = u.rstrip(".,;:")
        if u.startswith("http") and u not in out:
            out.append(u)
    return out


def search(query):
    """Ссылки из выдачи: DuckDuckGo → Bing → browser_search Groq. Между запросами пауза, чтобы не выглядеть ботом."""
    for fn in (search_ddg, search_bing, search_groq):
        if fn.__name__ in BLOCKED:
            continue
        if fn is not search_groq:
            time.sleep(SEARCH_PAUSE)
        try:
            res = fn(query)
        except Blocked as ex:
            print("  поисковик %s недоступен (капча или лимит), больше его не спрашиваю" % ex)
            BLOCKED.add(fn.__name__)
            res = []
        except Exception as ex:  # noqa: BLE001
            print("  поисковик %s: %s" % (fn.__name__, str(ex)[:120]))
            res = []
        if res:
            return res
    return []


def page_text(url):
    page = http_get(url, max_bytes=600000)
    text = re.sub(r"(?is)<(script|style|noscript|svg|header|nav|footer)[^>]*>.*?</\1>", " ", page)
    text = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</li>|</h[1-6]>", "\n", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = htmllib.unescape(text)
    text = re.sub(r"[ \t\x0b\f\r]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n", text)
    return text.strip()


def excerpt(url, anchor, window):
    """Текст страницы без разметки, окно вокруг первого упоминания anchor (кода или модели)."""
    text = page_text(url)
    if len(text) < 200:
        return ""
    idx = text.lower().find(anchor.lower())
    if idx < 0:
        idx = 0
    start = max(idx - window // 5, 0)
    return text[start:start + window].strip()


def research_notes(queries, anchor, budget, want=3):
    """Ссылки с форумов по запросам и выдержки из них. Возвращает (текст для модели, [адреса])."""
    links = []
    for q in queries:
        if len(links) >= want:
            break
        for url in search(q):
            if any(f in url for f in FORUMS) and url not in links and len(links) < want:
                links.append(url)
    notes = []
    used = []
    total = 0
    for url in links:
        try:
            ex = excerpt(url, anchor, max(700, min(2800, budget // want)))
        except Exception:  # noqa: BLE001
            continue
        # страница должна реально говорить про этот код/модель, иначе ссылка не подтверждена
        if not ex or anchor.lower() not in ex.lower():
            continue
        notes.append("Источник: %s\n%s\n" % (url, ex))
        used.append(url)
        total += len(ex)
        if total > budget:
            break
    return "\n".join(notes), used


# ---------- модель ----------

def chat(key, model, system, user, max_tokens):
    body = {"model": model, "temperature": 0.2, "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    if model.startswith("openai/gpt-oss"):
        body["reasoning_effort"] = "low"   # размышление тратит токены ответа, а минутный лимит 8 тыс.
    # Cloudflare перед Groq режет стандартный User-Agent urllib (error code 1010): представляемся по-человечески
    req = urllib.request.Request(API, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key,
                                          "User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=240) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return (data.get("choices", [{}])[0].get("message", {}).get("content") or "").strip()


def extract_json(text):
    text = text.strip()
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except ValueError:
        return None


class RateLimit(Exception):
    def __init__(self, body):
        super().__init__(body)
        self.body = body


def ask_json(key, system, user_full, user_tight, max_tokens):
    """Спрашивает модели по очереди; при 429 повторяет с короткими выдержками, при дневном лимите бросает RateLimit."""
    last = None
    for model in MODELS:
        for user in (user_full, user_tight):
            try:
                data = extract_json(chat(key, model, system, user, max_tokens))
                if data is not None:
                    return data
                last = "модель %s вернула не JSON" % model
                break
            except urllib.error.HTTPError as ex:
                body = ex.read()[:400].decode("utf-8", "replace")
                if ex.code == 429:
                    if "per day" in body or "TPD" in body or "RPD" in body:
                        raise RateLimit(body)
                    m = re.search(r"try again in ([0-9.]+)s", body)
                    wait = min(max((float(m.group(1)) if m else 30) + 2, 20), 120)
                    print("  429 у %s, жду %.0f с" % (model, wait))
                    time.sleep(wait)
                    last = body[:200]
                    continue   # та же модель, короткие выдержки
                last = "%s: %s" % (ex.code, body[:200])
                break
    raise RuntimeError(last or "нет ответа")


# ---------- пары ----------

def research(key, car, code):
    brand, model = split_car(car)
    if code == "ISSUES":
        queries = ["%s болячки site:drive2.ru" % car, "%s проблемы владельцев site:drom.ru" % car, "%s типичные проблемы отзывы" % car]
        notes, used = research_notes(queries, model or brand, NOTES_BUDGET, want=4)
        if not used:
            return None
        head = "Машина: %s.\n\n=== Выдержки с форумов ===\n" % car
        data = ask_json(key, ISSUES_SYSTEM, head + notes, head + notes[:NOTES_BUDGET_TIGHT], 900)
        problems = [p for p in data.get("problems") or [] if isinstance(p, dict) and p.get("issue")]
        if not problems:
            return None
        causes, fixes = [], []
        for p in problems[:8]:
            issue = str(p.get("issue", "")).strip()
            mileage = str(p.get("mileage", "")).strip()
            causes.append(issue + (" (%s)" % mileage if mileage else ""))
            if p.get("fix"):
                fixes.append(str(p["fix"]).strip())
        sources = [u for u in data.get("sources") or [] if isinstance(u, str) and u in used] or used
        return {
            "car": car, "brand": brand, "model": model, "code": "ISSUES",
            "summary": "Типичные проблемы по отзывам владельцев: " + "; ".join(causes[:4]) + ".",
            "causes": causes, "fixes": fixes[:6], "wasted": [], "price_from": 0, "price_to": 0, "mileage": "",
            "sources": sources[:4], "updated": dt.date.today().isoformat(),
        }

    queries = ["%s %s site:drive2.ru" % (code, car), "%s %s site:drom.ru" % (code, car), "%s %s ошибка форум" % (code, car)]
    notes, used = research_notes(queries, code, NOTES_BUDGET)
    if not used:
        return None
    head = "Машина: %s. Код: %s.\n\n=== Выдержки с форумов ===\n" % (car, code)
    data = ask_json(key, SYSTEM, head + notes, head + notes[:NOTES_BUDGET_TIGHT], 800)
    if not (data.get("summary") or "").strip():
        return None
    sources = [u for u in data.get("sources") or [] if isinstance(u, str) and u in used] or used
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
    ap.add_argument("--limit", type=int, default=25, help="сколько пар обработать за запуск")
    ap.add_argument("--max-age-days", type=int, default=90, help="обновлять записи старше N дней")
    ap.add_argument("--car", default=None, help="только эта машина")
    ap.add_argument("--pause", type=float, default=45.0, help="пауза между парами, с (минутный лимит токенов)")
    args = ap.parse_args()

    key = os.environ.get("GROQ_API_KEY") or os.environ.get("AI_API_KEY")
    if not key:
        print("нет ключа: задайте GROQ_API_KEY", file=sys.stderr)
        sys.exit(2)
    global KEY
    KEY = key

    kb = load_kb()
    index = {(e["car"], e["code"]): e for e in kb["entries"]}
    today = dt.date.today()

    def stale(e):
        try:
            return (today - dt.date.fromisoformat(e["updated"])).days > args.max_age_days
        except Exception:  # noqa: BLE001
            return True

    queue = []
    for car, codes in TARGETS:
        if args.car and args.car.lower() != car.lower():
            continue
        for code in ["ISSUES"] + list(codes):   # болячки модели — раньше кодов: они нужны «Перед покупкой»
            e = index.get((car, code))
            if e is None:
                queue.append((0, car, code))       # новых — первыми
            elif stale(e):
                queue.append((1, car, code))
    queue.sort(key=lambda x: x[0])
    queue = queue[: args.limit]
    print("в очереди пар: %d (всего в базе %d)" % (len(queue), len(kb["entries"])))

    done = added = failed = 0
    for _, car, code in queue:
        if len(BLOCKED) >= 3:
            print("все поисковики недоступны: останавливаюсь, остальное — в следующий запуск")
            break
        try:
            entry = research(key, car, code)
        except RateLimit as ex:
            print("дневной лимит: %s" % ex.body[:200])
            break
        except Exception as ex:  # noqa: BLE001
            print("ошибка для %s %s: %s" % (car, code, ex))
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
