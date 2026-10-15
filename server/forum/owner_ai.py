# -*- coding: utf-8 -*-
"""Разбор проверок для владельца через Claude Code на этом компьютере (подписка владельца).

Только для версии разработчика: приложение шлёт отчёт о машине с заголовком X-Owner-Key, сервер запускает
claude -p (встроенный в приложение Claude для Windows) и возвращает JSON-вердикт в той же схеме, что у нейронок
приложения. Покупательская сборка ключа не содержит и этот адрес не вызывает: подписка — личная, для владельца.

  POST /api/owner/analyze {"system","user"}  → {"content": "<JSON-вердикт>", "model", "seconds"}
  GET  /api/owner/status                    → {"ok", "claude", "loggedIn"} (тоже с ключом)

Ключ владельца — файл owner.key рядом с базой (создаётся сам) или переменная OBDAI_OWNER_KEY; тот же ключ лежит
в секрете GitHub OWNER_AI_KEY и вшивается только в версию разработчика.
"""
import glob
import hmac
import json
import os
import secrets
import shutil
import subprocess
import tempfile
import threading
import time

OWNER_KEY = ""
MODEL = os.environ.get("OBDAI_CLAUDE_MODEL", "opus")
EFFORT = os.environ.get("OBDAI_CLAUDE_EFFORT", "medium")
TIMEOUT = int(os.environ.get("OBDAI_CLAUDE_TIMEOUT", "420"))
_run_lock = threading.Lock()   # один разбор за раз: подписка одна, параллель только съест лимит


def configure(base_dir):
    global OWNER_KEY
    OWNER_KEY = os.environ.get("OBDAI_OWNER_KEY", "").strip()
    if OWNER_KEY:
        return
    f = os.path.join(base_dir, "owner.key")
    if os.path.exists(f):
        OWNER_KEY = open(f, encoding="utf-8").read().strip()
    if not OWNER_KEY:
        OWNER_KEY = secrets.token_urlsafe(24)
        with open(f, "w", encoding="utf-8") as h:
            h.write(OWNER_KEY + "\n")
        print("создан ключ владельца для разбора через Claude (файл %s)" % f)


def find_claude():
    """CLI из PATH, иначе свежая копия, встроенная в приложение Claude для Windows (как в FACTUM)."""
    p = os.environ.get("OBDAI_CLAUDE")
    if p:
        return p
    p = shutil.which("claude")
    if p:
        return p
    appdata = os.environ.get("APPDATA", "")
    local = os.environ.get("LOCALAPPDATA", "")
    found = glob.glob(os.path.join(appdata, "Claude", "claude-code", "*", "*", "claude.exe"))
    found += glob.glob(os.path.join(local, "Packages", "Claude_*", "LocalCache", "Roaming", "Claude",
                                    "claude-code", "*", "*", "claude.exe"))
    return max(found, key=os.path.getmtime) if found else None


def _clean_env():
    """Окружение без чужих переменных Claude Code: если сервер запущен из другой сессии Claude, унаследованные
    CLAUDE_CODE_* указывают на её сокет и авторизацию — claude.exe тогда считает, что вход не выполнен, или виснет."""
    return {k: v for k, v in os.environ.items() if not k.upper().startswith(("CLAUDE", "ANTHROPIC"))}


def _is_owner(h):
    key = h.headers.get("X-Owner-Key", "")
    return bool(OWNER_KEY and key and hmac.compare_digest(key.encode(), OWNER_KEY.encode()))


def _friendly(text):
    low = (text or "").lower()
    if "not logged in" in low or "/login" in low:
        return "Claude Code не залогинен на компьютере"
    if "limit" in low and ("usage" in low or "reached" in low or "reset" in low):
        return "Упёрлись в лимит подписки Claude: " + text.strip()[:160]
    return (text or "").strip()[:300] or "Claude Code завершился с ошибкой"


def analyze(system, user):
    exe = find_claude()
    if not exe:
        raise RuntimeError("Claude Code не найден на компьютере")
    cmd = [exe, "-p", "--no-session-persistence", "--setting-sources", "", "--strict-mcp-config",
           "--effort", EFFORT, "--output-format", "json",
           # опыт владельцев Claude ищет сам; файлов и команд компьютера ему не нужно
           "--tools", "WebSearch,WebFetch", "--allowedTools", "WebSearch", "WebFetch",
           "--append-system-prompt", system]
    if MODEL:
        cmd += ["--model", MODEL]
    started = time.time()
    with _run_lock, tempfile.TemporaryDirectory(prefix="obdai-claude-") as cwd:
        try:
            out = subprocess.run(cmd, cwd=cwd, input=user, capture_output=True, text=True, encoding="utf-8",
                                 errors="replace", timeout=TIMEOUT, env=_clean_env())
        except subprocess.TimeoutExpired:
            raise RuntimeError("Claude не уложился в %d с" % TIMEOUT)
    try:
        res = json.loads(out.stdout.strip().splitlines()[-1]) if out.stdout.strip() else {}
    except ValueError:
        res = {}
    if not res or res.get("is_error"):
        raise RuntimeError(_friendly(str(res.get("result") or out.stderr or out.stdout)))
    return {"content": res.get("result") or "", "model": MODEL, "seconds": round(time.time() - started)}


def handle_get(h, path):
    if not _is_owner(h):
        return h._json(401, {"error": "key"})
    if path == "/api/owner/status":
        exe = find_claude()
        logged = False
        if exe:
            try:
                st = subprocess.run([exe, "auth", "status"], capture_output=True, text=True, encoding="utf-8",
                                    errors="replace", timeout=30, env=_clean_env())
                logged = bool(json.loads(st.stdout or "{}").get("loggedIn"))
            except Exception:
                pass
        return h._json(200, {"ok": bool(exe and logged), "claude": bool(exe), "loggedIn": logged})
    return h._json(404, {"error": "not found"})


def handle_post(h, path, b):
    if not _is_owner(h):
        return h._json(401, {"error": "key"})
    if path == "/api/owner/analyze":
        system = (b.get("system") or "").strip()
        user = (b.get("user") or "").strip()
        if not system or not user:
            return h._json(400, {"error": "system/user"})
        try:
            return h._json(200, analyze(system, user))
        except RuntimeError as e:
            return h._json(503, {"error": str(e)})
    return h._json(404, {"error": "not found"})
