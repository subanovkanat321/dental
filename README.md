# dental

Запись к стоматологу в Telegram: бот и Mini App для врача — расписание на день, новые
записи без двойного бронирования, напоминания пациентам в WhatsApp, утренняя сводка.

Учебный проект. Сейчас только **тестовые пациенты**: реальные данные — после шага 8
(регистрация в реестре ГАЗПД, согласие пациента, проверка по главе 11 Цифрового кодекса КР).

## Стек

Spring Boot 4.1, Java 25, Postgres 18, Flyway, Testcontainers. Mini App — HTML и JS без сборки.

## Запуск

- Нужны JDK 25 и Docker.
- `./mvnw spring-boot:run` — профиль `local`; Postgres из `compose.yaml` поднимается сам
  на `localhost:5433` (база, логин и пароль — `dental`).
- `./mvnw verify` — сборка и тесты (Testcontainers, настоящий Postgres).

## Mini App

`src/main/resources/static`: `index.html`, `app.css`, `js/app.js` (экраны), `js/state.js`
(чистые функции: статус строки, даты по Бишкеку — их тесты в `src/test/js`, запуск
`node --test 'src/test/js/*.test.js'`). Без сборки и фреймворка.

- **«День»** — приёмы по времени, у каждого один статус (значок + подпись), «Сегодня | Завтра», ‹ ›.
- **Карточка** — ответ пациента, «Перенести», «Пришёл» / «Не пришёл» (сегодня и раньше),
  «Отменить приём» с плашкой «Вернуть» на 5 с, «Восстановить» у отменённого.
- **«Новая запись»** — поиск пациента или новый, дата, длительность, сетка свободного
  времени (или «Другое время» вручную); «Время занято» — над сеткой, сетка обновляется.
- **«свободно 11:00–12:00»** — окна в рабочих часах (`WORK_HOURS`, `WORK_DAYS`); нажатие —
  запись на это время. Сетку и окна считает сервер тем же условием, что и защита базы.

На ноутбуке: `./mvnw spring-boot:run` → http://localhost:8080. Вне Telegram страница берёт
подписанную тестовую initData у `/dev/init-data` (только профиль `local`), а MainButton
заменяет своя кнопка внизу.

## Защита от двойной записи

Держит сама база (`V1__init.sql`): ограничение `EXCLUDE USING gist` не даёт двум активным
приёмам пересечься по времени, даже если два запроса пришли одновременно. Интервал
полуоткрытый `[start, end)`, поэтому приёмы встык разрешены. Отменённый приём и неявка
время не занимают.

## API

Ошибки — Problem Details (`application/problem+json`): 422 со списком `errors[]` под полями,
409 с полем `code` — `time-taken` «Время занято», `stale` «Приём изменён, обновите»,
`invalid-transition`, `duplicate-patient` «Такой пациент уже есть».

| Запрос | Что делает |
|---|---|
| `POST /api/patients/search` `{query}` | поиск по части имени или номера (номер не в URL) |
| `POST /api/patients` `{fullName, phone, allowDuplicate}` | новый пациент; телефон приводится к `996XXXXXXXXX` / цифрам E.164 |
| `GET /api/appointments?date=2027-06-01` | приёмы дня по Бишкеку |
| `GET /api/appointments/{id}` | один приём |
| `POST /api/appointments` `{patientId, startAt, durationMinutes}` | запись, 15–240 мин |
| `POST /api/appointments/{id}/reschedule` `{version, startAt, durationMinutes}` | перенос, сбрасывает ответ и отметку напоминания |
| `POST /api/appointments/{id}/status` `{version, status}` | отменить, восстановить, пришёл / не пришёл |
| `POST /api/appointments/{id}/reply` `{version, reply}` | ответ пациента |
| `POST /api/appointments/{id}/undo` `{version, previousStatus}` | «Вернуть» после отмены или «не пришёл» |
| `POST /api/appointments/{id}/reminder-opened` | кнопка WhatsApp нажата |
| `GET /api/slots?date=&duration=&exclude=` | свободные окна дня и сетка начал (шаг 15 мин) в рабочих часах |

## Вход и бот

- Каждый запрос к `/api/**` несёт `Authorization: tma <initData>` — строку, которую Telegram
  передаёт в Mini App. Сервер проверяет подпись токеном бота и срок (24 ч): иначе 401
  «Сессия устарела — откройте заново из бота». Подпись верна, но id не в
  `ALLOWED_TELEGRAM_IDS` — 403 с контактом клиники.
