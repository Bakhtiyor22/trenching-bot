-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying engine
-- dip-paper-v4, the timed dip window and time exit (docs/adr/20261024-timed-dip-window-and-exit.md). Every column is nullable
-- and additive: rows stored before it read with no times, and a close without a price assumption reads as NEXT_TRADE.

begin;

-- t0 is the block time of the tracked wallet's buy that started the cycle, which the timed clocks count from.
alter table trade_intents
    add column if not exists t0 timestamp(6) with time zone;

-- exit_start is when a timed position's time exit starts; exit_trigger_time is the trigger's block time (a time exit's is its
-- exit start); close_price_assumption says whether the sale was into the next trade or against the standing curve.
alter table positions
    add column if not exists exit_start timestamp(6) with time zone,
    add column if not exists exit_trigger_time timestamp(6) with time zone,
    add column if not exists close_price_assumption varchar(32);

-- A database built from the earlier reference SQL has no value checks on the enum columns and needs only the columns above.
-- A local database whose tables Hibernate created has one check per enum column, which `ddl-auto: update` never changes;
-- this extends each, keeping every value already allowed (REFERENCE_BELOW_MIN_MARKET_CAP from V20261023120000 included), so
-- no stored row is affected. Engine dip-paper-v4 stores TIME_EXIT and UNRESOLVED.

alter table positions
    drop constraint if exists positions_close_reason_check,
    add constraint positions_close_reason_check check (close_reason in ('TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT'));

alter table positions
    drop constraint if exists positions_exit_trigger_reason_check,
    add constraint positions_exit_trigger_reason_check check (exit_trigger_reason in ('TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT'));

alter table positions
    drop constraint if exists positions_close_price_assumption_check,
    add constraint positions_close_price_assumption_check check (close_price_assumption in ('NEXT_TRADE', 'STANDING_CURVE'));

alter table positions
    drop constraint if exists positions_exit_unavailable_check,
    add constraint positions_exit_unavailable_check check (exit_unavailable in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED'));

alter table paper_orders
    drop constraint if exists paper_orders_rejection_reason_check,
    add constraint paper_orders_rejection_reason_check check (rejection_reason in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED'));

commit;
