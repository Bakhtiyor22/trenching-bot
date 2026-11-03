-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying the
-- automated capture and sweep (docs/plans/20261102-decu-capture-sweep-automation.md). Each part names the unit that adds it.

begin;

-- Engine dip-paper-v6, the fee-inclusive minimum signal size (docs/adr/20261102-fee-inclusive-signal-size.md).
-- signal_lamports is the wallet buy's curve payment plus its protocol and creator fees, set when a minimum sized it; nullable and
-- additive, so no row is rewritten and an intent stored earlier has none.

alter table trade_intents
    add column if not exists signal_lamports numeric(20, 0);

-- A database built from the earlier reference SQL has no value checks on the enum columns and needs only the column above.
-- A local database whose tables Hibernate created has one check per enum column, which `ddl-auto: update` never changes;
-- this extends each, keeping every value already allowed (V20261025120000 included), so no stored row is affected.
-- dip-paper-v6 stores SIGNAL_BELOW_MIN_SIZE and SIGNAL_SIZE_UNAVAILABLE as a watch outcome and a rejection reason.

alter table trade_intents
    drop constraint if exists trade_intents_watch_check,
    add constraint trade_intents_watch_check check (watch in ('TRIGGERED', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE',
        'SIGNAL_BELOW_MIN_SIZE', 'SIGNAL_SIZE_UNAVAILABLE'));

alter table paper_orders
    drop constraint if exists paper_orders_rejection_reason_check,
    add constraint paper_orders_rejection_reason_check check (rejection_reason in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE',
        'SIGNAL_BELOW_MIN_SIZE', 'SIGNAL_SIZE_UNAVAILABLE'));

alter table positions
    drop constraint if exists positions_exit_unavailable_check,
    add constraint positions_exit_unavailable_check check (exit_unavailable in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE',
        'SIGNAL_BELOW_MIN_SIZE', 'SIGNAL_SIZE_UNAVAILABLE'));

-- Dataset-referenced runs (docs/adr/20261102-dataset-referenced-runs.md). A run stored with storage DATASET_REFERENCE keeps no
-- dataset_snapshot and no market_observations rows and reads its dataset from replay_datasets. storage is nullable and additive:
-- existing rows keep null, which reads as COPY, so no row is rewritten. The index serves the dataset delete check.
-- `ddl-auto: update` adds the column and the index but never drops a NOT NULL, so a local database Hibernate created needs the
-- DROP NOT NULL below once (or a reset with `docker compose down -v`) before it can store a reference run. The IF NOT EXISTS and
-- DROP CONSTRAINT IF EXISTS forms let this whole file run on such a database, where `update` already added the column and index.

ALTER TABLE simulation_runs ADD COLUMN IF NOT EXISTS storage varchar(20);
ALTER TABLE simulation_runs ALTER COLUMN dataset_snapshot DROP NOT NULL;
ALTER TABLE simulation_runs DROP CONSTRAINT IF EXISTS ck_simulation_runs_storage;
ALTER TABLE simulation_runs ADD CONSTRAINT ck_simulation_runs_storage
  CHECK ((storage IS NULL OR storage = 'COPY') AND dataset_snapshot IS NOT NULL
      OR storage = 'DATASET_REFERENCE' AND dataset_snapshot IS NULL);
CREATE INDEX IF NOT EXISTS idx_simulation_runs_storage_dataset ON simulation_runs (dataset_checksum, storage);

-- Automation (docs/adr/20261102-automated-capture-and-sweep-evaluation.md): the five automation tables, exactly as their
-- entities in console.automation.infrastructure.persistence declare them. Automation runs only under the local profile, but
-- the entities are scanned in every profile, so `validate` needs the tables in production too. All five are new and
-- additive; local and test databases get them through `ddl-auto: update`, with one CHECK per enum column that Hibernate adds
-- and this file leaves out, as for the earlier enum columns. Ids are SHA-256 hex derived from their input; associations are
-- by id only, with no foreign keys. Rollback: drop the five tables.

create table if not exists automation_watermarks (
    wallet varchar(44) primary key,
    slot bigint not null,
    signature varchar(88) not null,
    instruction_index integer not null,
    inner_index integer not null,
    updated_at timestamptz not null,
    version bigint not null default 0
);

