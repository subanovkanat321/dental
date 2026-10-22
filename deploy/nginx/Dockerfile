# nginx перед приложением: HTTPS, заголовки безопасности, сжатие.
# Сертификат получает соседний контейнер certbot (deploy/certbot.sh).
FROM nginx:1.28-alpine
# openssl — только для временного самоподписанного сертификата при первом запуске
RUN apk add --no-cache openssl
# Шаблон: при старте образ подставляет ${DENTAL_DOMAIN} и кладёт результат в /etc/nginx/conf.d
COPY default.conf.template /etc/nginx/templates/
COPY security-headers.conf /etc/nginx/snippets/
# Скрипты из /docker-entrypoint.d образ запускает перед стартом nginx
COPY --chmod=755 40-certificate.sh /docker-entrypoint.d/
