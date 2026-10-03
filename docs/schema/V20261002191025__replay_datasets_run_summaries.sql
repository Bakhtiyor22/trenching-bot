-- Reference only: nothing runs this file. The JPA entities are the schema
-- (ReplayDatasetEntity, SimulationRunEntity); local/test create it with ddl-auto: update.
-- Apply by hand before a prod deployment that validates against these entities.

create table replay_datasets (
    id varchar(64) primary key,
    name varchar(128) not null,
    tracked_wallet varchar(44) not null,
    schema_version integer not null,
    transaction_count integer not null,
    observation_count integer not null,
    first_slot bigint,
    last_slot bigint,
    -- Distinct "base/quote" mint pairs joined by ",", sorted by base then quote; empty for an empty dataset.
    pairs text not null,
    -- Canonical v1 dataset JSON (deduplicated, ordered); ReplayDatasetLoader reloads and re-verifies it.
    snapshot text not null,
    created_at timestamptz not null,
    version bigint not null default 0
);

create index idx_replay_datasets_created_at on replay_datasets (created_at, id);

-- NOT NULL without defaults: an existing populated table must be emptied first
-- (runs are reproducible from their datasets), e.g. `docker compose down -v` locally.
alter table simulation_runs
    add column quote_mint varchar(44) not null,
    add column scenario_count integer not null,
    add column filled_count integer not null,
    add column rejected_count integer not null,
    add column open_count integer not null,
    add column closed_count integer not null,
    add column realized_pnl numeric(38,12) not null;

create index idx_simulation_runs_created_at on simulation_runs (created_at, id);
create index idx_simulation_runs_dataset on simulation_runs (dataset_checksum, created_at);
