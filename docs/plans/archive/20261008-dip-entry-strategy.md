# Dip-entry strategy

> Replace the +1/+2/+3 copy strategy: after Decu's first buy of a token, buy a 15% dip below his price if the market cap is at least $10k and the token is still on its bonding curve, then exit at +30% or −15%.

Status: Approved (2026-10-08, in chat, with the recommended answer to every open question)

## Context

Today every run uses `CopyBuyStrategy` ([original strategy decision](../../adr/0001-fixture-simulation-semantics.md)):

- Each of the tracked wallet's buys creates three intents at source slot +1/+2/+3.
- `FillModel` fills each at the first eligible observation within `maxWaitSlots`.
- `PositionService` exits after `holdingSlots` ([ADR 0001](../../adr/0001-fixture-simulation-semantics.md), [configurable runs](../../adr/20261002-configurable-replay-runs.md)).

Market capture records only the slots those rules need, [S+1, S+12] per buy (`ReplayMarketWindowPlanner`).

The user wants this strategy instead (verbatim):

```text
WAIT_FIRST_BUY  → Deku's first buy (record his price P0)
WAIT_DIP        → price ≤ P0 × 0.85 AND mcap ≥ $10k AND not migrated → BUY at Pe
IN_POSITION     → price ≥ Pe × 1.30 → SELL (TP)
                → price ≤ Pe × 0.85 → SELL (SL)
CLOSED
```

**Decisions already made in chat (2026-10-08):**
- **It replaces the copy strategy;** no per-run strategy choice.
- **30-minute horizon** after the first buy.
- **SOL→USD comes from rates stored in the dataset**, so a rerun gives the same result.
- **A trigger fills at the next trade after it,** with slippage and fees. A price that gaps through the stop fills at the real, worse price.

**Evidence, checked offline from the user's 2026-10-08 capture with no provider calls:**
- **The trade event carries the curve's reserves.** Pump.fun's `TradeEvent` has virtual and real SOL and token reserves after each trade, at offsets 105–136 of the event, inside the 233 bytes already verified. Decoded values are consistent: virtual SOL − real SOL = 30 SOL, and virtual tokens − real tokens = 279.9M, both Pump.fun's initial virtual reserves.
- **"Migrated" is readable.** Real token reserves reach 0 when the curve completes, and the token then migrates.
- **Market cap needs a fixed supply.** Pump.fun tokens have a fixed 1,000,000,000 supply with 6 decimals, so mcap = price (SOL per token) × 10⁹ × SOL/USD.
- **Decu's 10 buys span 3 tokens.** Only the first buy per token counts, so this sample gives 3 cycles.

## Scope

### In scope

- The dip strategy as the only strategy: a per-mint state machine, fills, TP and SL exits, outcome reasons, configuration and run identity.
- **Replay dataset schema v2:**
  - each observation records whether the bonding curve was complete after it;
  - the dataset carries SOL/USDT minute rates covering every observation.
- **Market capture over a horizon:** one window per mint, [S+1, S+horizon] after the wallet's first buy of it, with capture bounds raised to fit.
- **A user-run `captureReferenceRates` command** that records Binance SOLUSDT 1-minute klines for a market capture's minutes, as audited artifacts. Export joins them offline.
- Additive persistence columns, the console run form and the run detail.
- A new synthetic v2 fixture with hand-checked outcomes; the existing copy-strategy tests are rewritten or removed.

### Out of scope

- Keeping the copy strategy, or choosing between strategies per run.
- Trading after migration (PumpSwap). A position whose token migrates stays open with reason `MIGRATED`.
- More than one cycle per token; re-entry after a close.
- Live trading, alerts, or anything that signs a transaction.
- Changing the console's USDT display view. It keeps using Binance on request, and could later read the dataset's rates.

## Key Decisions

1. **One cycle per mint, started by the wallet's first decoded buy of that mint** in canonical order (slot, signature, instruction). Later buys of the same mint are recorded in ingestion as ignored (`LATER_BUY_SAME_MINT`), so the audit shows them.
2. **P0 is the wallet's own execution price:** SOL amount ÷ token amount from its decoded buy (fees excluded, as the decoder already does). It is not a later trade's price.
3. **Only trades in a later slot than the trigger count, for both watching and filling.** Trades in the source buy's slot are ignored. The fill is the first eligible trade in a slot after the trigger's.
   - Within one slot, the dataset orders trades by id, not by on-chain order. A "next trade" in the same slot could therefore be one that actually happened earlier.
   - Requiring a later slot is the conservative reading of "next trade after trigger".
