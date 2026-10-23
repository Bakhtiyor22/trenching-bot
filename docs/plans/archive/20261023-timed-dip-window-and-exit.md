# Timed dip window and time exit (spec P7, P11, Section 8)

> Give dip-entry the spec's clocks: buy only on a dip within 30 s of Decu's buy, always sell at `min(fill + 3 min, Decu's buy + 4 min)` against the curve as it stands then, and cut `DECIDED` market captures with these clocks so each token is recorded for about 4–5 minutes instead of 16.

Status: Delivered 2026-10-24 (approved 2026-10-23 with the four Open Question recommendations; Option B after review)

## Context

Most new Pump.fun tokens live about 3 minutes, so the strategy has to act in seconds and get out before the token dies. `dip-entry` v1 (engine `dip-paper-v3`) has one clock instead: `horizonSlots` (4,500 slots, ~16 min) from Decu's first buy.

- **Dip watch** (`DipEntryStrategy.watch`): any trade at or below the dip level until the horizon end triggers. In run `a5f5…2674` the dip came ~12 min after the buy.
- **Holding** (`PositionService.close`): only take profit and stop loss end a position. Without either, it stays `OPEN` with `HORIZON_REACHED`; a trigger with no later trade stays open as `NO_EXIT_FILL`. On a token that stops trading, the position is never sold.
- **Market capture:** each token is recorded for its full 4,500 slots. The `DECIDED` cutoff (`DipOutcomeCutoff`, `SimulationConfig.mvp()`) usually decides an untriggered or open cycle only at the horizon end, so it saves little.

The agreed spec (`docs/strategy/decu-dip-v2-spec.md`):
- **P7, Section 6:** the buy is submitted at most 30.000 s after `t0`, the block time of Decu's buy (whole seconds; exactly 30 s counts; landing later counts).
- **P11, Section 8:** `exit_start = min(entry_confirmed + 3 min, t0 + 4 min)`. TP, SL and time are triggers, and the first wins. The position is sold 100%. `UNRESOLVED` is only for a sell that can't be made.
- **Section 11:** the market is recorded for 4 minutes from `t0` (`recordingWindowMs: 240000`), even when the signal is skipped, and extended while a position is unresolved.

On the Pump.fun bonding curve the curve itself is the counterparty: a sale never needs another trader. The curve at any moment is the post-trade curve of the last trade before it.

## Scope

### In scope
- An optional dip window (`dipWindowMs`) on the dip watch.
- An optional time exit (`maxHoldMs`, `hardExitStartMs`) as a third exit trigger, `TIME_EXIT`.
- Sales against the curve standing at the exit moment, so a dying token is still sold.
- `UNRESOLVED` only when no sale is possible.
- **Coverage end time:**
  - the block time where each market window's recording ends, recorded at capture;
  - market manifest version 5;
  - dataset schema v6.
- **The spec's configuration as a strategy-owned value** (`SimulationConfig.agreed()`). The console preset, the `DECIDED` cutoff and wallet + token qualification use it.
- **The `DECIDED` cutoff stops** once the agreed outcome is decided **and** the recording reaches `t0 + 240 s`.
- Engine `dip-paper-v4`. Console fields and the preset, plus a Codex brief for the frontend.

### Out of scope
- Section 8's retry economics: slippage steps, priority fee escalation, the fee cap, failed attempts. Paper sales land at the first attempt and pay one `transactionCostSol`. That's the unsupported rule `EXIT_RETRY_ECONOMICS`.
- P8, P2, P3, P13 and the Section 5 post-trade price.
- Pool trading after migration: a migration while holding stays `MIGRATED`.
- Sub-second timing (the spec accepts ±1 s).
- Changing `SimulationConfig.mvp()`, which stays the API and CLI default.
- Adding coverage end times to existing captures. They keep working; see rule 13.

## Key Decisions

1. **Time comes from block times, never slot counts.**
   - `t0` is the wallet buy's `blockTime`.
   - A trade's time is its `observedTime`.
   - The fill's time is its fill trade's `observedTime`.
   - The recording's end is the recorded coverage end time.

   All are whole-second `Instant`s. Slot still orders everything. This departs from trading-domain's "wall-clock time is metadata" (block time is on-chain and in the dataset, so it stays deterministic) and needs an ADR.
