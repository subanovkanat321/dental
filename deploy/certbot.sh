#!/bin/sh
# HTTPS-сертификат Let's Encrypt для nginx: получить, если его нет, и продлевать.
# Работает в контейнере certbot (compose.prod.yaml) бесконечным циклом. nginx сам
# замечает новый сертификат за несколько секунд (deploy/nginx/40-certificate.sh).
set -u

case "$DENTAL_DOMAIN" in
	localhost | *.localhost)
		# CI и проверки на своей машине: Let's Encrypt не выдаёт сертификаты на localhost,
		# nginx работает с временным самоподписанным
		echo "certbot: $DENTAL_DOMAIN — без Let's Encrypt"
		while :; do sleep 86400; done
		;;
esac

webroot=/var/www/acme
live="/etc/letsencrypt/live/$DENTAL_DOMAIN/fullchain.pem"

# Let's Encrypt проверяет домен, скачивая файл с http://<домен>/.well-known/acme-challenge/.
# Сначала убеждаемся сами, что nginx его отдаёт: обращаться к Let's Encrypt раньше —
# значит тратить попытки из лимита (5 неудачных проверок в час на домен)
nginx_serves_challenge() {
	mkdir -p "$webroot/.well-known/acme-challenge"
	token="selfcheck-$$"
	echo ok > "$webroot/.well-known/acme-challenge/$token"
	for _ in $(seq 1 60); do
		if [ "$(wget -q -T 2 -O - "http://nginx/.well-known/acme-challenge/$token" 2>/dev/null)" = ok ]; then
			rm -f "$webroot/.well-known/acme-challenge/$token"
			return 0
		fi
		sleep 1
	done
	rm -f "$webroot/.well-known/acme-challenge/$token"
	echo "certbot: nginx за минуту так и не отдал файл проверки — Let's Encrypt не трогаем"
	return 1
}

# Паузы после неудачной попытки получить сертификат: 1, 2, 5 минут, дальше по 15.
# Первые попытки — быстро (сайт ещё на временном сертификате), но в пределах лимита
failures=0
backoff() {
	case "$failures" in
		1) echo 60 ;;
		2) echo 120 ;;
		3) echo 300 ;;
		*) echo 900 ;;
	esac
}

while :; do
	if [ -f "$live" ]; then
		# Продлевает, только если до конца срока меньше 30 дней; иначе ничего не делает
		certbot renew --webroot -w "$webroot" --quiet && pause=43200 || pause=3600
	elif nginx_serves_challenge && certbot certonly --webroot -w "$webroot" -d "$DENTAL_DOMAIN" \
		--agree-tos --register-unsafely-without-email --non-interactive; then
		# Почта необязательна: письма об истечении Let's Encrypt больше не рассылает,
		# а продление — автоматическое
		echo "certbot: сертификат для $DENTAL_DOMAIN получен"
		failures=0
		pause=43200
	else
		failures=$((failures + 1))
		pause="$(backoff)"
		echo "certbot: не получилось (попытка $failures), следующая через $pause с"
	fi
	sleep "$pause"
done
