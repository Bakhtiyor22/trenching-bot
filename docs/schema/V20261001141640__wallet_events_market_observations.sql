create table wallet_events (
    id varchar(384) primary key,
    run_id varchar(64) not null,
    event_id varchar(128) not null,
    tracked_wallet varchar(44) not null,
    wallet varchar(44) not null,
    signature varchar(88) not null,
    instruction_index integer not null,
    slot bigint not null,
    block_time timestamptz,
    base_mint varchar(44) not null,
    quote_mint varchar(44) not null,
    base_delta numeric(20,0) not null,
    quote_delta numeric(20,0) not null,
    base_decimals integer not null,
    quote_decimals integer not null,
    successful boolean not null,
    supported_route boolean not null,
    signer boolean not null,
    source varchar(128) not null,
    commitment varchar(16) not null,
    side varchar(16),
    ignored_reason varchar(32),
    version bigint not null default 0,
    constraint fk_wallet_events_run foreign key (run_id) references simulation_runs(id),
    constraint uq_wallet_events_identity unique (run_id, signature, instruction_index),
    constraint uq_wallet_events_event unique (run_id, event_id),
    constraint ck_wallet_events_slot check (slot >= 0 and instruction_index >= 0),
    constraint ck_wallet_events_decimals check (base_decimals between 0 and 255 and quote_decimals between 0 and 255),
    constraint ck_wallet_events_amounts check (
        base_delta between -18446744073709551615 and 18446744073709551615 and
        quote_delta between -18446744073709551615 and 18446744073709551615
    ),
    constraint ck_wallet_events_outcome check (
        (side is not null and side in ('BUY', 'SELL') and ignored_reason is null) or
        (side is null and ignored_reason is not null)
    )
);
create table market_observations (
    id varchar(384) primary key,
    run_id varchar(64) not null,
    observation_id varchar(255) not null,
    base_mint varchar(44) not null,
    quote_mint varchar(44) not null,
    slot bigint not null,
    price_slot bigint not null,
    observed_time timestamptz not null,
    price numeric(78,38) not null,
    price_scale integer not null,
    liquidity_quote numeric(78,38),
    liquidity_scale integer,
    source varchar(128) not null,
    source_event_id varchar(255) not null,
    commitment varchar(16) not null,
    quality varchar(32) not null,
    version bigint not null default 0,
    constraint fk_market_observations_run foreign key (run_id) references simulation_runs(id),
    constraint uq_market_observations_identity unique (run_id, observation_id),
    constraint ck_market_observations_slot check (slot >= 0 and price_slot >= 0),
    constraint ck_market_observations_price check (price > 0 and (liquidity_quote is null or liquidity_quote >= 0)),
    constraint ck_market_observations_scale check ((liquidity_quote is null) = (liquidity_scale is null))
);
create index idx_wallet_events_run_slot on wallet_events(run_id, slot, signature, instruction_index);
create index idx_market_observations_pair_slot on market_observations(run_id, base_mint, quote_mint, slot, observation_id);
