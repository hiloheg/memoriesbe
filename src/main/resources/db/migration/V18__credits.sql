-- Credits: one row per change of an owner's credit balance. kind 'trial' is the free credit, 'full' is purchased.
create table credit_ledger (
    id uuid primary key default gen_random_uuid(),
    owner_sub text not null,
    delta int not null,
    kind text not null check (kind in ('trial', 'full')),
    reason text not null check (reason in ('welcome', 'purchase', 'event', 'refund')),
    ref text unique,
    event_id uuid,
    created_at timestamptz not null default now()
);
create index credit_ledger_owner on credit_ledger (owner_sub);

-- Every payment webhook we receive, for support. Stored whatever the outcome.
create table payment_events (
    id uuid primary key default gen_random_uuid(),
    order_id text,
    event_name text not null,
    outcome text not null,
    payload jsonb,
    received_at timestamptz not null default now()
);

-- The moment credits were switched on (every account counts as unlocked for 30 days after it).
create table credit_launch (launched_at timestamptz not null);
insert into credit_launch (launched_at) values (now());

-- What each event got from its credit, and when its images go.
alter table events add column plan text;
alter table events add column max_photos int;
alter table events add column max_bytes bigint;
alter table events add column expires_at timestamptz;
alter table events add column purge_after timestamptz;
alter table events add column purged_at timestamptz;

-- Events that exist at launch are full events for 30 days, then their images are deleted.
update events set plan = 'full', max_photos = 3000, max_bytes = 42949672960,
    expires_at = now() + interval '30 days', purge_after = now() + interval '30 days';
