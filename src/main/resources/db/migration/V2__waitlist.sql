-- Лист ожидания: пациент хочет прийти раньше, чем записан, или пока не записан.
-- Освободилось окно — врач видит, кого позвать, и пишет ему в WhatsApp.
create table waitlist_entry (
    id               bigint generated always as identity primary key,
    patient_id       bigint      not null references patient (id),
    -- Сколько нужно времени: в окно короче пациент не поместится
    duration_minutes integer     not null check (duration_minutes between 15 and 240),
    created_at       timestamptz not null default now()
);

-- Один пациент — одна строка в листе: вторая ничего не добавила бы, кроме путаницы
create unique index waitlist_entry_patient_uq on waitlist_entry (patient_id);
