#!/usr/bin/env bash
# Установка/обновление сервера форума на Ubuntu/Debian VPS. Идемпотентно: можно запускать сколько угодно раз.
#   sudo bash deploy.sh forum.example.ru [email@для.certbot]
# Ставит python3, nginx, certbot; кладёт server.py в /opt/obdai; systemd-сервис obdai-forum; HTTPS через Let's Encrypt.
set -euo pipefail
DOMAIN="${1:?домен, например forum.example.ru}"
EMAIL="${2:-admin@$DOMAIN}"
HERE="$(cd "$(dirname "$0")" && pwd)"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq python3 nginx certbot python3-certbot-nginx > /dev/null

id -u obdai > /dev/null 2>&1 || useradd -r -s /usr/sbin/nologin obdai
mkdir -p /opt/obdai /var/lib/obdai
cp "$HERE/server.py" /opt/obdai/server.py
chown -R obdai:obdai /var/lib/obdai

cat > /etc/systemd/system/obdai-forum.service <<EOF
[Unit]
Description=OBD AI forum
After=network.target

[Service]
User=obdai
ExecStart=/usr/bin/python3 /opt/obdai/server.py --host 127.0.0.1 --port 8080 --db /var/lib/obdai/forum.db
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now obdai-forum
systemctl restart obdai-forum

cat > /etc/nginx/sites-available/obdai-forum <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 30s;
        proxy_set_header X-Forwarded-For \$remote_addr;
    }
    location / { return 404; }
}
EOF
ln -sf /etc/nginx/sites-available/obdai-forum /etc/nginx/sites-enabled/obdai-forum
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

# HTTPS: certbot сам допишет ssl в конфиг nginx (нужна A-запись домена на этот сервер)
if ! test -d "/etc/letsencrypt/live/$DOMAIN"; then
  certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect || echo "certbot не смог: проверь, что домен указывает на этот сервер; повтори deploy позже"
fi

sleep 1
curl -fsS "http://127.0.0.1:8080/api/health" && echo && echo "форум работает: https://$DOMAIN/api/health"
