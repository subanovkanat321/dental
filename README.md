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
- **«Новая запись»** — поиск пациента или новый, дата, длительность, время;
  «Время занято» — под полем времени.

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

## Этапы

1. ✅ Скелет, профили, миграция V1, тесты ограничений, CI
2. ✅ Пациенты и приёмы: REST API, 409 «Время занято», переходы статусов, undo
3. ✅ Telegram: webhook, проверка initData, список доступа
4. ✅ Mini App: «День», карточка приёма, «Новая запись»
5. Сетка свободного времени (`/api/slots`), рабочие часы
6. Напоминания (wa.me) и утренняя сводка
7. Деплой на сервер, бэкапы
8. Юридический шлюз перед реальными пациентами
9. Playwright-сценарии
