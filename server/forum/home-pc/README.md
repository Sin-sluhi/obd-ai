# Форум и облачный гараж на домашнем ПК, переезд на другую машину

Сервер форума и облачного гаража — один файл `server/forum/server.py` (только стандартная библиотека Python + SQLite).
На домашнем ПК его держит `forum.ps1`: поднимает сервер на 127.0.0.1:8080, пробрасывает наружу через
`ssh -R … nokey@localhost.run` (HTTPS-адрес вида `https://xxxx.lhr.life`, без аккаунта) и публикует адрес
в `docs/forum_url.txt` репозитория через `gh api`. Приложение читает этот файл при запуске и при входе в чат.

## Что лежит где

| Папка | Что это |
|---|---|
| `obd-ai\` | репозиторий целиком, с историей git и `CLAUDE.md` |
| `obd-ai-server\forum.db` | база: сообщения форума, «кто онлайн», облачный гараж |
| `obd-ai-server\forum.ps1` | сторож сервера и туннеля (копия `server/forum/home-pc/forum.ps1`) |
| `obd-ai-server\obdai-forum.vbs` | запуск без окна, копируется в автозагрузку |
| `obd-ai-server\forum.log` | журнал сторожа |
| `obd-ai-server\cloudflared.exe` | не используется (у провайдера режется DNS argotunnel.com), оставлен на всякий случай |
| `claude-memory\` | память Claude Code по проекту |

Пути в `forum.ps1` не зашиты: он ждёт репозиторий рядом, в `..\obd-ai`, либо в переменной окружения `OBDAI_REPO`.

## Переезд

1. Воткнуть флешку, запустить `install.ps1` из её корня:
   `powershell -ExecutionPolicy Bypass -File D:\obd-ai-move\install.ps1` (буква диска своя).
   Скрипт копирует репозиторий и сервер в папку пользователя, память Claude в профиль Claude,
   кладёт `obdai-forum.vbs` в автозагрузку и проверяет, есть ли python, git, gh и ssh.
2. `gh auth login` — войти в GitHub (токен не переносится).
3. На старой машине убрать `obdai-forum.vbs` из автозагрузки и закрыть powershell/python форума,
   иначе два сервера будут по очереди перезаписывать `docs/forum_url.txt`.
4. Запустить форум: `wscript C:\Users\<имя>\obd-ai-server\obdai-forum.vbs`. Через минуту в `forum.log`
   должно появиться «опубликован адрес https://…lhr.life», а `https://…lhr.life/api/health` отвечать `{"ok": true}`.
5. Открыть папку `obd-ai` в Claude Code. Секреты (`AI_API_KEY`, ключ Groq) живут в настройках репозитория
   на GitHub, локально ничего настраивать не нужно. Сборка APK по-прежнему только в GitHub Actions.

## Что не переносится

- Токен `gh` и приватный ключ SSH: заново `gh auth login`; ключ для туннеля не нужен (`nokey@localhost.run`).
- `forum.db-wal`/`forum.db-shm`: перед копированием база сведена в один файл `forum.db`.

Скрипт обязан быть в UTF-8 с BOM, иначе PowerShell 5.1 ломает кириллицу в логе и в сообщении коммита.