2. **The timed fields are optional; absent means today's behaviour.**
   - `SimulationConfig` gains `dipWindowMs`, `maxHoldMs` and `hardExitStartMs` (nullable `Long`). The exit pair is set together.
   - With none set, a run's outcomes equal `dip-paper-v3`'s, except rule 9's standing-curve sale. Old configuration snapshots still read.
3. **The configuration hash includes a timed field only when it's set**, so `mvp()`'s hash is unchanged.
4. **Engine `dip-paper-v4`.** Every run id changes once. v3 cutoffs reopen on resume (their identity changes anyway; see decision 10).
5. **Dip window:**
   - a trade can trigger only if `observedTime ≤ t0 + dipWindowMs`, inclusive;
   - the watch stops at the first trade later than that, and no trigger means `NO_DIP`;
   - the window gates the trigger, not the fill.
6. **Exit start:** `exitStart = min(fillTime + maxHoldMs, t0 + hardExitStartMs)`.
   - Trades with `observedTime < exitStart` are checked for TP and SL as today.
   - The time exit fires at `exitStart` itself, so TP and SL triggers are only checked on trades before `exitStart`. A trade at or after `exitStart` (same whole second included) is already after the exit started, so it never changes the reason.
7. **A time exit sells against the curve standing at `exitStart`:**
   - that's the post-trade curve of the last eligible trade with `observedTime < exitStart`, or the fill trade's when none followed it;
   - the sale is 100% of the raw tokens, through `BondingCurve.sell`, with the same fee, rounding and `transactionCostSol` as today;
   - the close references that curve's observation and is flagged `priceAssumption = STANDING_CURVE`.

   It's valid only when the recording **proves it covers `exitStart`**: a recorded trade of the pair with `observedTime ≥ exitStart` exists, or the coverage end time is at or after `exitStart`. Neither the "no silent fallback" rule nor the "no latest known price" rule is broken, because with complete coverage that curve is the market's real state, not a stale guess.
8. **A TP or SL exit sells as today**, at the first eligible trade at least `latencySlots` after the trigger.
9. **When no such trade exists, a TP or SL exit sells against the curve standing at slot `trigger.slot + latencySlots`:** the post-trade curve of the last trade before that slot. It's valid when the coverage reaches that slot (`throughSlot ≥ trigger.slot + latencySlots`, provable in slots). This replaces `NO_EXIT_FILL`, which leaves a dying token unsold, for every run, timed or not.
10. **`SimulationConfig.agreed()` in `strategy`:**
    - it holds the spec's values: 0.1 SOL, dip 2,000, TP 5,500, SL 4,000, min $10,000, latency 1, 0.001 SOL, `dipWindowMs` 30,000, `maxHoldMs` 180,000, `hardExitStartMs` 240,000, and horizon 4,500 as the recording bound;
    - the console preset is built from it.

    The `DECIDED` cutoff and qualification switch from `mvp()` to `agreed()`. Both must judge a capture with the same configuration, or every qualification of a cut capture is refused for coverage. This settles the preset ADR's Open Question 3 and replaces the caveat `CUTOFF_USES_LEGACY_DEFAULTS`.
11. **A `DECIDED` window is decided when both hold:**
    - the agreed outcome is decided within the prefix;
    - the recording reaches `t0 + recordingWindowMs` (240,000, spec Section 11) by its coverage end time.

    So every token keeps at least its first 4 minutes, even one skipped at the buy, and charts and reruns with other dip or market cap values still work inside those 4 minutes. A position whose exit starts at 4 minutes is decided once its sale is proven (decision 7), so a window typically stops at about `t0 + 4–5 min`.
12. **The coverage end time is read once per need:**
    - a new read `ChainCaptureReads.blockTime(slot)` returns the block time of the nearest produced block at or below the slot (`getBlockTime`, stepping back with `getBlocks` over a skipped slot);
    - it's counted in API usage as purpose `BLOCK_TIME`;
    - the capture calls it when a window's coverage completes, and the cutoff calls it, cached per slot, only when a decision waits on time.

    The nearest earlier block's time is never later than the slot's true time, so the proof is conservative.
13. **Existing captures and datasets have no coverage end time.** For them, a time exit is provable only by a later trade. On a token that stopped trading, the position stays `OPEN` with `HORIZON_REACHED` ("the recording can't show the exit moment"). Re-capturing gives the time.

### Amendment after review (2026-10-24)

