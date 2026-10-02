create table simulation_runs (
    id varchar(64) primary key,
    engine_version varchar(64) not null,
    dataset_checksum varchar(64) not null,
    configuration_hash varchar(64) not null,
    tracked_wallet varchar(44) not null,
    configuration_snapshot text not null,
    dataset_snapshot text not null,
    report_snapshot text not null,
    result_checksum varchar(64) not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_simulation_runs_identity unique (engine_version, dataset_checksum, configuration_hash),
    constraint ck_simulation_runs_hashes check (
        length(id) = 64 and length(dataset_checksum) = 64 and
        length(configuration_hash) = 64 and length(result_checksum) = 64
    )
);
