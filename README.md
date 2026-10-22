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

**Проверки доступа пока нет** — она появится на шагах 3–4 (initData Telegram и список
разрешённых id). До этого приложение не выкладывать на сервер.

## Этапы

1. ✅ Скелет, профили, миграция V1, тесты ограничений, CI
2. ✅ Пациенты и приёмы: REST API, 409 «Время занято», переходы статусов, undo
3. Telegram: webhook, проверка initData, список доступа
4. Mini App: «День», карточка приёма, «Новая запись»
5. Сетка свободного времени (`/api/slots`), рабочие часы
6. Напоминания (wa.me) и утренняя сводка
7. Деплой на сервер, бэкапы
8. Юридический шлюз перед реальными пациентами
9. Playwright-сценарии