Decision 9 let a take profit or stop loss sell into its next trade however late, up to the horizon end, and fall back to the standing curve only when no trade followed at all. The close then depended on trades long after the sale slot, while its decision slot was the sale slot, so a `DECIDED` capture could cut the window and report a different P&L than a `FULL` capture of the same token. The user chose Option B: **a take profit or stop loss sells into the next trade only if it lands within 60 s of the trigger; otherwise it sells against the curve standing at the sale slot.** This replaces decisions 8 and 9 and the take profit / stop loss parts of Domain Rules 8 and 11.

- **The limit:** `deadline = trigger.time + 60,000 ms`, spec Section 8's active phase (`exitRetry.activePhaseMs`), the engine constant `ExitSaleWindow.ACTIVE_PHASE_MS`. It applies to every run, timed or not, as engine `dip-paper-v4` semantics, and is not in the configuration hash (`mvp()`'s hash `be93…a329` is unchanged).
- **`NEXT_TRADE`:** the first eligible trade at or after `saleSlot = trigger.slot + latencySlots` whose `observedTime ≤ deadline` (inclusive, whole seconds) and whose curve can pay. Trades within the deadline that cannot pay are skipped; when some were tried and none paid, the position is `UNRESOLVED`.
- **`STANDING_CURVE`:** otherwise, against the post-trade curve of the last eligible trade with slot `< saleSlot` (normally the trigger). Valid only when the recording proves it reaches `saleSlot` and that no unseen trade can land within the deadline: a recorded trade of the pair (eligible or not) at or after `saleSlot` with `observedTime > deadline`, or coverage with `throughSlot ≥ saleSlot` and `throughTime > deadline` (strictly: an unseen later trade has a time at or after `throughTime`). Without the proof the position stays `OPEN` with `HORIZON_REACHED`. Datasets without coverage (v3, v4) count as covered through the horizon end with no time, so only a later trade proves their deadline.
- **Migration:** a curve completing before the sale (between the trigger and the proof) is `MIGRATED`, as before; a completed curve that is itself the proving trade comes after the standing-curve sale.
- **Decision slots:** a `NEXT_TRADE` close at its sale slot; a `STANDING_CURVE` take profit or stop loss, and the corresponding `UNRESOLVED`, at the slot proving the deadline (the first recorded trade past it, or the coverage end slot when `throughTime` proves it). The close's own slot stays the sale slot. These are prefix-stable: the outcome over the trades through the decision slot equals the outcome over the whole window. The `DECIDED` cutoff reads the prefix end's block time when an open take profit or stop loss waits on its deadline, as it does for a time exit's start.
- **The time exit's standing curve** also takes the trades later in the fill's own slot into account: it is the last eligible trade after the fill trade in canonical order, and a curve completing later in that slot is a migration.
- **The bundled sample:** the v3 sample's former `NO_EXIT_FILL` cycle has no later trade and no coverage end time, so it now stays `OPEN` with `HORIZON_REACHED` (its take profit trigger kept). The sample closes 2 of 10 cycles for `-0.013487824000` SOL.

## Module Contracts

- **`strategy.domain.SimulationConfig`** (additive):
  - `dipWindowMs: Long?`, `maxHoldMs: Long?` and `hardExitStartMs: Long?`;
  - the `of(...)` named parameters, and the bounds in `SimulationConfigBounds`: `dipWindowMs` 1,000..600,000; the exit pair 1,000..3,600,000;
  - `agreed(): SimulationConfig`.
- **`strategy.domain.TradeIntent`**: `+ t0: Instant?`.
- **`portfolio.domain`:**
  - `CloseReason + TIME_EXIT`;
  - `ExitTrigger + time: Instant`;
  - `Position + exitStart: Instant?`;
  - `PositionClose + priceAssumption: ExitPriceAssumption` (`NEXT_TRADE`, `STANDING_CURVE`).
- **`papertrading.domain.RejectionReason`**: `+ UNRESOLVED` (an exit reason, not in `ENTRY`).
- **`replay.domain.MarketCoverage`**: `+ throughTime: Instant?`. Dataset schema v6 writes it. v3–v5 load with null.
- **`marketdata`:**
  - `MarketWindowRecord + throughBlockTime` (manifest version 5; version ≤ 4 reads null);
  - `MarketCaptureCutoff.evaluate(window, observations, observedThrough, observedThroughTime: Instant?)`. This **changes a port signature** inside capture: `marketdata` owns it and `replay` implements it, and only those two modules are affected;
  - the cutoff may ask for the time through a supplied lookup.