4. **Pe is the execution price including slippage,** the price actually paid. TP = Pe × 1.30 and SL = Pe × 0.85 are measured against observed trade prices. This is conservative: TP needs a slightly bigger move.
5. **The exit fills at the first eligible trade in a later slot than the exit trigger,** at its price × (1 − slippage), with the fee on gross proceeds. A gap past SL fills at that real price, never at the level.
6. **The horizon bounds everything: 4,500 slots (≈30 minutes at 400 ms).** The trigger, fill, exit trigger and exit fill must all lie in (S, S+4,500]. Market capture uses the same horizon, so the data and the rule agree. Slots, not block time, define it, because capture works in slots.
7. **Migration.**
   - An observation whose trade left the curve's real token reserves at 0 is `curveComplete=true`. From that trade on, the mint is migrated.
   - Before entry, this ends the cycle (`MIGRATED_BEFORE_ENTRY`).
   - In position, the position stays `OPEN` with exit reason `MIGRATED`. No price after migration is captured, so no fill is invented.
8. **Market cap = trigger price × 10⁹ × the dataset's SOL/USDT rate for the trigger trade's minute** (the 1-minute open, as in the [USDT ADR](../../adr/20261005-sol-usdt-display-conversion.md)). It is checked only at the dip trigger.
   - A token whose base decimals are not 6, or whose source is not `pumpfun-bonding-curve-buy/v1`, is rejected `UNSUPPORTED_SUPPLY` rather than assumed to have 1B supply.
9. **Rates enter the dataset.** This supersedes the USDT ADR's "display only" boundary for this one use, so it needs a new ADR. Rates are captured by the user, stored with hashes, written into the dataset and covered by its checksum. A run never calls Binance.
10. **The copy strategy's code, tests and default configuration are removed.** Old stored runs cannot be shown faithfully by the new run page, so the recommendation is a local database reset (Open Question 1).

## Module Contracts

**Breaking changes, all inside this one repository; both developers' agreement is assumed through the user's decision:**

- **`strategy.domain.SimulationConfig`** becomes:
  - `notional`, `feeBps`, `slippageBps`, `maxAgeSlots`, `quoteMint`, unchanged;
  - `dipBps` (default 1500), `takeProfitBps` (3000), `stopLossBps` (1500), `minMarketCapUsd` (`BigDecimal`, 10000), `horizonSlots` (4500).

  `maxWaitSlots` and `holdingSlots` are removed. The strategy identity is `dip-entry` / `1`.
- **`strategy.application.DipEntryStrategy`** replaces `CopyBuyStrategy`. Its signature is `fun cycles(trades: List<WalletTradeObserved>, runId, configHash, config): List<DipCycle>`, which picks the first buy per mint and computes P0.
- **`strategy.domain.TradeIntent`:**
  - `entryOffset` stays and is 0;
  - `targetSlot` is the trigger slot, or S+1 when there is no trigger;
  - it adds `referencePrice` (P0), `dipPrice`, `triggerObservationId?`, `triggerSlot?`, `triggerMarketCapUsd?` and `triggerRate?`.
- **`papertrading`:**
  - `FillModel.fill` takes the trigger instead of a target slot and fills at the first eligible observation in a later slot within the horizon;
  - `RejectionReason` gains `NO_DIP`, `DIP_BELOW_MIN_MARKET_CAP`, `MIGRATED_BEFORE_ENTRY`, `NO_FILL_AFTER_TRIGGER` and `UNSUPPORTED_SUPPLY`.
- **`portfolio`:**
  - `PositionService.close` evaluates TP and SL over the horizon;
  - `CloseReason` becomes `TAKE_PROFIT` or `STOP_LOSS`;
  - exit-unavailable reasons gain `HORIZON_REACHED`, `MIGRATED` and `NO_EXIT_FILL`;
  - `Position` adds `takeProfitPrice`, `stopLossPrice`, `exitTriggerObservationId?` and `exitTriggerSlot?`.
- **`marketdata.domain.MarketObservation`** adds `curveComplete: Boolean`, and the extractor sets it from `TradeEvent.real_token_reserves == 0`.
  - The new `marketdata.ReferenceRate(minute: Instant, rate: BigDecimal, source: String)` is a value type.
  - Read-only port: `ReferenceRates.rate(minute): BigDecimal?`.
