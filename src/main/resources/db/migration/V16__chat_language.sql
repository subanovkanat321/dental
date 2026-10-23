-- Язык, на котором бот и Mini App говорят с пациентом: русский или кыргызский.
-- Ключ — Telegram id: язык есть и у того, кто ещё не записывался (/start, /help).
-- Строки нет — русский. Первое значение бот берёт из языка Telegram (language_code),
-- дальше его меняет сам пациент переключателем в Mini App.
create table chat_language (
    telegram_id bigint primary key,
    lang        varchar(2) not null check (lang in ('ru', 'ky')),
    updated_at  timestamptz not null default now()
);
