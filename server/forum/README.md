# Сервер форума OBD AI

Один файл на стандартной библиотеке Python, база SQLite. Никаких зависимостей.

## Как работает сейчас: домашняя машина Мирослава (с 2026-09-18)

Сервер крутится на его Windows-компьютере: папка `C:\Users\Mira\obd-ai-server`, скрипт `forum.ps1` (в автозагрузке через
`obdai-forum.vbs`, без окна, лог `forum.log`). Наружу — туннель localhost.run по SSH (`ssh -R 80:127.0.0.1:8080 nokey@localhost.run`,
без аккаунта, HTTPS). Адрес вида `https://<случайно>.lhr.life` меняется при каждом переподключении, поэтому скрипт публикует
его в `docs/forum_url.txt` через GitHub API, а приложение читает этот файл при запуске и при входе в чат (`ForumLocator`).
Cloudflare Tunnel не подошёл: провайдер режет DNS argotunnel.com. Ограничения: чат жив, пока компьютер включён и не спит;
после смены адреса приложению нужно до 5 минут (кэш raw.githubusercontent). База — `forum.db` в той же папке.

## Самый короткий путь: автодеплой из GitHub

1. Любой VPS с Ubuntu/Debian (Timeweb, Beget, Selectel, Aeza — от ~150 ₽/мес) и домен с A-записью на него
   (подойдёт поддомен вроде `forum.мойдомен.ru`).
2. В репозитории GitHub → Settings → Secrets and variables → Actions:
   - секреты `FORUM_SSH_HOST` (IP сервера), `FORUM_SSH_USER` (пользователь с sudo, например `root`), `FORUM_SSH_KEY` (приватный SSH-ключ);
   - переменные `FORUM_DOMAIN` (`forum.мойдомен.ru`) и `FORUM_URL` (`https://forum.мойдомен.ru`).
3. Actions → «Deploy forum» → Run workflow. Он поставит сервер, nginx, HTTPS и сам пересоберёт APK с адресом форума.

Дальше каждая правка `server/forum/` разворачивается автоматически.

## Запасной вариант без VPS: Cloudflare Workers (бесплатно, нужен аккаунт)

Папка `worker/`: тот же API на Workers + D1. Лимит бесплатного плана 100 000 запросов в день — на первые десятки
пользователей хватит. Команды в шапке `worker/worker.js`; адрес воркера — в переменную `FORUM_URL`.

## Запуск на VPS вручную

```bash
sudo mkdir -p /opt/obdai /var/lib/obdai
sudo cp server/forum/server.py /opt/obdai/
sudo useradd -r -s /usr/sbin/nologin obdai || true
sudo chown -R obdai:obdai /var/lib/obdai
```

`/etc/systemd/system/obdai-forum.service`:

```ini
[Unit]
Description=OBD AI forum
After=network.target

[Service]
User=obdai
ExecStart=/usr/bin/python3 /opt/obdai/server.py --host 127.0.0.1 --port 8080 --db /var/lib/obdai/forum.db
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now obdai-forum
curl -s http://127.0.0.1:8080/api/health
```

## HTTPS через nginx

```nginx
server {
    listen 443 ssl;
    server_name forum.example.ru;
    # ssl_certificate ... (certbot)
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 30s;
    }
}
```

Android без HTTPS ходить на сервер не станет (cleartext запрещён), поэтому HTTPS обязателен.

## Подключение приложения

В репозитории GitHub → Settings → Variables добавить `FORUM_URL` = `https://forum.example.ru` и пересобрать APK.
Для проверки без пересборки адрес можно ввести в скрытых настройках разработчика (7 нажатий на версию).

## Что хранится

`messages` — ветка, устройство (случайный UUID из приложения), ник, текст, время. `presence` — кто когда последний раз
заглядывал в ветку; «онлайн» = запрос за последние 90 секунд. Ограничения: 1000 символов, 1 сообщение в 2 секунды с устройства.