- **`chaincapture.ChainCaptureReads`**: `+ blockTime(slot: Long): Instant?`, read-only.
- **Console HTTP:**
  - `ConsoleConfigRequest`, `ConfigResponse` and limits gain the three optional fields;
  - the preset response gains them;
  - run report positions gain `exitStart`, and each close gains `priceAssumption`.

  All additive.

**Contract changes needing agreement:** the replay dataset format (v6) and the `MarketCaptureCutoff` signature.

## Domain Rules

1. With no timed field set, every outcome equals `dip-paper-v3`'s, except that a TP or SL whose next trade is missing now closes against the standing curve (rule 9) instead of `NO_EXIT_FILL`.
2. A trigger trade at exactly `t0 + 30 s` triggers; one at `t0 + 31 s` never does. A below-level trade after the window gives `NO_DIP`, decided at that first later trade's slot (or the horizon end when there is none).
3. A timed config and a buy without `blockTime` gives `NO_DIP`, decided at the buy's slot.
4. `exitStart = min(fillTime + maxHoldMs, t0 + hardExitStartMs)`. When `t0 + hardExitStartMs ≤ fillTime`, the exit starts right after the fill: it sells against the fill trade's post-trade curve once coverage covers `exitStart`.
5. Before `exitStart`: price `≥ TP` is `TAKE_PROFIT` and `≤ SL` is `STOP_LOSS`. The first trigger fixes the reason; later crossings never change it.
6. A `TIME_EXIT` sale's curve is the last eligible trade with `observedTime < exitStart` after the fill (or the fill trade itself). Hand-checked: tokens out, gross, fee rounding up, net, P&L. The proceeds come from that curve even when trades after `exitStart` exist.
7. A `TIME_EXIT` closes only when coverage provably reaches `exitStart` (decision 7). Otherwise the position stays `OPEN` with `HORIZON_REACHED`.
8. *(Replaced by the amendment after review.)* TP or SL with a trade at least `latencySlots` after the trigger sells into that trade (`NEXT_TRADE`). Without one, and with `throughSlot ≥ trigger.slot + latencySlots`, it sells against the standing curve (`STANDING_CURVE`).
   Datasets without coverage (v3, v4) count as covered through each cycle's horizon end, as `DatasetCoverage` already treats them. That proves rule 8's slot. They have no coverage end time, so their time exits are proven only by a later trade (rule 7).
