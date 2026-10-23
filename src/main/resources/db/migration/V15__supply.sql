-- Склад расходников кабинета: анестетик, пломбировочный материал, перчатки, иглы…
-- Остаток — целое число в своей единице (шт, уп, мл). Ниже порога — бот предупреждает.
create table supply (
    id            bigint generated always as identity primary key,
    name          text        not null check (length(trim(name)) between 1 and 100),
    unit          text        not null default 'шт' check (length(trim(unit)) between 1 and 10),
    quantity      integer     not null default 0 check (quantity >= 0),
    min_quantity  integer     not null default 0 check (min_quantity >= 0),
    created_at    timestamptz not null default now()
);
create unique index supply_name_uq on supply (lower(name));

-- История: кто, сколько и почему. Остаток в supply — сумма этих движений.
create table supply_movement (
    id          bigint generated always as identity primary key,
    supply_id   bigint      not null references supply (id) on delete cascade,
    delta       integer     not null check (delta <> 0),
    reason      varchar(16) not null check (reason in ('USE', 'DELIVERY', 'CORRECTION')),
    -- Врач, который списал (null — админ без кресла)
    doctor_id   bigint references doctor (id) on delete set null,
    created_at  timestamptz not null default now()
);
create index supply_movement_supply_idx on supply_movement (supply_id, created_at desc);
