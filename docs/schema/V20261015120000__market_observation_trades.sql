-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying
-- replay dataset schema v4 (docs/plans/20261015-chart-trade-actors-mcap.md, docs/adr/20261015-chart-trade-actors-and-mcap.md).
-- Local and test databases get the four columns from the entity through `ddl-auto: update`; the CHECK constraints below
-- exist only here, like the other constraints Hibernate does not create.
--
-- Every column is nullable and additive: rows stored before trades were kept have all four null and read back without a
-- trade. A trade is stored whole or not at all.

alter table market_observations
    add column trade_trader varchar(44),
    add column trade_side varchar(8),
    add column trade_sol_lamports numeric(20,0),
    add column trade_token_raw numeric(20,0);

alter table market_observations
    add constraint ck_market_observations_trade check (
        (trade_trader is null and trade_side is null and trade_sol_lamports is null and trade_token_raw is null)
        or (trade_trader is not null and trade_side is not null and trade_sol_lamports is not null and trade_token_raw is not null
            and trade_side in ('BUY', 'SELL')
            and trade_sol_lamports between 1 and 18446744073709551615
            and trade_token_raw between 1 and 18446744073709551615));
