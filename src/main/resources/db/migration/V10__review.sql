-- Отзыв после приёма: на следующий день бот спрашивает тех, кто записывался через бота,
-- «Как прошёл приём?» — оценка от 1 до 5. review_asked_at — спросили (один раз на приём).
alter table appointment add column review_asked_at timestamptz;
alter table appointment add column rating smallint check (rating between 1 and 5);
