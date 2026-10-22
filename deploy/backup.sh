#!/usr/bin/env bash
# Ночной бэкап базы (pg_dump), как в store, но без фото. Запуск из cron на сервере:
#   15 3 * * * /opt/dental/deploy/backup.sh >> /var/log/dental-backup.log 2>&1
#
#   BACKUP_DIR     куда класть файлы               (/var/backups/dental)
#   BACKUP_KEEP    сколько последних копий хранить  (14)
#   BACKUP_REMOTE  rclone-адрес копии вне сервера, например "offsite:". Пусто — только
#                  локально; такой бэкап умрёт вместе с сервером. До настоящих пациентов
#                  (шаг 8) копия вне сервера обязательна
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Одна переменная из .env, без source: source выполнил бы .env как скрипт
env_value() { [[ -f "$ROOT/.env" ]] && sed -n "s/^$1=//p" "$ROOT/.env" | tail -n 1 | tr -d '\r'; }
BACKUP_DIR="${BACKUP_DIR:-/var/backups/dental}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
BACKUP_REMOTE="${BACKUP_REMOTE:-$(env_value BACKUP_REMOTE || true)}"
compose=(docker compose -f "$ROOT/compose.prod.yaml" --env-file "$ROOT/.env")

# В дампе — имена и телефоны пациентов: файлы читает только владелец (600)
umask 077
mkdir -p "$BACKUP_DIR"

# Второй запуск, пока идёт первый (cron + ручной), испортил бы файлы
exec 9>"$BACKUP_DIR/.lock"
flock -n 9 || { echo "Бэкап уже идёт, выходим"; exit 1; }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/db-$stamp.dump"
echo "$(date -u +%FT%TZ) бэкап $stamp"

# -Fc: сжатый формат pg_restore. Сначала во временный файл: оборванный бэкап
# не будет выглядеть как целый
"${compose[@]}" exec -T db pg_dump -U dental -d dental -Fc > "$file.part"
mv "$file.part" "$file"
# Пустой или обрезанный файл pg_restore не разберёт
"${compose[@]}" exec -T db pg_restore --list < "$file" > /dev/null
echo "  база: $(du -h "$file" | cut -f1)"

# Ротация: оставляем BACKUP_KEEP последних копий
find "$BACKUP_DIR" -maxdepth 1 -name 'db-*.dump' -type f -printf '%f\n' | sort -r \
	| tail -n +"$((BACKUP_KEEP + 1))" | while read -r old; do rm -f -- "$BACKUP_DIR/$old"; done

if [[ -n "$BACKUP_REMOTE" ]]; then
	rclone copy --immutable "$file" "$BACKUP_REMOTE"
	echo "  копия вне сервера: $BACKUP_REMOTE"
fi
echo "$(date -u +%FT%TZ) готово"
