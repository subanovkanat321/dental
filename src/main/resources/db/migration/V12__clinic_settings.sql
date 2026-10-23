-- Адрес кабинета (со ссылкой на 2ГИС) и техподдержка приложения. Одна строка (id = 1);
-- админ заполняет в «Меню → О клинике». Пусто — не показываем.
create table clinic_settings (
    id               smallint primary key check (id = 1),
    address          text check (length(address) <= 200),
    -- Только ссылка 2ГИС (проверяет сервер): пациенты нажимают её из бота и Mini App
    map_url          text check (length(map_url) <= 300),
    -- Telegram того, кто поддерживает приложение (без «@»), и его телефон цифрами
    support_telegram text check (support_telegram ~ '^[A-Za-z0-9_]{5,32}$'),
    support_phone    varchar(15) check (support_phone ~ '^[0-9]{8,15}$')
);
insert into clinic_settings (id) values (1);
