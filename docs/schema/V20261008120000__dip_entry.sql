-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying the
-- dip-entry strategy (docs/plans/20261008-dip-entry-strategy.md). Every column is nullable and additive.

alter table trade_intents
    add column reference_price numeric(78,38),
    add column dip_price numeric(78,38),
    add column horizon_end_slot bigint,
    add column watch varchar(32),
    add column trigger_observation_id varchar(255),
    add column trigger_slot bigint,
    add column trigger_price numeric(78,38),
    add column trigger_market_cap_usd numeric(38,12),
    add column trigger_rate numeric(78,38);

alter table positions
    add column take_profit_price numeric(78,38),
    add column stop_loss_price numeric(78,38),
    add column exit_trigger_observation_id varchar(255),
    add column exit_trigger_slot bigint,
    add column exit_trigger_reason varchar(32);

alter table market_observations
    add column curve_complete boolean;

-- A dip cycle has entry offset 0; the copy strategy's checks allowed only 1..3.
alter table trade_intents
    drop constraint ck_trade_intents_slots,
    add constraint ck_trade_intents_slots check (creation_slot >= 0 and target_slot > creation_slot and entry_offset >= 0);

alter table positions
    drop constraint ck_positions_scenario,
    add constraint ck_positions_scenario check (entry_offset >= 0 and target_slot >= 0);