- `POST /telegram/webhook` — сообщения боту на сервере; без верного
  `X-Telegram-Bot-Api-Secret-Token` — 401, иначе всегда 200.
- `/start` от врача — приветствие и кнопка меню «Открыть день» (если `PUBLIC_URL` — https);
  от постороннего — «Это служебный бот записи клиники…» и контакт.

### Бот на ноутбуке

1. `cp .env.example .env` и заполнить: токен от @BotFather, свой id (@userinfobot),
   `TELEGRAM_MODE=polling`. `.env` в `.gitignore`, профиль `local` читает его сам.
2. `./mvnw spring-boot:run` → написать боту `/start`.
3. Кнопка Mini App появится только с https-адресом: туннель (например,
   `cloudflared tunnel --url http://localhost:8080`) и его адрес в `PUBLIC_URL`.

## Деплой

Сервер: DigitalOcean (Германия), 2 ГБ RAM, Docker уже стоит (там работал store). Стек —
`compose.prod.yaml`: Postgres, приложение, nginx (https), certbot (Let's Encrypt). Наружу
открыты только 80 и 443. Память ≈ 1 ГБ по лимитам, поэтому store перед этим выключается.

### 0. Адрес для https

Telegram открывает Mini App только по https с настоящим сертификатом. Домен не нужен:
Let's Encrypt выдаёт сертификаты прямо на IP — `DENTAL_DOMAIN=164.92.189.88`, адрес
`https://164.92.189.88`. Такой сертификат живёт 160 часов; certbot сам продлевает его
на середине срока (`deploy/certbot.sh` проверяет раз в 6 ч). Появится домен — поменять
`DENTAL_DOMAIN` и перезапустить стек.

### 1. Выключить store (только после бэкапа)

```bash
cd /opt/store
deploy/backup.sh && deploy/verify-backup.sh   # свежий бэкап и проверка, что он восстанавливается
docker compose -f compose.prod.yaml --env-file .env down   # без -v: тома с данными остаются
```

### 2. Запустить dental

```bash
git clone https://github.com/subanovkanat321/dental /opt/dental && cd /opt/dental
cp .env.prod.example .env && chmod 600 .env
openssl rand -hex 24   # → POSTGRES_PASSWORD
openssl rand -hex 32   # → TELEGRAM_WEBHOOK_SECRET
nano .env              # DENTAL_DOMAIN, пароли, токен бота, CLINIC_CONTACT
docker compose -f compose.prod.yaml --env-file .env up -d --build
docker compose -f compose.prod.yaml --env-file .env logs -f certbot   # ждать «сертификат … получен»
```

Проверка: `https://<DENTAL_DOMAIN>/actuator/health` → `UP`, в Telegram боту `/start` →
кнопка «Открыть день».

**Один бот на ноутбуке и сервере.** При старте в режиме polling приложение снимает webhook,
и сервер перестаёт получать сообщения. После деплоя на ноутбуке — `TELEGRAM_MODE=off`
(Mini App в браузере работает и так); если webhook всё же снялся —
`docker compose -f compose.prod.yaml --env-file .env restart app` на сервере ставит его заново.

### 3. Бэкап по ночам

```bash
crontab -e
15 3 * * * /opt/dental/deploy/backup.sh >> /var/log/dental-backup.log 2>&1
```

`deploy/verify-backup.sh` — восстанавливает последний бэкап во временный Postgres и считает
строки. До настоящих пациентов (шаг 8) нужна копия вне сервера: `BACKUP_REMOTE` в `.env`
(rclone, как в store).

### Обновление

```bash
cd /opt/dental && git pull && docker compose -f compose.prod.yaml --env-file .env up -d --build
```

## Этапы

1. ✅ Скелет, профили, миграция V1, тесты ограничений, CI
2. ✅ Пациенты и приёмы: REST API, 409 «Время занято», переходы статусов, undo
3. ✅ Telegram: webhook, проверка initData, список доступа
4. ✅ Mini App: «День», карточка приёма, «Новая запись»
5. ✅ Свободное время: строки «свободно» в «Дне», сетка начал в записи и переносе, `WORK_HOURS` / `WORK_DAYS`
6. Напоминания (wa.me) и утренняя сводка
7. ✅ Деплой на сервер (https://164.92.189.88, с 2026-10-22), бэкап на сервере; копия вне сервера — до шага 8
8. Юридический шлюз перед реальными пациентами
9. Playwright-сценарии
