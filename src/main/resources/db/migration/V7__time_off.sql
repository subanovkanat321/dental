-- Перерывы и выходные: время, когда врач не принимает. Его нет в свободных окнах и в сетке
-- самозаписи; врач сам записать может (он знает, что делает).
--   каждую неделю: weekday (1 — пн … 7 — вс), например пятница 13:00–15:00 — джума-намаз;
--   разово:        first_day … last_day, например отпуск или приём у себя в клинике не в этот час.
-- Без start_time/end_time — весь день.
create table time_off (
    id         bigint generated always as identity primary key,
    weekday    smallint check (weekday between 1 and 7),
    first_day  date,
    last_day   date,
    start_time time,
    end_time   time,
    title      text        not null check (length(trim(title)) between 1 and 60),
    emoji      text        not null default '⏸️' check (length(emoji) between 1 and 8),
    created_at timestamptz not null default now(),
    constraint time_off_weekly_or_dates check ((weekday is null) <> (first_day is null)),
    constraint time_off_dates check ((first_day is null) = (last_day is null) and (last_day is null or last_day >= first_day)),
    constraint time_off_times check ((start_time is null) = (end_time is null) and (start_time is null or start_time < end_time))
);

-- Пятница 13:00–15:00 — джума-намаз (врач может изменить или удалить в «Меню → Перерывы и выходные»)
insert into time_off (weekday, start_time, end_time, title, emoji) values (5, '13:00', '15:00', 'Джума-намаз', '🕌');
