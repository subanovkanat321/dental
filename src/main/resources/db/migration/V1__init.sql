-- Пациент: только ФИО и телефон (без заметок о лечении — решение до шага 8, юр. вопрос).
-- Телефон хранится цифрами с кодом страны (996555123456): из него собирается ссылка wa.me.
-- Один номер может быть у нескольких пациентов (семья), поэтому он не уникальный.
create table patient (
    id         bigint generated always as identity primary key,
    full_name  text        not null check (length(trim(full_name)) between 1 and 200),
    phone      varchar(15) not null check (phone ~ '^[0-9]{8,15}$'),
    created_at timestamptz not null default now()
);

create index patient_phone_idx on patient (phone);

-- Приём. Время в UTC (timestamptz). Интервал полуоткрытый [start_at, end_at):
-- приёмы встык (10:00–10:30 и 10:30–11:00) не пересекаются.
create table appointment (
    id                 bigint generated always as identity primary key,
    patient_id         bigint      not null references patient (id),
    start_at           timestamptz not null,
    end_at             timestamptz not null,
    status             varchar(16) not null default 'SCHEDULED'
        check (status in ('SCHEDULED', 'CANCELLED', 'DONE', 'NO_SHOW')),
    -- Ответ пациента на напоминание
    reply              varchar(16) not null default 'NONE'
        check (reply in ('NONE', 'CONFIRMED', 'RESCHEDULE', 'NO_ANSWER')),
    -- Когда врач открыл WhatsApp с напоминанием (NULL — не напоминали)
    reminder_opened_at timestamptz,
    -- Оптимистическая блокировка (@Version): два окна не перезапишут друг друга молча
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),

    -- Без этой проверки пустой интервал (start = end) прошёл бы мимо EXCLUDE ниже
    constraint appointment_time_order check (end_at > start_at),
    -- Защита от двойной записи на уровне базы: два активных приёма не могут пересекаться
    -- по времени, даже если два запроса пришли одновременно. Отменённый приём и неявка
    -- время не занимают. Нарушение — ошибка 23P01 (exclusion_violation) → ответ 409.
    constraint appointment_no_overlap
        exclude using gist (tstzrange(start_at, end_at, '[)') with &&)
        where (status not in ('CANCELLED', 'NO_SHOW'))
);

create index appointment_patient_idx on appointment (patient_id);
