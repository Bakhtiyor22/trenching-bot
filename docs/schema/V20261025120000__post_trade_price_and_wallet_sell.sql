-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying engine
-- dip-paper-v5, the post-trade price measure and the wallet-sell rule (docs/adr/20261025-post-trade-price-and-wallet-sell.md).
-- Every column is nullable and additive, and no row is rewritten: an intent stored before it has no wallet and reads the wallet of
-- its own source event, no reference observation and no wallet sell.

begin;

-- wallet is the tracked wallet's buy that started the cycle; reference_observation_id is the market observation of that buy the
-- post-trade reference was read from; wallet_sell_event_id and wallet_sell_slot are the wallet's sell that ended the dip watch.
alter table trade_intents
    add column if not exists wallet varchar(44),
    add column if not exists reference_observation_id varchar(255),
    add column if not exists wallet_sell_event_id varchar(255),
    add column if not exists wallet_sell_slot bigint;

-- A database built from the earlier reference SQL has no value checks on the enum columns and needs only the columns above.
-- A local database whose tables Hibernate created has one check per enum column, which `ddl-auto: update` never changes;
-- this extends each, keeping every value already allowed (V20261023120000 and V20261024120000 included), so no stored row is
-- affected. Engine dip-paper-v5 stores WALLET_SOLD and REFERENCE_UNAVAILABLE as a watch outcome and a rejection reason, and
-- WALLET_SOLD as a close and exit trigger reason.

alter table trade_intents
    drop constraint if exists trade_intents_watch_check,
    add constraint trade_intents_watch_check check (watch in ('TRIGGERED', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE'));

alter table paper_orders
    drop constraint if exists paper_orders_rejection_reason_check,
    add constraint paper_orders_rejection_reason_check check (rejection_reason in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE'));

alter table positions
    drop constraint if exists positions_exit_unavailable_check,
    add constraint positions_exit_unavailable_check check (exit_unavailable in ('MISSING_OBSERVATION', 'STALE_OBSERVATION',
        'INVALID_QUALITY', 'INSUFFICIENT_LIQUIDITY', 'ALREADY_OPEN', 'ZERO_QUANTITY', 'NO_DIP', 'DIP_BELOW_MIN_MARKET_CAP',
        'REFERENCE_BELOW_MIN_MARKET_CAP', 'MIGRATED_BEFORE_ENTRY', 'UNSUPPORTED_SUPPLY', 'NO_FILL_AFTER_TRIGGER', 'EXCEEDS_CURVE',
        'HORIZON_REACHED', 'MIGRATED', 'NO_EXIT_FILL', 'UNRESOLVED', 'WALLET_SOLD', 'REFERENCE_UNAVAILABLE'));

alter table positions
    drop constraint if exists positions_close_reason_check,
    add constraint positions_close_reason_check check (close_reason in ('TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT', 'WALLET_SOLD'));

alter table positions
    drop constraint if exists positions_exit_trigger_reason_check,
    add constraint positions_exit_trigger_reason_check check (exit_trigger_reason in ('TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT',
        'WALLET_SOLD'));

commit;