- **`replay.domain.ReplayDataset`:** `schemaVersion = 2`, plus `referenceRates: List<ReferenceRate>`.
  - The loader accepts only v2 and refuses v1 with `UNSUPPORTED_SCHEMA` ("re-export with rates").
  - The writer, checksum and catalog cover the rates.
- **`replay.application.ReplayRunner`:** engine version `dip-paper-v1`, and a new configuration hash input list.
- **`ReplayMarketWindowPlanner`:** one window per mint from that mint's first buy, `[S+1, S+horizonSlots]`. Market manifest version 2 records `horizonSlots`, which replaces the offset/wait/holding parameters.
- **New user-run command `captureReferenceRates`** (in `replay.api`, behind a `marketdata` port), plus offline `inspectReferenceRates`.
  - `exportRecordedReplay` gains `-PratesCaptureDirectory`.

## Domain Rules

1. For each mint, only the earliest accepted buy by the tracked wallet starts a cycle. A later buy of the same mint produces no intent and is ingested as `LATER_BUY_SAME_MINT`.
2. P0 = (|quoteDelta| / 10^quoteDecimals) ÷ (baseDelta / 10^baseDecimals), at `DecimalPolicy.price` (scale 38, HALF_EVEN).
   - Dip level = P0 × (1 − dipBps/10⁴), TP = Pe × (1 + takeProfitBps/10⁴) and SL = Pe × (1 − stopLossBps/10⁴), all at `DecimalPolicy.price`.
   - The comparisons are `price ≤ dip`, `price ≥ TP` and `price ≤ SL`, using `compareTo`.
3. Market cap (USD) = price × 1,000,000,000 × rate(minute of `observedTime`), at `DecimalPolicy.scale` (12, HALF_EVEN). It is eligible when ≥ `minMarketCapUsd`.
4. Eligible observation: the existing quality rules (positive price, not `INVALID`, not `PROCESSED`, `priceSlot ≤ slot`, age ≤ `maxAgeSlots`), of the cycle's pair, and with S < slot ≤ S + horizon.
5. **Dip trigger:** the first eligible observation, in canonical order (slot, id), satisfying all of:
   - price ≤ dip level;
   - mcap ≥ min;
   - no `curveComplete` observation of the mint at or before it.
6. **Without a trigger**, the outcome is, in this order:
   - `MIGRATED_BEFORE_ENTRY` if the curve completed within the horizon;
   - otherwise `DIP_BELOW_MIN_MARKET_CAP` if some observation was ≤ dip level but every such observation had mcap < min;
   - otherwise `NO_DIP`.
7. **Entry fill:** the first eligible observation with slot > trigger slot.
   - If a `curveComplete` observation comes first, or there is none within the horizon, the result is `NO_FILL_AFTER_TRIGGER`.
   - Pe = price × (1 + slippage), quantity = notional / Pe (scale 12), fee = notional × fee, cost basis = notional + fee. This is the existing math.
8. **Exit trigger:** the first eligible observation with slot > fill slot and price ≥ TP (`TAKE_PROFIT`) or ≤ SL (`STOP_LOSS`).
   - One price cannot satisfy both, since TP > SL.
   - The trigger observation is never the fill.
9. **Exit fill:** the first eligible observation with slot > exit trigger slot. Its price × (1 − slippage), gross = quantity × that price, fee on gross, net = gross − fee, P&L = net − cost basis (scale 12).
10. **Open positions:** a position stays `OPEN`, with:
    - `MIGRATED`, if a `curveComplete` observation precedes the exit fill;
    - `NO_EXIT_FILL`, if there was an exit trigger but no later-slot observation within the horizon;
    - `HORIZON_REACHED`, if there was no exit trigger.
11. A dataset observation of a cycle's mint with no rate for its minute makes the dataset invalid; the loader refuses it (`REFERENCE_RATE_MISSING`). Export refuses earlier, so a run never meets a missing rate.
12. **Determinism.** No clock and no network in a run. The run id is the hash of the engine version, dataset checksum and configuration hash. The same dataset and configuration always give the same `resultChecksum`.
13. **Idempotency.** Starting the same run twice returns the existing run, as today. It is enforced by `uq_simulation_runs_identity`, and by `uq_trade_intents_scenario` with `entry_offset = 0`, which leaves one intent per source buy.
14. **Provenance.** Each intent keeps P0, the dip level and its trigger (observation id, slot, mcap, rate). Each position keeps TP, SL and the exit trigger's observation and slot. Fills and closes keep the observation ids as today.
15. **Negative cases, each with a test:**
    - a later buy of the same mint is ignored;
    - mcap below the minimum at every dip;
    - migration before the dip;
    - migration between trigger and fill;
    - migration in position;
    - a gap through SL fills below SL;
    - a TP trigger with no later trade;
    - the horizon ends while waiting, and while in position;
    - same-slot trades never fill;
    - base decimals other than 6 give `UNSUPPORTED_SUPPLY`;
    - a v1 dataset is refused;
    - a missing rate is refused;
    - a zero-quantity fill is rejected, as today;
    - a rerun returns the identical run.

