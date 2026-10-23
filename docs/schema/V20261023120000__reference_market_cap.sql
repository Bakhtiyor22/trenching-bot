-- Reference only: no Flyway runs this. Engine dip-paper-v3 stores the new value REFERENCE_BELOW_MIN_MARKET_CAP
-- (docs/adr/20261023-reference-market-cap.md). A database built from the earlier reference SQL has no value checks on
-- these columns and needs nothing. A local database whose tables Hibernate created has one check per enum column, which
-- `ddl-auto: update` never changes; this extends each, keeping every value already allowed so no stored row is affected.

begin;

alter table trade_intents
    drop constraint if exists trade_intents_watch_check,
    add constraint trade_intents_watch_check check (watch in ('TRIGGERED', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY'));

alter table paper_orders
    drop constraint if exists paper_orders_rejection_reason_check,
    add constraint paper_orders_rejection_reason_check check (rejection_reason in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL'));

alter table positions
    drop constraint if exists positions_exit_unavailable_check,
    add constraint positions_exit_unavailable_check check (exit_unavailable in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL'));

commit;
