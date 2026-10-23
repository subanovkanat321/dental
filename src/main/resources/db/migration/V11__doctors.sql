-- Несколько врачей в одном кабинете: у каждого своё кресло, своё расписание и свой Telegram.
-- Пациенты, услуги и рабочие часы — общие на кабинет.

-- Для EXCLUDE ниже: «равно» по doctor_id внутри индекса gist (расширение доверенное, из contrib)
create extension if not exists btree_gist;

create table doctor (
    id            bigint generated always as identity primary key,
    full_name     text        not null check (length(trim(full_name)) between 1 and 100),
    about         text check (length(about) <= 300),
    -- Telegram врача: вход в приложение врача и уведомления о его приёмах.
    -- null — пока не привязан: его уведомления получают ALLOWED_TELEGRAM_IDS
    telegram_id   bigint unique,
    photo         bytea,
    photo_type    text check (photo_type in ('image/jpeg', 'image/png', 'image/webp')),
    photo_version integer     not null default 0,
    -- Скрытый врач не виден пациентам; его прошлые приёмы остаются
    active        boolean     not null default true,
    created_at    timestamptz not null default now(),
    check ((photo is null) = (photo_type is null))
);

-- Врач №1 — тот, кто работал в приложении до V11: имя, «о себе» и фото — из профиля (V9)
insert into doctor (full_name, about, photo, photo_type, photo_version)
select coalesce(doctor_name, 'Врач'), about, photo, photo_type, photo_version from clinic_profile where id = 1;
drop table clinic_profile;

-- Приём — у конкретного врача. Все прежние приёмы — у врача №1
alter table appointment add column doctor_id bigint references doctor (id);
update appointment set doctor_id = (select min(id) from doctor);
alter table appointment alter column doctor_id set not null;

-- Двойная запись запрещена у одного врача; у разных врачей в одно время — можно (разные кресла)
alter table appointment drop constraint appointment_no_overlap;
alter table appointment add constraint appointment_no_overlap
    exclude using gist (doctor_id with =, tstzrange(start_at, end_at, '[)') with &&)
    where (status not in ('CANCELLED', 'NO_SHOW'));
create index appointment_doctor_start_idx on appointment (doctor_id, start_at);

-- Перерыв — у одного врача или у всех (null): джума-намаз остаётся общим
alter table time_off add column doctor_id bigint references doctor (id) on delete cascade;

-- Лист ожидания — к какому врачу хочет пациент (null — к любому)
alter table waitlist_entry add column doctor_id bigint references doctor (id);
