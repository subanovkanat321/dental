-- Цена услуги «от» в целых сомах: пациент видит её при выборе («от 2 000 сом»).
-- null — цену не показываем (врач ещё не указал или она сильно зависит от случая).
alter table service_type add column price_som integer check (price_som between 0 and 10000000);