## Data Model

All additive, nullable columns (`ddl-auto: update` adds them locally). Production needs [`docs/schema/V20261008120000__dip_entry.sql`](../../schema/V20261008120000__dip_entry.sql), applied by hand:
- **New columns:**
  - `trade_intents`: `reference_price`, `dip_price`, `horizon_end_slot`, `watch`, the five `trigger_*` columns;
  - `positions`: `take_profit_price`, `stop_loss_price`, the three `exit_trigger_*` columns;
  - `market_observations`: `curve_complete`.
- **Constraint changes:** `ck_trade_intents_slots` and `ck_positions_scenario` must accept `entry_offset = 0`. The Flyway-era checks allowed only 1..3, and Hibernate neither creates nor validates them.

- No column is dropped or renamed. `entry_offset` stays and is 0 for dip intents. The existing enum columns (`close_reason`, `exit_unavailable` and `rejection_reason`, each `varchar(32)`) fit the new values.
- **Old runs** (copy strategy) keep their rows, but the new run page cannot show them faithfully. **Recommended:** reset the local database (`docker compose down -v`, approval required). The rollback note: datasets re-import from their files and runs are recomputed. See Open Question 1.
- **Size.**
  - `ReplayLimits.MAX_INPUT_RECORDS` rises from 10,000 to 100,000 observations, and the scenario bound becomes 1 per transaction.
  - `spring.servlet.multipart.max-file-size` / `max-request-size` rise from 20 MB to 100 MB (`application.yml`).
  - A run persists every observation as today, which is up to 100k rows per run. That is accepted at research scale; the existing `(run_id, …, slot)` index serves it.

## Transactions & Integration

- Run persistence is unchanged: one transaction per run, and no remote call inside it.
- **Market capture, user-run.** Bounds rise:
  - `maxTransactions` to 50,000;
  - `requestBudget` to 60,000;
  - `byteBudget` to 4 GiB;
  - `scanPages` stays at 500 per window.

  These are totals across resumed runs, as today. Pacing, retries and checks are unchanged, at about 0.5 s per request in the user's runs.
- **Cost estimate.** In this capture, Decu's tokens traded about 1–5 times per slot near his buys.
  - Over 4,500 slots, expect roughly 2,000–15,000 trades per token. That means as many requests, 75–550 MB of artifacts, and 15 minutes to 2 hours per token, resumable.
  - This is the main cost of the strategy (Open Question 2).
- **Reference rate capture, user-run.**
  - It reads the verified market capture, collects the minutes of every in-window trade, and fetches Binance `GET /api/v3/klines?symbol=SOLUSDT&interval=1m`. That is one request per contiguous run of minutes, so about one per token.
  - It writes the raw responses as hashed artifacts and a manifest (`rates-manifest.json`), with the same lock, atomic write, budget and resume rules.
  - Public endpoint, no key. Timeouts are 5 s connect and 10 s read. HTTP 429/418 → `RATE_LIMITED` and 451/403 → `REGION_BLOCKED`, both reported and resumable.
  - The base URL must be https.
  - The agent never runs it.
- Export stays offline. It refuses `REFERENCE_RATES_INCOMPLETE` if any in-window trade's minute has no rate.
- **Configuration:** run defaults live in `SimulationConfig`, and the console validator bounds them:
  - dip, TP and SL each 1–9,999 bps;
  - min mcap 0–10⁹ USD;
  - horizon 1–4,500 slots, no more than the capture's.

  No new Spring properties apart from the multipart size.

## What To Implement

1. **Dataset v2 and curve state** (`marketdata`, `replay`):
   - `curveComplete` from the event's real token reserves;
   - reference rates in the dataset;
   - loader, writer, checksum and catalog;
   - raised record bounds;
   - a new synthetic v2 fixture.
2. **Dip engine** (`strategy`, `papertrading`, `portfolio`, `replay`):
   - the state machine, fills, exits, reasons, configuration and run identity;
   - removal of the copy strategy;
   - the hand-checked fixture tests.

   Depends on 1.
