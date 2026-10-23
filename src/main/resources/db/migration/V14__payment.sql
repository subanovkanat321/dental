-- Касса: сколько пациент заплатил за приём и как. Деньги — целые сомы (как цены услуг).
-- Ставится у состоявшегося приёма; выручка считается только по ним.
alter table appointment add column paid_som integer check (paid_som between 0 and 10000000);
alter table appointment add column payment_method varchar(16) check (payment_method in ('CASH', 'TRANSFER', 'CARD'));
alter table appointment add column paid_at timestamptz;
alter table appointment add constraint appointment_payment check ((paid_som is null) = (payment_method is null));
