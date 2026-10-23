-- Часы работы врача по дням недели: у каждого своё кресло и своё время.
-- Нет ни одной строки у врача — работает по часам клиники (WORK_HOURS, WORK_DAYS).
-- Есть строки — работает только в эти дни недели и в эти часы. Перерывы внутри дня — time_off.
create table doctor_hours (
    doctor_id  bigint   not null references doctor (id) on delete cascade,
    weekday    smallint not null check (weekday between 1 and 7),
    start_time time     not null,
    end_time   time     not null,
    primary key (doctor_id, weekday),
    constraint doctor_hours_order check (end_time > start_time)
);