3. **Persistence:** additive columns, mappers and stores; persisted replay end-to-end on PostgreSQL. Depends on 2.
4. **Capture over the horizon:** the planner (first buy per mint, horizon window), market manifest v2 and raised bounds. Depends on 2's configuration; can run in parallel with 3.
5. **Reference rates:** `captureReferenceRates` / `inspectReferenceRates` and the export join. Depends on 1; parallel with 3–4.
6. **Console:** API and frontend.
   - The run form gets dip/TP/SL/min-mcap/horizon fields.
   - The run detail gets one card per token: P0 and dip level, the trigger with mcap and rate, entry, TP/SL levels, exit trigger and fill, outcome and P&L.
   - The chart draws P0, dip, TP and SL lines and the trigger and fill markers.

   Depends on 3.
7. **Docs:** see Documentation Sync.

## Verification

- **`DipEntryStrategyTest`:** first buy per mint, P0 math, `LATER_BUY_SAME_MINT`.
- **`DipEngineTest`,** on the synthetic v2 fixture, hand-checked:
  - one TP cycle with exact Pe, TP and P&L;
  - one SL cycle with a gap fill below SL;
  - `NO_DIP`, `DIP_BELOW_MIN_MARKET_CAP`, `MIGRATED_BEFORE_ENTRY`, `NO_FILL_AFTER_TRIGGER`;
  - `MIGRATED`, `HORIZON_REACHED` and `NO_EXIT_FILL` in position;
  - same-slot exclusion;
  - `UNSUPPORTED_SUPPLY`;
  - a deterministic `resultChecksum`.
- **Extractor test:** `curveComplete` is false on the user's captured events (real token reserves > 0), and true on a fixture event with real token reserves 0.
- **Dataset tests:** v2 round trip, checksum covering rates, v1 refused, missing rate refused, 100k bound.
- **Planner test:** one window per mint from its first buy, `[S+1, S+4500]`; manifest v2 parameters.
- **Rate capture tests,** with `MockRestServiceServer` and no network:
  - minute grouping, artifacts and hashes, resume;
  - 429/451 handling;
  - export join and `REFERENCE_RATES_INCOMPLETE`.
- **Persisted replay integration test** (Testcontainers): the fixture run persists the hand-checked rows; a rerun is idempotent; new columns round-trip.
- **Console:** controller and validator tests; frontend Vitest for the form fields, the per-token card and the chart lines.
- Commands: `./gradlew test`, plus `npm --prefix frontend run test` / `lint` / `build`.
- **User-run, after delivery:** market capture with the horizon for `decu-pumpfun-buys`, `captureReferenceRates`, export v2, then import and run in the console.

## Documentation Sync

- **New ADR `20261008-dip-entry-strategy`.** It records the state machine and every decision above, supersedes ADR 0001's strategy, fill and exit rules and the configurable-runs ADR's offset, wait and holding parameters, and records the hand-checked example.
- **New ADR `20261008-reference-rates-in-dataset`.** It amends the USDT display ADR: rates enter the dataset for the mcap rule only.
- **README:** strategy, configuration, run form, capture steps.
- **`REAL_DATA_CAPTURE.md`:** horizon windows, cost, `captureReferenceRates`, export v2.
- **`WEEKLY_EXECUTION_PLAN.md` §9:** the strategy decisions are resolved.
- **`IMPLEMENTATION_STATUS.md`.**

## Acceptance Criteria

- The synthetic fixture run reports exactly the hand-checked outcomes (TP, SL with a gap fill, and each rejection or open reason), with fixed values for Pe, TP, SL, P&L and `resultChecksum`.
- A v1 dataset import is refused with `UNSUPPORTED_SCHEMA`. A v2 dataset missing one minute's rate is refused with `REFERENCE_RATE_MISSING`.
- The planner gives `decu-pumpfun-buys` 3 windows (one per mint), each `[S+1, S+4500]`.
- A persisted run stores one intent per token with P0 and its trigger, positions with TP and SL, and observations with `curve_complete`. Starting it again creates no rows.
- The console run form shows dip/TP/SL/min-mcap/horizon with the defaults 15/30/15/10,000/4,500, and the run page shows one card per token.
- All backend and frontend tests pass.

## Progress

