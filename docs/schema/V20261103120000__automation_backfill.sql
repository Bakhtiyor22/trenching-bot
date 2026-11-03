-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying always-on
-- discovery, trading days, day scoring and backfills (docs/plans/archive/20261103-always-on-automation-trading-days-backfill.md, units 1-3).

begin;

-- Where live discovery covers the wallet without gaps (plan Rule 32): the start of its first scan's lookback. Live discovery
-- covers covered_from up to the last complete scan (updated_at less the indexing margin). A trading day counts as covered only
-- when its whole range lies inside that span, so a null value means no day is covered and every verdict is PENDING until it is set.
-- Nullable and additive, so `ddl-auto: update` adds it to local and test databases and no row is rewritten there.

alter table automation_watermarks add column if not exists covered_from timestamptz;

-- Existing rows: the wallet's first scan reached back 12 hours (the default initial lookback) before the first session it opened
-- was created, so the earliest session's created_at less 12 hours is the nearest value the stored rows can give. A wallet with no
-- session keeps null.

update automation_watermarks w
set covered_from = (select min(s.created_at) - interval '12 hours' from automation_sessions s where s.wallet = w.wallet)
where w.covered_from is null;

-- Session evaluations are no longer written or read (plan Key Decision 4): a trading day's rows are computed on every read from the
-- sessions, items and sweep rows. The table is left in place. Dropping it is optional and may be done by hand once its history is
-- no longer wanted:
--
--     drop table if exists automation_evaluations;

-- Backfills (plan Key Decisions 6-10, Rules 35-43). A backfill is a date-range job that reads the wallet's older buys; its items
-- and sessions carry its id. `ddl-auto: update` creates the table, its unique keys and the index from the entity on local and
-- test databases; the CHECK on state is not created there.

create table if not exists automation_backfills (
    id                  varchar(64)  not null primary key,
    request_id          varchar(80)  not null,
    wallet              varchar(44)  not null,
    -- The wallet while the backfill is queued, scanning or capturing, null once it ends: its unique key lets a wallet hold one.
    active_wallet       varchar(44),
    from_day            date         not null,
    to_day              date         not null,
    -- The range after clipping to live coverage; a dry run keeps the range asked for.
    range_start         timestamptz  not null,
    range_end           timestamptz  not null,
    dry_run             boolean      not null,
    min_signal_lamports numeric(20, 0),
    mints               text,
    request_budget      bigint       not null,
    discovery_requests  bigint       not null,
    state               varchar(16)  not null,
    resume_before       varchar(88),
    actions_scanned     integer      not null,
    buys_found          integer      not null,
    buys_passing        integer      not null,
    items_created       integer      not null,
    skipped_by_filter   integer      not null,
    summary             text,
    estimated_requests  bigint,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    finished_at         timestamptz,
    version             bigint       not null,
    constraint uq_automation_backfills_request unique (request_id),
    constraint uq_automation_backfills_active unique (active_wallet),
    constraint ck_automation_backfills_state check (state in ('QUEUED', 'SCANNING', 'CAPTURING', 'DONE', 'STOPPED_BUDGET', 'CANCELLED'))
);

create index if not exists idx_automation_backfills_wallet_created on automation_backfills (wallet, created_at);

-- The backfill that found an item, and the backfill whose buys a session groups; null for everything live discovery found. Nullable
-- and additive, so no existing row is rewritten.

alter table automation_items add column if not exists backfill_id varchar(64);
alter table automation_sessions add column if not exists backfill_id varchar(64);

create index if not exists idx_automation_items_backfill on automation_items (backfill_id, state);
create index if not exists idx_automation_sessions_backfill on automation_sessions (backfill_id, state);

-- Review fixes (2026-11-03, ADR amendment 3). `scanned_to` is how far back a backfill's scan has read (the range start once it
-- finished): an ended capturing backfill covers [scanned_to, range_end), whether it is done, stopped by its budget or cancelled.
-- `skipped_no_block_time` counts the actions the scan could not place in the range for lack of a block time. Both are additive;
-- `ddl-auto: update` adds them to local and test databases.

alter table automation_backfills add column if not exists scanned_to timestamptz;
alter table automation_backfills add column if not exists skipped_no_block_time integer not null default 0;

-- Backfills that already ended: a finished scan read down to its range start. A stopped or cancelled one that never reached the range
-- start keeps null, which covers nothing, and the days it would have covered stay pending until a backfill reads them again.

update automation_backfills
set scanned_to = range_start
where scanned_to is null and dry_run = false
  and (state = 'DONE' or (state = 'STOPPED_BUDGET' and resume_before is null));

-- Filtered backfills (ADR amendment 4). A backfill with a token list, or a minimum signal above the capture minimum, captures its buys
-- but never counts as coverage and never moves `covered_from`. Additive; `ddl-auto: update` adds it locally. The UPDATE assumes the
-- default capture minimum of 1.5 SOL (1,500,000,000 lamports); change the figure if `capture-min-signal-sol` was set otherwise. A
-- `covered_from` that an earlier version of this change moved back for a stopped, cancelled or filtered backfill is not undone here;
-- that version never ran against production.

alter table automation_backfills add column if not exists filtered boolean not null default false;

update automation_backfills
set filtered = true
where filtered = false and (mints is not null or min_signal_lamports > 1500000000);

commit;
