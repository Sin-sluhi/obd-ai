# Сервер форума OBD AI

Один файл на стандартной библиотеке Python, база SQLite. Никаких зависимостей.

## Запуск на VPS

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
