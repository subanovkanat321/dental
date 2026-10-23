-- Уведомление врачу перед приёмом («Через 15 мин, в 10:00 — приём»): когда отправлено.
-- Приём «забирается» одним UPDATE ... RETURNING по условию pre_notified_at is null, поэтому
-- уведомление не придёт дважды, даже если задача запустится два раза подряд.
alter table appointment add column pre_notified_at timestamptz;
