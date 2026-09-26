# Инструкция для Claude: поднять OBD AI на новой машине

Ты читаешь это, потому что проект переехал с флешки на новый компьютер. Владелец — Мирослав, общение по-русски.
Задача: сделать так, чтобы всё работало как на старой машине. Ничего сверх этого не строить, пока не попросят.
Выполняй по порядку, после каждого шага проверяй результат, не переходи дальше при ошибке.

## Из чего состоит проект и что где крутится

| Часть | Где работает | Кто запускает |
|---|---|---|
| Android-приложение (APK) | GitHub Actions `build.yml`, сборка при каждом пуше в `main` | GitHub сам. Локально Android SDK нет и не нужен |
| Форум + облачный гараж (`server/forum/server.py`) | **этот компьютер**, 127.0.0.1:8080, наружу через туннель localhost.run | `obd-ai-server\forum.ps1` из автозагрузки Windows |
| Публикация адреса туннеля в `docs/forum_url.txt` | этот компьютер, внутри `forum.ps1` через `gh api` | тот же скрипт |
| База опыта владельцев `docs/kb.json` | GitHub Actions `kb.yml`, ночью 02:00 UTC, по 10 пар | GitHub сам |
| Секреты (`AI_API_KEY` = ключ Groq) | настройки репозитория на GitHub | уже заданы, локально не нужны |

Других серверов нет. `deploy-forum.yml` и `server/forum/worker/` — заготовки под VPS и Cloudflare, они выключены и не нужны.

## Шаг 0. Где что лежит на флешке

`D:\obd-ai-move\` (буква диска может быть другой, найди папку `obd-ai-move`):
- `obd-ai\` — репозиторий с историей git;
- `obd-ai-server\` — `forum.db` (база форума и гаража), `forum.ps1`, `forum.log`, `cloudflared.exe` (не используется);
- `claude-memory\` — память Claude по проекту;
- `install.ps1`, `README.md`, этот файл.

## Шаг 1. Проверить инструменты

```powershell
python --version      # нужен 3.9+; если нет: winget install Python.Python.3.12  (или python.org, галочка Add to PATH)
git --version         # если нет: winget install Git.Git
gh --version          # если нет: winget install GitHub.cli
ssh -V                # если нет: Параметры → Приложения → Дополнительные компоненты → Клиент OpenSSH
```

После установки чего-либо через winget открой новую консоль, иначе PATH старый. `gh` иногда не в PATH в Git Bash:
`export PATH="$PATH:/c/Program Files/GitHub CLI"`.

## Шаг 2. Скопировать проект

Запусти установщик с флешки (он копирует репозиторий и сервер в папку пользователя, память в профиль Claude,
кладёт запуск форума в автозагрузку):

```powershell
powershell -ExecutionPolicy Bypass -File D:\obd-ai-move\install.ps1
```

Проверь, что получилось:
- `%USERPROFILE%\obd-ai\CLAUDE.md` существует, `git -C %USERPROFILE%\obd-ai status` чистый, `git log -1` показывает
  коммит «server: переносимые скрипты…» или новее;
- `%USERPROFILE%\obd-ai-server\forum.db` и `forum.ps1` на месте;
- `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\obdai-forum.vbs` есть и внутри правильный путь к `forum.ps1`;
- память: `%USERPROFILE%\.claude\projects\<C--Users-<имя>-obd-ai>\memory\MEMORY.md`. Имя папки = путь к репозиторию,
  где `:` и `\` заменены на `-`. Если Claude Code открыт в другой папке, перенеси память в соответствующую.

Если `git` ругается «dubious ownership» на флешке, это нормально: работай с копией в папке пользователя, а не с флешкой.

## Шаг 3. Войти в GitHub

```powershell
gh auth login
```
Выбирать: GitHub.com → HTTPS → Login with a web browser (или токен, если браузер недоступен). Проверка:

```powershell
gh auth status
gh api repos/Sin-sluhi/obd-ai --jq .full_name        # → Sin-sluhi/obd-ai
gh secret list -R Sin-sluhi/obd-ai                   # → AI_API_KEY должен быть в списке
git -C %USERPROFILE%\obd-ai pull --rebase origin main # должно пройти без пароля (gh настроит credential helper; если нет: gh auth setup-git)
```

## Шаг 4. Убедиться, что старый компьютер больше не сервер

Иначе два `forum.ps1` будут по очереди перезаписывать `docs/forum_url.txt`, и чат в приложении будет то работать, то нет.
Спроси у владельца, выключен ли старый ПК или удалён ли там `obdai-forum.vbs` из автозагрузки. Признак спора:
`gh api repos/Sin-sluhi/obd-ai/commits?path=docs/forum_url.txt --jq '.[0:5][].commit.message'` показывает свежие
коммиты «forum: адрес туннеля» не от этой машины.

## Шаг 5. Запустить форум и туннель

Не ждать перезагрузки:

```powershell
wscript "%USERPROFILE%\obd-ai-server\obdai-forum.vbs"
```

Через 60–90 секунд проверь:

```powershell
Get-Content "$env:USERPROFILE\obd-ai-server\forum.log" -Tail 10
```
Ожидаемые строки по порядку: «старт (репозиторий: …)», «запускаю сервер», «туннель: https://xxxx.lhr.life»,
«опубликован адрес https://xxxx.lhr.life». Затем:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health                       # → ok=True, messages=N
$u = (gh api repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt --jq .content | % { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }).Trim()
$u                                                                       # → https://xxxx.lhr.life, тот же адрес, что в логе
Invoke-RestMethod "$u/api/health"
```
Второй запрос должен вернуть то же `ok=True` через туннель. Приложение читает этот файл из GitHub при запуске и при входе
в чат, ничего в APK менять не нужно.

