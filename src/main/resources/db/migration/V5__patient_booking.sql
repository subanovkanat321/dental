-- Самозапись пациентов через бота.

-- Услуги: пациент выбирает услугу, длительность приёма берётся из неё. Ведёт врач в Mini App
create table service_type (
    id               bigint generated always as identity primary key,
    name             text        not null check (length(trim(name)) between 1 and 100),
    duration_minutes integer     not null check (duration_minutes between 15 and 240),
    -- Скрытая услуга не видна пациентам, но остаётся у прошлых приёмов
    active           boolean     not null default true,
    created_at       timestamptz not null default now()
);
insert into service_type (name, duration_minutes) values ('Осмотр и консультация', 30);

-- Пациент, записавшийся сам: его Telegram id (по нему бот отвечает «запись подтверждена»)
-- и когда он дал согласие на обработку персональных данных
alter table patient add column telegram_id bigint;
alter table patient add column consent_at timestamptz;
create unique index patient_telegram_uq on patient (telegram_id) where telegram_id is not null;

-- Номер, которым пациент поделился в Telegram, до того как записался: запись — отдельным шагом
create table telegram_contact (
    telegram_id bigint primary key,
    phone       varchar(15) not null check (phone ~ '^[0-9]{8,15}$'),
    first_name  text,
    shared_at   timestamptz not null default now()
);

-- Новый статус REQUESTED — заявка пациента: время уже занято (EXCLUDE ниже не трогаем — он
-- освобождает только CANCELLED и NO_SHOW), врач подтверждает (SCHEDULED) или отклоняет (CANCELLED)
alter table appointment drop constraint appointment_status_check;
alter table appointment add constraint appointment_status_check
    check (status in ('REQUESTED', 'SCHEDULED', 'CANCELLED', 'DONE', 'NO_SHOW'));

-- Кто записал: врач в Mini App или пациент сам; какая услуга
alter table appointment add column source varchar(16) not null default 'DOCTOR'
    check (source in ('DOCTOR', 'PATIENT'));
alter table appointment add column service_type_id bigint references service_type (id);
