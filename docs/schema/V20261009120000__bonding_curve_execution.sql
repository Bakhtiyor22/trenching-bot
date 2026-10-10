-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying the
-- bonding-curve execution model (docs/plans/archive/20261009-bonding-curve-execution.md). Every column is nullable and additive:
-- rows stored before it lack curve state, and the application refuses to read them back.

alter table market_observations
    add column virtual_sol_reserves numeric(20,0),
    add column virtual_token_reserves numeric(20,0),
    add column real_sol_reserves numeric(20,0),
    add column real_token_reserves numeric(20,0),
    add column fee_bps integer,
    add column creator_fee_bps integer;

alter table market_observations
    add constraint ck_market_observations_curve_reserves check (
        virtual_sol_reserves > 0 and virtual_token_reserves > 0
        and real_sol_reserves >= 0 and real_token_reserves >= 0
        and real_sol_reserves <= virtual_sol_reserves
        and real_token_reserves <= virtual_token_reserves),
    add constraint ck_market_observations_fee_bps check (
        fee_bps between 0 and 10000 and creator_fee_bps between 0 and 10000);

-- A close records the exit trade's fee basis points (protocol + creator), the configured transaction cost and the sale's
-- price impact. They are set exactly when the position is closed (close_slot is set).
alter table positions
    add column close_fee_bps integer,
    add column close_transaction_cost numeric(38,12),
    add column close_price_impact_bps integer;

alter table positions
    add constraint ck_positions_close_costs check (
        (close_slot is null and close_fee_bps is null and close_transaction_cost is null and close_price_impact_bps is null)
        or
        (close_slot is not null and close_fee_bps is not null and close_fee_bps between 0 and 20000
         and close_transaction_cost is not null and close_transaction_cost >= 0 and close_price_impact_bps is not null));

-- A sale of a dust position can round down to zero lamports and still closes (its loss is real), so a closed position's
-- exit price may be zero. The rest of the state check is unchanged.
alter table positions
    drop constraint ck_positions_state,
    add constraint ck_positions_state check (
        (state = 'OPEN' and close_slot is null and close_observation_id is null and exit_price is null and
         gross_proceeds is null and exit_fee is null and net_proceeds is null and realized_pnl is null and close_reason is null)
        or
        (state = 'CLOSED' and close_slot is not null and close_slot >= target_slot and close_observation_id is not null and
         exit_price is not null and exit_price >= 0 and gross_proceeds is not null and gross_proceeds >= 0 and
         exit_fee is not null and exit_fee >= 0 and net_proceeds is not null and realized_pnl is not null and
         close_reason is not null and exit_unavailable is null));

-- A fill's fee_bps is now the fill trade's protocol plus creator basis points, each at most 10,000; slippage_bps is 0.
alter table paper_fills
    drop constraint ck_paper_fills_costs,
    add constraint ck_paper_fills_costs check (fee_bps between 0 and 20000 and slippage_bps between 0 and 9999 and price_impact_bps >= 0);