9. `BondingCurve.sell` returning null (the curve can't pay) means `OPEN` with `UNRESOLVED`. A completed curve before the sale means `OPEN` with `MIGRATED`. `NO_EXIT_FILL` is never produced by v4.
10. A timed run's position is `CLOSED` unless its reason is `UNRESOLVED`, `MIGRATED` or `HORIZON_REACHED`.
11. Decision slots:
    - `TIME_EXIT`: the slot proving `exitStart` is covered, which is the first trade at or after it, or the coverage end slot;
    - a standing-curve TP or SL: `trigger.slot + latencySlots` *(replaced by the amendment after review: the slot proving the 60 s deadline)*;
    - the rest unchanged.
12. **Cutoff:**
    - a window is decided only when its agreed outcome is decided within the prefix **and** the prefix's coverage end time is `≥ t0 + 240 s`;
    - the cutoff never decides inside the lead (unchanged);
    - a block time lookup failure or a missing time leaves the window undecided, and it continues in full;
    - it's logged `WARN` with `mint` and `slot`.
13. **Qualification** replays with `agreed()`. A capture cut under v3/`mvp()` that the agreed outcome needs past its cut is refused `MARKET_COVERAGE_ENDED`, as today, and the market step can be resumed.
14. **Determinism:** no clock is read in a run. Times come from the dataset. The block time read happens only at capture and is stored. The reorder/duplicate replay test is extended to a timed config.
15. **Idempotency** is unchanged: the run id is the hash of engine, dataset and config, and the ledger's unique run id guards reruns.
16. **Provenance:**
    - each intent carries `t0`;
    - each position `exitStart`;
    - each exit trigger its observation, slot, time and reason;
    - each close its curve observation and `priceAssumption`;
    - each window its `throughBlockTime`;
    - each cutoff its engine version and `agreed()` hash.

## Data Model

Additive, nullable columns:

| Table | Column | Type |
|---|---|---|
| `trade_intents` | `t0` | `timestamp with time zone` null |
| `positions` | `exit_start` | `timestamp with time zone` null |
| `positions` | `exit_trigger_time` | `timestamp with time zone` null |
| `positions` | `close_price_assumption` | `varchar(32)` null, `@Enumerated(STRING)` |

New enum values:
- `positions.close_reason` and `positions.exit_trigger_reason`: `+ TIME_EXIT`;
- `positions.exit_unavailable` and `paper_orders.rejection_reason`: `+ UNRESOLVED`.

`docs/schema/V20261024120000__timed_exit.sql` adds the columns for production and extends local Hibernate check constraints in place, keeping every existing value (as `V20261023120000`). `StoredRunConfiguration` gains the three optional fields with null defaults. The market manifest (v5) and the dataset (v6) are files, not tables. Nothing is destructive.

## Transactions & Integration

- Runs are unchanged: in memory, then one `SimulationRunTransaction`.
- **Capture gains one read-only RPC per window, plus cached reads during `DECIDED` evaluation:**
  - `getBlockTime`, and `getBlocks` over a skipped slot;
  - they go through the existing bounded, budgeted `ChainCaptureReads` with its timeouts, retries and the request budget;
  - a failure leaves the coverage time null (`WARN`), so the capture still completes and the time is read again on resume.
- No new properties. No live provider is needed for tests; fixtures and the recorded-RPC fakes suffice.

## What To Implement

1. **Config, agreed config, hash and engine version:**
   - `SimulationConfig` fields, bounds and `agreed()`;
   - the hash appends only the set fields;
   - `ENGINE_VERSION = "dip-paper-v4"`.
2. **Dip window and `t0`** in `DipEntryStrategy` and `ScenarioDecision` (rules 2–3).
3. **Exits in `PositionService`:**
   - `exitStart` and `TIME_EXIT`;
   - the standing-curve sales for time and for TP/SL;
   - `UNRESOLVED`;
   - `ScenarioDecision`, `DatasetCoverage` and `TargetTokenQualificationService` handle the new reasons.

   It needs `MarketCoverage.throughTime` (unit 4) for the time proof.
4. **Coverage end time:**
   - the `ChainCaptureReads.blockTime` read and its usage purpose;
   - market manifest v5 `throughBlockTime`, recorded when a window's coverage completes;
   - dataset v6 `MarketCoverage.throughTime` in the export, the loader and the checksum.
5. **The agreed-clock cutoff:**
   - `DipOutcomeCutoff` with `agreed()` and the 240 s rule;
   - the `MarketCaptureCutoff` signature with the observed-through time, and the time lookup in the capture service;
   - qualification with `agreed()`;
   - `CaptureCutoffModes`, recommendations and caveats updated.

   Depends on 1, 3 and 4.
6. **Persistence and console:**
   - entity columns, mappers and the stored report;
   - the reference SQL;
   - config DTOs, validator and limits;
   - `AgreedStrategyPreset` built from `agreed()`:
     - the new fields `AGREED` with spec refs P7, P11, P11;
     - `horizonSlots` `ENGINE_DEFAULT` with note `RECORDING_BOUND_ONLY`;
     - unsupported list 6: `DIP_WINDOW_30_SECONDS` and `TIMED_EXIT` removed, `EXIT_RETRIES_AND_UNRESOLVED` renamed `EXIT_RETRY_ECONOMICS`.

   Depends on 1–3.
7. **Codex brief** `docs/plans/20261024-timed-exit-codex-brief.md`:
   - the form fields (seconds shown, milliseconds sent);
   - cycle card labels (`TIME_EXIT`, `UNRESOLVED`, "Sold against the curve at the exit time");
   - the chart's exit-start marker;
   - the market cap help text "at Decu's buy";
   - the overlapping long numbers in the cycle card.

Units 2 and 4 can run in parallel after 1: they touch different modules. Unit 3 waits for 4's `MarketCoverage` change only.

## Verification

- `SimulationConfigTest`: bounds, the exit pair rule, `agreed()` equals the spec values, and `mvp()`'s hash is unchanged.
- `DipWindowTest`: triggers at 30 s and not at 31 s; a post-window dip gives `NO_DIP`; a buy without block time; the decision slot.
- `TimedExitTest` (`PositionService` with `OrderedMarketData` and coverage):
  - `min()` picks the hard exit;
  - a TP before `exitStart` keeps its reason;
  - a trade at `exitStart` is not a TP;
  - a time exit on an active token sells against the last curve before `exitStart`;
  - a time exit on a dead token, proven by coverage end time, closes;
  - the same without a coverage end time stays `HORIZON_REACHED`;
  - a TP with no next trade sells against the standing curve;
  - a curve that can't pay gives `UNRESOLVED`;
  - migration while holding gives `MIGRATED`;
  - one hand-checked `TIME_EXIT` P&L.
- `ReplayRunnerTest`:
  - untimed sample outcomes unchanged except `NO_EXIT_FILL` → standing-curve close (pinned values updated once, with the hand check of that cycle);
  - a timed config over `DipSampleV5`/v6.
- `DatasetCoverageTest`: the new decision slots.
- **Format:**
  - `ReplayDatasetLoaderTest` and writer tests: v6 round trip with `throughTime`; v3–v5 still load;
  - market manifest v5 round trip; v4 reads null.
- `DipOutcomeCutoffTest`:
  - undecided before `t0 + 240 s` even when the outcome is decided;
  - decided after it;
  - a lookup failure stays undecided;
  - a skip at the buy still records 4 min;
  - the identity is v4 plus the `agreed()` hash.
- `MarketHistoryCaptureService` tests with the fake RPC: the block time is read once per completed window, a skipped slot steps back, the request is counted under `BLOCK_TIME`, and a failure gives null and is retried on resume.
- `TargetTokenQualificationServiceTest` with `agreed()`.
- Console: `RunSetupControllerTest` and `AgreedStrategyPresetTest` (6 rules, the new fields, spec drift for the three values and `recordingWindowMs`).
- PostgreSQL integration: a timed run persists `t0`, `exit_start`, `exit_trigger_time`, `close_price_assumption` and `TIME_EXIT`, and reads back. A stored v3 run still reads.
- Command: `JAVA_HOME=C:/Users/user/.jdks/ms-21.0.10 ./gradlew build`, with the 5 known pre-existing failures unchanged.

## Documentation Sync

- **README:** the strategy rules, the preset rule count (6), engine v4, `DECIDED` mode now cut by the agreed clocks with a 4-minute minimum, and the capture's block time read.
- **New ADR** `docs/adr/20261024-timed-dip-window-and-exit.md`: block-time clocks, inclusive bounds, the time exit starting at `exitStart`, standing-curve sales and their coverage proof, `UNRESOLVED`, and the cutoff on `agreed()` with the 4-minute minimum.
- **Amendments:**
  - `20261008-dip-entry-strategy.md`;
  - `20261009-bonding-curve-execution.md` (the standing-curve sale);
  - `20261015-outcome-bounded-market-capture.md` (the cutoff configuration);
  - `20261016-agreed-strategy-run-preset.md` (Open Question 3 settled);
  - `20261022-market-window-lead.md` (manifest v5).
- **`docs/REPLAY_FORMAT.md`:** schema v6 and engine v4. **`docs/REAL_DATA_CAPTURE.md`:** the cutoff and the block time read.

## Acceptance Criteria

- A preset run on dataset `f572…3adc` (GPj2…RtqS, dip ~12 min after the buy) ends as `NO_DIP` with `t0 = 2026-10-22T15:59:48Z` stored on its intent.
- A preset run on a token that dips within 30 s and then stops trading closes as `TIME_EXIT` with `close_price_assumption = STANDING_CURVE` and `exit_start ≤ t0 + 240 s`. Its realized P&L matches a hand computation from the last curve before `exit_start`.
- An untimed `mvp()` run on `dip-sample-v3` keeps configuration hash `be93…a329` and its per-cycle outcomes, except the `NO_EXIT_FILL` cycle, which now closes against the standing curve.
- A new `DECIDED` capture of a token Decu buys stops recording at about `t0 + 4–5 min` instead of `S + 4500`. Its manifest records `throughBlockTime` for each window, and its export is schema v6.
- `GET /api/runs/preset` returns the three timed values `AGREED` and 6 unsupported rules.
- A run stored before this change still opens.
- `./gradlew build` shows only the 5 known pre-existing failures.

## Open Questions

1. **A time exit on an active token sells against the curve standing at `exitStart` and ignores trades landing in the one slot our sale needs.** That's optimistic by at most about a slot's trades. Recommendation: accept it. The spec accepts ±1 s, and the alternative (selling into the next trade) needs a slot for a moment in time that the data doesn't give.
2. **Captures cut by the agreed clocks hold about 4–5 minutes per token**, so a run with longer settings on them (for example a 15-minute hold) is refused `MARKET_COVERAGE_ENDED`. Recommendation: accept it. `FULL` mode still records the whole 4,500 slots when longer experiments are wanted.
3. **Existing captures have no coverage end time**, so a dead token's time exit there stays `HORIZON_REACHED`. Recommendation: accept it, and re-capture tokens worth studying. Backfilling block times would mean RPC calls against old captures.
4. **Standing-curve sales for TP/SL** (rule 8) also change untimed runs, removing `NO_EXIT_FILL`. Recommendation: yes. It's the same "never leave a sellable position unsold" fix, and it's provable in slots.

## Delivered

2026-10-24, uncommitted, in five implementation parts, one review and one fix part.

- **Strategy and engine:** `SimulationConfig` has the optional `dipWindowMs`, `maxHoldMs` and `hardExitStartMs` and `agreed()`. The configuration hash appends them only when set, so `mvp()`'s hash `be93…a329` is unchanged. The engine is `dip-paper-v4`. Intents carry `t0`, and the 30-second dip window is inclusive.
- **Exits:** a time exit at `min(fill + 3 min, t0 + 4 min)` sells against the standing curve once the recording proves the moment. A take profit or stop loss sells into the next trade **within 60 s of its trigger**, else against the curve standing at the sale slot (the review's Option B, see the amendment under Key Decisions). `UNRESOLVED` is used only when no curve can pay. The standing curve also counts trades later in the fill's own slot.
- **Capture:** a read-only block time read (`getBlockTime`, plus `getBlocks` over a skipped slot, purpose `BLOCK_TIME`); market manifest v5 `throughBlockTime`; replay dataset v6 `coverage.throughTime`. The `DECIDED` cutoff and qualification run on `agreed()`, keep at least `t0 + 240 s`, and read a block time lazily, only when a decision waits on time.
- **Persistence:** additive nullable `trade_intents.t0`, `positions.exit_start`, `positions.exit_trigger_time` and `positions.close_price_assumption`, plus the enum values `TIME_EXIT` and `UNRESOLVED`. `docs/schema/V20261024120000__timed_exit.sql` adds them for production and extends the local check constraints. It was applied to the local database on 2026-10-24.
- **Console:** the clock fields in config, validation and limits; the preset built from `agreed()` (13 fields, 6 unsupported rules); stored timed runs read back with their clocks; timelines and the chart place time-exit and standing-curve sales correctly. Frontend work is in `docs/plans/20261024-timed-exit-codex-brief.md`.

**Differences from the plan**
- Review finding: a standing-curve take profit or stop loss depended on trades through the horizon end while it was decided at the sale slot. The user chose the 60-second sale limit (Option B), which replaces Domain Rules 8 and 11 for take profit and stop loss.
- `ExitTrigger.time` is nullable, so positions stored before v4 don't get invented times.
- The `MarketCaptureCutoff` port takes a lazy time lookup rather than an eager time.
- The minimum recording constant is `ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING`.
- The `CUTOFF_DECIDED` caveat is renamed `CUTOFF_USES_AGREED_STRATEGY`, and `ADD_LEGACY_COMPARISON` gains `COMPARISON_MAY_NEED_FULL_CAPTURE`.
- The bundled v3 sample's former `NO_EXIT_FILL` cycle now stays open as `HORIZON_REACHED`: its dataset has no coverage time, and no later trade proves its 60 s. Its standing-curve P&L is hand-checked on a v6 twin instead.

**Docs updated:** README; the new ADR `20261024-timed-dip-window-and-exit.md`; amendments to the dip-entry, bonding-curve, outcome-bounded capture, agreed preset and market-window lead ADRs; `REPLAY_FORMAT.md`; `REAL_DATA_CAPTURE.md`.

**Not verified:** a live block time read, a fresh `DECIDED` capture, and the acceptance run on dataset `f572…3adc`. They need the provider and the running app.
