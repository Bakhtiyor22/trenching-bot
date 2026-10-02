create table trade_intents (
    id varchar(256) primary key,
    run_id varchar(64) not null,
    source_event_id varchar(128) not null,
    base_mint varchar(44) not null,
    quote_mint varchar(44) not null,
    strategy_name varchar(128) not null,
    strategy_version varchar(128) not null,
    configuration_hash varchar(64) not null,
    entry_offset integer not null,
    creation_slot bigint not null,
    target_slot bigint not null,
    notional numeric(38,12) not null,
    version bigint not null default 0,
    constraint fk_trade_intents_run foreign key (run_id) references simulation_runs(id),
    constraint fk_trade_intents_event foreign key (run_id, source_event_id) references wallet_events(run_id, event_id),
    constraint uq_trade_intents_scenario unique (run_id, strategy_name, strategy_version, source_event_id, entry_offset),
    constraint uq_trade_intents_run_id unique (run_id, id),
    constraint ck_trade_intents_slots check (creation_slot >= 0 and target_slot > creation_slot and entry_offset between 1 and 3),
    constraint ck_trade_intents_notional check (notional > 0)
);
create table paper_orders (
    id varchar(256) primary key,
    run_id varchar(64) not null,
    target_slot bigint not null,
    status varchar(16) not null,
    rejection_reason varchar(32),
    version bigint not null default 0,
    constraint fk_paper_orders_intent foreign key (run_id, id) references trade_intents(run_id, id),
    constraint uq_paper_orders_run_status unique (run_id, id, status),
    constraint ck_paper_orders_slot check (target_slot >= 0),
    constraint ck_paper_orders_status check (
        (status = 'FILLED' and rejection_reason is null) or
        (status = 'REJECTED' and rejection_reason is not null)
    )
);
create table paper_fills (
    order_id varchar(256) primary key,
    run_id varchar(64) not null,
    order_status varchar(16) not null,
    target_slot bigint not null,
    actual_slot bigint not null,
    observation_id varchar(255) not null,
    execution_price numeric(38,12) not null,
    quantity numeric(38,12) not null,
    fee numeric(38,12) not null,
    cost_basis numeric(38,12) not null,
    priority_fee numeric(38,12) not null,
    fee_bps integer not null,
    slippage_bps integer not null,
    price_impact_bps integer not null,
    price_source varchar(128) not null,
    price_assumption varchar(32) not null,
    version bigint not null default 0,
    constraint fk_paper_fills_order foreign key (run_id, order_id, order_status) references paper_orders(run_id, id, status),
    constraint fk_paper_fills_observation foreign key (run_id, observation_id) references market_observations(run_id, observation_id),
    constraint uq_paper_fills_run_order unique (run_id, order_id),
    constraint ck_paper_fills_status check (order_status = 'FILLED'),
    constraint ck_paper_fills_slots check (target_slot >= 0 and actual_slot >= target_slot),
    constraint ck_paper_fills_amounts check (execution_price > 0 and quantity > 0 and fee >= 0 and cost_basis > 0 and priority_fee >= 0),
    constraint ck_paper_fills_costs check (fee_bps between 0 and 9999 and slippage_bps between 0 and 9999 and price_impact_bps >= 0)
);
create table positions (
    id varchar(256) primary key,
    run_id varchar(64) not null,
    intent_id varchar(256) not null,
    base_mint varchar(44) not null,
    quote_mint varchar(44) not null,
    entry_offset integer not null,
    fill_order_id varchar(256) not null,
    target_slot bigint not null,
    state varchar(16) not null,
    close_slot bigint,
    close_observation_id varchar(255),
    exit_price numeric(38,12),
    gross_proceeds numeric(38,12),
    exit_fee numeric(38,12),
    net_proceeds numeric(38,12),
    realized_pnl numeric(38,12),
    close_reason varchar(32),
    exit_unavailable varchar(32),
    version bigint not null default 0,
    constraint fk_positions_intent foreign key (run_id, intent_id) references trade_intents(run_id, id),
    constraint fk_positions_fill foreign key (run_id, fill_order_id) references paper_fills(run_id, order_id),
    constraint fk_positions_exit_observation foreign key (run_id, close_observation_id) references market_observations(run_id, observation_id),
    constraint ck_positions_identity check (id = intent_id and intent_id = fill_order_id),
    constraint ck_positions_scenario check (entry_offset between 1 and 3 and target_slot >= 0),
    constraint ck_positions_state check (
        (state = 'OPEN' and close_slot is null and close_observation_id is null and exit_price is null and
         gross_proceeds is null and exit_fee is null and net_proceeds is null and realized_pnl is null and close_reason is null)
        or
        (state = 'CLOSED' and close_slot is not null and close_slot >= target_slot and close_observation_id is not null and
         exit_price is not null and exit_price > 0 and gross_proceeds is not null and gross_proceeds >= 0 and
         exit_fee is not null and exit_fee >= 0 and net_proceeds is not null and realized_pnl is not null and
         close_reason is not null and exit_unavailable is null)
    )
);
create index idx_trade_intents_run_slot on trade_intents(run_id, target_slot, id);
create index idx_paper_orders_run_slot on paper_orders(run_id, target_slot, id);
create index idx_paper_fills_run_slot on paper_fills(run_id, actual_slot, order_id);
create index idx_positions_run_slot on positions(run_id, target_slot, id);
create unique index uq_positions_open_scenario on positions(run_id, entry_offset, base_mint, quote_mint) where state = 'OPEN';
