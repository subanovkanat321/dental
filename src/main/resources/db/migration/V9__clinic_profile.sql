-- Профиль врача для экрана самозаписи: имя, пара слов о себе и фото. Одна строка (id = 1).
-- Фото — в базе (bytea), а не на диске: попадает в тот же бэкап, что и записи, и не нужен
-- отдельный том. Браузер врача заранее уменьшает его до 800 px — это сотни килобайт.
create table clinic_profile (
    id            smallint primary key check (id = 1),
    doctor_name   text check (length(doctor_name) <= 100),
    about         text check (length(about) <= 300),
    photo         bytea,
    photo_type    text check (photo_type in ('image/jpeg', 'image/png', 'image/webp')),
    -- Растёт при каждой смене фото: адрес /profile/photo?v=N меняется, и старое фото из кэша не покажется
    photo_version integer not null default 0,
    check ((photo is null) = (photo_type is null))
);
insert into clinic_profile (id) values (1);
