#!/usr/bin/env bash
# Проверка, что последний бэкап восстанавливается: поднимает отдельный временный Postgres,
# восстанавливает в него дамп и считает строки. Рабочую базу не трогает.
# Бэкап, который ни разу не восстанавливали, — это надежда, а не бэкап.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/dental}"
file="${1:-$(find "$BACKUP_DIR" -maxdepth 1 -name 'db-*.dump' -type f | sort | tail -n 1)}"
[[ -n "$file" && -f "$file" ]] || { echo "Бэкап не найден в $BACKUP_DIR"; exit 1; }
echo "Проверяю $file"

name="dental-verify-$$"
trap 'docker rm -f "$name" > /dev/null 2>&1 || true' EXIT
docker run -d --name "$name" -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=dental postgres:18 > /dev/null
# По TCP, а не через сокет: при первом старте образ поднимает временный сервер только на
# сокете (создаёт базу), и pg_isready через сокет ответил бы «готов» ему — за миг до остановки
for _ in $(seq 1 60); do
	docker exec "$name" pg_isready -h 127.0.0.1 -U postgres -d dental > /dev/null 2>&1 && break
	sleep 1
done
# Дамп сделан пользователем dental: восстанавливаем без владельцев и прав
docker exec -i "$name" pg_restore -h 127.0.0.1 -U postgres -d dental --no-owner --no-privileges < "$file"

count() { docker exec "$name" psql -h 127.0.0.1 -U postgres -d dental -tAc "select count(*) from $1"; }
echo "  миграций: $(count flyway_schema_history), пациентов: $(count patient), приёмов: $(count appointment)"
# Миграции есть всегда: без них дамп пустой или чужой
test "$(count flyway_schema_history)" -gt 0
echo "Бэкап восстанавливается"