Если что-то не так:
- «запускаю сервер» повторяется, а health не отвечает: смотри `obd-ai-server\server.err` (обычно нет python в PATH
  для процессов автозагрузки или занят порт 8080);
- «туннель не поднялся»: смотри `tunnel.err`; первый раз ssh спросит про ключ хоста — в скрипте стоит `accept-new`,
  но если провайдер режет SSH на 22 порт, localhost.run недоступен, тогда сообщи владельцу, нужен другой туннель или VPS
  (`server/forum/README.md`, `deploy.sh`);
- «не удалось опубликовать адрес»: `gh auth status`, шаг 3;
- в логе кракозябры: `forum.ps1` потерял BOM, пересохрани в UTF-8 with BOM (`utf-8-sig`).

## Шаг 6. Проверить, что облако GitHub живёт своей жизнью

```powershell
gh run list -R Sin-sluhi/obd-ai --workflow build.yml -L 3   # последняя сборка должна быть success
gh run list -R Sin-sluhi/obd-ai --workflow kb.yml -L 3      # ночные запуски базы опыта
gh release view latest -R Sin-sluhi/obd-ai --json assets --jq '.assets[].name'   # app-debug.apk
```
Ссылка на APK для владельца: https://github.com/Sin-sluhi/obd-ai/releases/download/latest/app-debug.apk

## Шаг 7. Проверить с телефона

Попроси владельца открыть приложение → вкладка «Форум» → любую ветку: должно показать «онлайн» и дать отправить
сообщение. Потом на главном «Гараж» → «Что в облаке»: должна вернуться его машина (в базе одна запись гаража).
Если чат пишет «ещё не подключён», подожди минуту и выйди-зайди в ветку: приложение перечитывает адрес при ошибках.

## Шаг 8. Открыть проект в Claude Code

Открой папку `%USERPROFILE%\obd-ai`. `CLAUDE.md` — главный документ: правила продукта, архитектура, грабли, открытые
задачи. Память подхватится из `.claude\projects\...\memory`. Первым делом обнови в памяти путь к репозиторию,
если он отличается от `C:\Users\Mira\obd-ai`.

## Правила работы, которые легко забыть на новой машине

- Компилятор только в CI: после каждого изменения `git push` и `gh run watch <id> --exit-status`.
- Перед пушем всегда `git pull --rebase origin main`: бот `kb.yml` и `forum.ps1` коммитят в `main` сами.
- Пуши, где меняются только `.md`/`.txt`/`docs/`, сборку не запускают.
- В Bash-инструменте heredoc с кириллицей ломается: скрипты писать через Write в короткий путь и запускать файлом.
- `robocopy` в Git Bash не понимает пути `/c/...`, только `C:\...`; в PowerShell 5.1 нет `&&`.
- В интерфейсе приложения нет слов Claude/Anthropic, только «OBIDI AI». В блоки машины ничего не пишем.
- Владелец не платит за иностранные сервисы: Groq бесплатный, ключ в секрете репозитория.
