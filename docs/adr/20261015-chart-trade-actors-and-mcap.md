# Trade actors in replay dataset v4

Accepted 2026-10-15 as part 2 of the approved [chart trade actors and market-cap plan](../plans/20261015-chart-trade-actors-mcap.md), milestone 3 of the [capture, paper and chart improvement plan](../plans/20261011-capture-paper-chart-improvement-plan.md).

## Context

The chart should show who traded, not only the price. Every Pump.fun `TradeEvent` already names its `user`, side and both amounts, and the extractor decodes them, but schema v3 datasets keep only the price and curve state. Schema v3 is checksum-addressed and immutable: its bytes, checksum, run ids and meaning must not change.

## Decision

Schema v4 is new and v3 is untouched. Each v4 observation has a required `trade` object: `trader` (the event's `user`, never the fee payer or a router), `side` (`BUY`/`SELL`) and `solLamports` and `tokenRaw` as positive raw integer strings of at most u64. The format is in [REPLAY_FORMAT.md](../REPLAY_FORMAT.md).

The loader accepts 3 and 4; every other version still fails `UNSUPPORTED_SCHEMA`. A v4 observation without `trade` is refused (`TRADE_FIELDS_MISSING`), so is an invalid one (`TRADE_FIELDS_INVALID`), and a v3 file containing a `trade` key is refused (`TRADE_FIELDS_IN_V3`), so v3 meaning cannot be changed by editing. A loaded v3 dataset has no trade (`null`, never inferred). The v4 checksum appends the four trade values to each observation's parts; the v3 parts are unchanged, and the checksum already covers the schema version. The writer writes v3 exactly as before (catalog snapshots use it) and v4 with `trade`.

Export writes v4 only, in full and per case. It re-extracts from the verified local capture artifacts (no new RPC) and refuses with `TRADE_FIELDS_MISSING` rather than writing v3 if an observation lacks its trade. The observation `source` and `id` are unchanged, so replay inputs are identical; the extractor provenance enum gains `PUMPFUN_BONDING_CURVE_TRADE_V2`.

Replay does not change. Engine `dip-paper-v2`, its configuration hash, the strategy and the fill rules are untouched; `ReplayRunner` only accepts both versions, and no strategy, paper-trading or portfolio code reads `trade`. A test replays the bundled v3 sample and a v4 twin and requires equal engine, configuration hash and per-scenario economics.

Persistence adds four nullable columns to `market_observations`, all set or all null, enforced by the store service on write, by the mapper on read and by a CHECK constraint in the production SQL. Earlier rows read back with `trade == null`.

## Consequences

Re-exporting a capture gives a new checksum, dataset id and run ids, because the checksum covers the schema version and the trades. Existing v3 datasets and their runs stay valid and replayable, and v3 datasets show no trader data. Production applies `docs/schema/V20261015120000__market_observation_trades.sql` before deploying; there is no destructive change and no new dependency.