create table if not exists automation_sessions (
    id varchar(64) primary key,
    wallet varchar(44) not null,
    first_item_id varchar(64) not null,
    first_buy_at timestamptz not null,
    last_buy_at timestamptz not null,
    state varchar(16) not null,
    closed_at timestamptz,
    close_reason varchar(16),
    below_filter_count integer not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_automation_sessions_first_item unique (first_item_id)
);

create index if not exists idx_automation_sessions_wallet_state on automation_sessions (wallet, state, last_buy_at);
create index if not exists idx_automation_sessions_first_buy on automation_sessions (wallet, first_buy_at);

create table if not exists automation_items (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    wallet varchar(44) not null,
    mint varchar(44) not null,
    signature varchar(88) not null,
    instruction_index integer not null,
    inner_index integer not null,
    slot bigint not null,
    block_time timestamptz,
    curve_payment_lamports numeric(20, 0) not null,
    state varchar(24) not null,
    step varchar(16),
    step_attempt integer not null,
    capture_session_id varchar(80),
    operation_id varchar(80),
    dataset_id varchar(64),
    classification varchar(32),
    failure_code varchar(64),
    failure_message varchar(512),
    next_action_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_automation_items_buy unique (wallet, signature, instruction_index, inner_index),
    constraint uq_automation_items_mint unique (wallet, mint)
);

create index if not exists idx_automation_items_due on automation_items (state, next_action_at, block_time);
create index if not exists idx_automation_items_session on automation_items (session_id, block_time);

create table if not exists automation_sweep_runs (
    id varchar(64) primary key,
    item_id varchar(64) not null,
    session_id varchar(64) not null,
    plan_id varchar(32) not null,
    label varchar(16) not null,
    run_id varchar(64),
    configuration_hash varchar(64),
    engine_version varchar(64),
    outcome varchar(40) not null,
    position_state varchar(8),
    is_signal boolean not null,
    realized_pnl numeric(38, 12) not null,
    worst_case_pnl numeric(38, 12) not null,
    created_at timestamptz not null,
    constraint uq_automation_sweep_runs_entry unique (item_id, plan_id, label)
);

create index if not exists idx_automation_sweep_runs_run on automation_sweep_runs (run_id);
create index if not exists idx_automation_sweep_runs_session on automation_sweep_runs (session_id, plan_id, label);

create table if not exists automation_evaluations (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    plan_id varchar(32) not null,
    label varchar(16) not null,
    window_session_ids text not null,
    window_sessions integer not null,
    signals integer not null,
    scored integer not null,
    unscored integer not null,
    realized_pnl numeric(38, 12) not null,
    worst_case_pnl numeric(38, 12) not null,
    status varchar(12) not null,
    evaluated_at timestamptz not null,
    constraint uq_automation_evaluations_entry unique (session_id, plan_id, label)
);

create index if not exists idx_automation_evaluations_rank on automation_evaluations (status, worst_case_pnl, evaluated_at);
create index if not exists idx_automation_evaluations_config on automation_evaluations (plan_id, label, evaluated_at);

-- Capture pipeline (plan Unit 4). requests_used is an item's capture session's provider requests, refreshed whenever a
-- capture step ends. The watermark's backlog cursor (plan Rule 9, amended 2026-11-03): while a backlog larger than one scan is
-- worked through, resume_before is where the next scan continues and pending_* the newest action of the whole catch-up, which
-- becomes the watermark once a scan reaches it; resume_since is the initial lookback a first scan's catch-up still has to
-- reach. All nullable and additive, so no row is rewritten; `ddl-auto: update` adds them to local and test databases.

alter table automation_items add column if not exists requests_used integer;
alter table automation_watermarks add column if not exists resume_before varchar(88);
alter table automation_watermarks add column if not exists resume_since timestamptz;
alter table automation_watermarks add column if not exists pending_slot bigint;
alter table automation_watermarks add column if not exists pending_signature varchar(88);
alter table automation_watermarks add column if not exists pending_instruction_index integer;
alter table automation_watermarks add column if not exists pending_inner_index integer;

-- Second review fixes (plan Rule 10, amended 2026-11-03). catch_up_started_at is the start of the scan from the newest
-- transaction that began a catch-up in progress; when a later scan completes that catch-up, updated_at (the start of the last
-- complete scan) takes it. Nullable and additive: a catch-up stored before the column existed completes as before.

alter table automation_watermarks add column if not exists catch_up_started_at timestamptz;

commit;
