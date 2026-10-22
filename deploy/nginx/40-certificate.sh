#!/bin/sh
# Какой сертификат отдаёт nginx. Настоящий (Let's Encrypt) появляется не сразу: certbot
# получает его, только когда nginx уже отвечает на порту 80. А без сертификата nginx
# не стартует на 443. Поэтому сначала — временный самоподписанный, а фоновый цикл
# каждые 5 секунд проверяет, не появился ли (или не продлился ли) настоящий, и
# перезагружает nginx без остановки. Проверка — один stat файла, это почти бесплатно.
set -eu

live="/etc/letsencrypt/live/$DENTAL_DOMAIN"
current=/etc/nginx/certs/current
dummy=/etc/nginx/certs/dummy

mkdir -p "$dummy"
if [ ! -f "$dummy/fullchain.pem" ]; then
	openssl req -x509 -nodes -newkey rsa:2048 -days 7 -subj "/CN=$DENTAL_DOMAIN" \
		-keyout "$dummy/privkey.pem" -out "$dummy/fullchain.pem" 2>/dev/null
fi

choose() {
	if [ -f "$live/fullchain.pem" ]; then
		ln -sfn "$live" "$current"
	else
		ln -sfn "$dummy" "$current"
	fi
}

choose
(
	seen=""
	while sleep 5; do
		# -L: live/ — ссылки на файлы в archive/, продление меняет именно их
		now="$(stat -L -c %Y "$live/fullchain.pem" 2>/dev/null || true)"
		if [ "$now" != "$seen" ]; then
			seen="$now"
			choose
			nginx -s reload 2>/dev/null || true
		fi
	done
) &