- **2026-10-08, units 1-7 implemented.**
  - **Data:** dataset v2 (`curveComplete`, `referenceRates`, loader/writer/checksum, 100,000-record bounds).
  - **Engine:** `DipEntryStrategy`, next-slot `FillModel`, take-profit and stop-loss `PositionService`, engine `dip-paper-v1`; the copy strategy is removed.
  - **Persistence:** additive columns.
  - **Capture:** one horizon window per mint, raised bounds, market manifest v2, detail checkpoints every 100 transactions, a 64 MiB manifest read cap.
  - **Rates:** `captureReferenceRates` / `inspectReferenceRates` and the export join.
  - **Console:** dip API fields, form and cycle cards; the chart draws P0, dip, take-profit and stop-loss levels.
  - **Docs:** ADRs and guides synced.
- **Hand-checked sample.** `dip-sample-v2` (checksum `61e0d9b1...8471`) gives:
  - take profit +32.157983339118 and a gapped stop loss -38.283768656717, for a total of -6.125785317599 (at notional 100; at the 0.1 SOL default set on 2026-10-09, +0.032157983339, -0.038283768656 and -0.006125785317);
  - 5 entries, 2 closes and 3 open positions (migrated, horizon, no exit fill);
  - 5 rejections (no dip, low market cap, migrated before entry, no fill, unsupported supply);
  - one later buy ignored.
- **Deviations from the contract text:**
  - The intent also stores `horizon_end_slot`, `watch` and `trigger_price`, and the position stores `exit_trigger_reason`, so both round-trip.
  - `trigger_rate` is `numeric(78,38)`.
  - Trigger prices and rates are kept at scale 38.
  - Exits also skip a trade whose known liquidity cannot cover the sale, as entries do.
  - The rate capture stores each minute's parsed open in `rates-manifest.json`, not raw response bodies.
  - The real Decu v1 dataset and its copy-strategy integration test were removed, since v1 no longer loads; the USDT tests use the dip sample.
- **Verification:**
  - backend `./gradlew test`: 385 tests pass;
  - frontend: 78 Vitest tests pass, lint is clean and the build succeeds.
- **Review (2026-10-08):** the `backend-reviewer` found no blockers and independently reproduced the sample's values. Fixed:
  - the production SQL now lists all 15 columns with their real types, plus the two CHECK constraints that rejected `entry_offset = 0` ([`docs/schema/V20261008120000__dip_entry.sql`](../../schema/V20261008120000__dip_entry.sql));
  - open positions are counted by reason on the run page (`Open: 1 migrated · 1 horizon reached`). These are computed from the run's cycles, so `simulation_runs` gains no column;
  - rate capture has tests: minute grouping, resume of missing minutes only, rate limits, resume identity, refusals, forged manifests;
  - an extractor test covers a trade that completes the curve;
  - the dashboard lists only entry rejection reasons (`RejectionReason.ENTRY`);
  - the capture guide notes that a kill between checkpoints refetches and recounts up to 99 transactions.
- **Accepted as is:** a curve that completes in the wallet's own buy slot, after its buy, reads `NO_DIP` rather than `MIGRATED_BEFORE_ENTRY`, because that slot is not watched.
- **Open:**
  - the local database reset (approved: `docker compose down -v`);
  - the user's first horizon market capture, rate capture and v2 export.

## Open Questions

Resolved 2026-10-08: the user chose the recommendation for all four.

1. **Old runs.** Reset the local database (`docker compose down -v`) after delivery? Old copy-strategy runs then disappear; datasets re-import from their files. *Recommendation:* yes. Keeping them would need a second, read-only run page for the old strategy.
2. **Capture size.** A 30-minute window can be 2,000–15,000 transactions per token: hours of capture and hundreds of MB of artifacts. Accept the raised bounds, or shorten the horizon for the first test? *Recommendation:* accept, and capture one token first to measure.
3. **Same-slot trades.** Rule 3 ignores trades in the trigger's own slot. On busy tokens that delays the fill by one slot (about 400 ms). *Recommendation:* keep it; on-chain ordering within a slot is not in the dataset. Recording the transaction index would allow "next trade in the same slot" later.
4. **A migrated position.** It stays open with no P&L. Alternatively, it could exit at the last curve trade's price (optimistic, since PumpSwap's price can differ). *Recommendation:* keep it open and count it separately in the run totals.


## Delivered

2026-10-10: Dip-entry triggers, bounded market windows, reference rates and the console shipped. Implementation and accepted differences are recorded above; docs include README, REPLAY_FORMAT, REAL_DATA_CAPTURE and the dip-entry/reference-rate ADRs. Bonding-curve execution supersedes its original execution assumptions.
