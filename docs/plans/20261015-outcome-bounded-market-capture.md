# Outcome-bounded market capture

> Fetch a window's trades oldest first and stop as soon as the dip-entry outcome can no longer change; datasets record how far each window was observed, and a replay that needs more is refused instead of reading the gap as "no trades".

Status: Approved (2026-10-15, all recommended answers to the open questions accepted)

## Context

A wallet-token capture plans one market window `[S+1, S+4500]` after the proven first buy S (`MarketPlanGate`, `ReplayMarketWindowPlanner`). `MarketHistoryCaptureService` lists the mint newest first until the window is covered (`MarketWindowScan`), then fetches **every** selected transaction (`fetchDetails`, `DetailBatches`, 100 per checkpoint, in listing order, so newest first). The cost is one `getTransaction` per trade: 2,000–15,000 per busy window, 15 minutes to 2 hours per token (`docs/REAL_DATA_CAPTURE.md`, Cost). Export (`RecordedReplayExportService`) then requires every selected transaction captured, rates are captured for every trade minute, and qualification (`TargetTokenQualificationService`) replays the dataset with `SimulationConfig.mvp()`.

The replay rarely needs the whole window. Under the dip-entry rules ([dip-entry ADR](../adr/20261008-dip-entry-strategy.md), [bonding-curve execution ADR](../adr/20261009-bonding-curve-execution.md)) a cycle ends at the first of: take profit or stop loss filled, curve completion (migration), an entry the curve cannot fill, or the horizon. Every decision is causal: it reads only trades up to its own slot. A take profit two minutes after the buy makes the remaining ~28 minutes of trades irrelevant, and after a migration the mint keeps trading on PumpSwap, which the capture fetches but the strategy never uses. The tracked wallet's own sells are not part of the strategy, so stopping at his exit is not a valid rule; stopping at *our* decided outcome is.

There is also a latent gap this fixes. Datasets do not record which slots were observed, so a run with a longer horizon than the capture (`docs/REAL_DATA_CAPTURE.md`: "a horizon longer than the captured one needs a new market capture") silently reads the missing slots as an absence of trades (`NO_DIP`, `HORIZON_REACHED`).

## Scope

### In scope

- Detail fetching oldest first for every market capture.
- An optional, strategy-agnostic cutoff port on market capture; a decided window marks its later transactions `NOT_NEEDED`.
- The dip-entry cutoff evaluator, implemented by running the real `ReplayRunner` on the captured prefix.
- Replay dataset schema v5 with per-window observed coverage; export writes it; runs and qualification refuse a scenario decided beyond its coverage.
- Extending a decided window to the full window without relisting.
- Console and CLI switches, docs, and a Codex brief for the console UI.

### Out of scope

- Any change to the dip-entry rules, fills, exits, P&L, the engine version or the hashes of v3/v4 datasets.
- A cutoff evaluator for the draft `decu-dip` v2 strategy ([plan](20261015-decu-dip-v2-replay.md)); the coverage contract below is engine-agnostic so it can add one later.
- Copying the tracked wallet's sells.
- Cheaper discovery (listing still pages from the newest signature back to S).
- Persisting capture-time rates; the rates stage stays the source of dataset rates.

## Key Decisions

1. **Decide with the replay itself, never a second copy of the rules.** The evaluator builds an in-memory dataset from the captured prefix and calls `ReplayRunner.run` with the cutoff configuration. Strategy, fill and exit logic exist once. *Rejected:* a hand-written "is it closed yet" check inside marketdata (duplicates the rules and drifts).
2. **A decided outcome has a decision slot.** Each scenario's `decidedAt` is the last slot whose trades its outcome depends on:
   - `TAKE_PROFIT`/`STOP_LOSS`: the close's slot;
   - `EXCEEDS_CURVE`, `ZERO_QUANTITY`: the fill observation's slot;
   - `MIGRATED_BEFORE_ENTRY`, `MIGRATED`, and `NO_FILL_AFTER_TRIGGER` caused by migration: the first `curveComplete` observation's slot;
   - `UNSUPPORTED_SUPPLY`: S (no market data is needed);
   - `NO_DIP`, `DIP_BELOW_MIN_MARKET_CAP`, `HORIZON_REACHED`, `NO_EXIT_FILL`, and `NO_FILL_AFTER_TRIGGER` without migration: the horizon end.

   Because the replay is causal, the outcome over observations `[S+1, D]` equals the outcome over the full window whenever `decidedAt ≤ D`.
3. **Coverage is part of the dataset, and an undecided run is refused.** Schema v5 adds `coverage`: one entry per window, `{baseMint, quoteMint, fromSlot, throughSlot}`. A run whose scenario has `decidedAt > throughSlot` is refused with `MARKET_COVERAGE_ENDED`, naming the mint, `decidedAt` and `throughSlot`. Nothing is stored. The same check is the evaluator's "undecided" signal. v3/v4 datasets carry no coverage and replay exactly as today. *Rejected:* a new `COVERAGE_ENDED` outcome stored in runs. It would add a value to `DipWatchOutcome`/`RejectionReason`, change persisted intent semantics, and make a partial result look like a strategy outcome.
4. **The capture-time decision is only an optimisation; correctness is re-checked offline.** Capture-time rates come from the same SOL/USDT source as the rates stage, but are not stored. Export and qualification replay with the recorded rates, and Decision 3 refuses any disagreement. A wrong cutoff can therefore only produce "extend the capture", never a wrong verdict.
5. **Prefixes are slot-complete.** Within-slot order is not recorded (dip-entry ADR). So a prefix ends at `D = min(toSlot, slot of the oldest selected record not CAPTURED − 1)`: every selected transaction in `[fromSlot, D]` is captured. A gap stops the prefix before its slot. Slots with no listed signature need no fetch, because discovery is complete.
6. **A decided window's manifest is a function of the decision slot X only.** Records with slot ≤ X stay `CAPTURED`. Every record with slot > X that no other still-undecided window needs becomes `NOT_NEEDED` with no artifact, even if this run already fetched it. Its file is left unreferenced, as after a kill between checkpoints today. So concurrency, batch boundaries and interruptions cannot change the manifest.
7. **Oldest-first fetching for all market captures.** Unresolved records are fetched in `(slot, signature)` order. The manifest's record order (window order) and the order details are applied in are unchanged, so full captures keep identical bytes. The evaluator runs after each checkpointed batch of 100, so at most 99 fetches past X are wasted.
8. **Cutoff configuration is `SimulationConfig.mvp()`** for the console wallet-token flow, because that is what qualification replays. The window stores the cutoff's configuration hash and engine version for audit.
9. **Extending reuses the listing.** Discovery already holds every in-window signature. Extending (mode `FULL`) clears the window's cutoff, turns its `NOT_NEEDED` records back to `PENDING` and fetches them oldest first. No listing request is made, and the result equals a capture that ran in full mode from the start.
10. **Defaults.** The console wallet-token market attempt defaults to `DECIDED`. The wallet-references scope and the CLI default to `FULL`, so "the CLI defaults remain unchanged" holds. The CLI opts in with `-PcutoffMode=DECIDED`. Without a configured rate source, `DECIDED` is refused up front with `RATES_NOT_CONFIGURED`.

## Module Contracts

**marketdata (public, additive):**

- `fun interface MarketCaptureCutoff { fun evaluate(window: MarketCaptureWindow, observations: List<MarketObservation>, observedThrough: Slot): CutoffDecision }`. It receives the window's canonical observations in `[fromSlot, observedThrough]`.
- `sealed interface CutoffDecision { data object Undecided; data class Decided(val slot: Slot, val outcome: String) }`, where `slot ≤ observedThrough`.
- `data class CutoffIdentity(val engineVersion: String, val configurationHash: String)`.
- `MarketHistoryCaptureRequest` gains `cutoffMode: MarketCutoffMode = FULL` (`FULL`, `DECIDED`), `cutoff: MarketCaptureCutoff? = null` and `cutoffIdentity: CutoffIdentity? = null`. `DECIDED` requires both. The mode is not part of the resume identity; a cutoff identity different from a decided window's stored one reopens that window (Domain Rule 12).
- `MarketWindowRecord` gains `cutoff: MarketWindowCutoff?` (`throughSlot`, `outcome`, `engineVersion`, `configurationHash`). `MarketTransactionOutcome` gains `NOT_NEEDED`.
- `MarketWindowDecodeRecord` and `MarketCaseProgress` gain `observedThrough: Slot` (`cutoff.throughSlot` or `toSlot`).

**chaincapture (additive):** `DetailBatches.run` gains `until: (S) -> Boolean = { false }`, checked after each checkpoint; `true` ends the phase without recording a stop reason.

**replay (public dataset contract: CHANGE).** Replay fixture schema v5: `ReplayDataset` gains `coverage: List<MarketCoverage>` (empty for v3/v4), `CURRENT_SCHEMA_VERSION = 5`, `SUPPORTED_SCHEMA_VERSIONS = {3, 4, 5}`. `DatasetFingerprint` covers coverage for v5 only. This changes the replay fixture schema and needs both developers' agreement.

**replay (internal):** `ScenarioDecision.decidedAt(scenario, source, buySlot): Slot`, `DatasetCoverage.undecided(report, dataset): List<CoverageShortfall>`, `DipOutcomeCutoff : MarketCaptureCutoff`, and `MarketCoverageEndedException` (replay's base domain exception, mapped to 409 `MARKET_COVERAGE_ENDED` by the console).

**console:** the market attempt settings gain `cutoffMode`; `CaptureOperationRequest` for `MARKET` accepts `cutoffMode` (extension = `FULL`). These are additive API fields.

## Domain Rules

1. Market detail requests are issued in ascending `(slot, signature)` order. Manifest record order, artifacts and hashes of a `FULL` capture are byte-identical to today's at any concurrency, including after a resume.
2. `D` is the window's slot-complete prefix end (Decision 5). A selected record whose outcome is `UNAVAILABLE`, `UNSUPPORTED_VERSION`, `WITHHELD_UNSAFE_TEXT` or `PROVIDER_UNAVAILABLE` at slot g bounds `D ≤ g − 1`.
3. In `DECIDED` mode the evaluator runs after each checkpointed batch whose prefix advanced. It also runs once before any fetch, so `UNSUPPORTED_SUPPLY` decides at S and fetches nothing.
4. `Decided(X)` requires `X ≤ D`. The window then records `cutoff.throughSlot = X`. A record with slot > X becomes `NOT_NEEDED` unless another window that selects it is undecided or was decided at a slot ≥ the record's slot. The phase ends.
5. `NOT_NEEDED` is resolved: `acquisitionComplete` and case readiness treat it like `CAPTURED`. Decode, case decode and export use only records in `[fromSlot, observedThrough]`. A detail gap above `observedThrough` does not block the window. A gap at or below it does, as today.
6. The evaluator's dataset is the window's anchor buy transaction(s) from the wallet capture, the prefix observations, coverage `{fromSlot, D}`, and capture-time rates for every prefix minute. If a minute's rate is unavailable, the prefix is cut before that minute's first observation, so the evaluator never sees a trigger without its rate.
7. The evaluator returns `Decided(decidedAt, outcome)` only when the report has exactly one scenario for the window's pair, its cycle buy is the window's anchor signature, and `DatasetCoverage.undecided` is empty. Otherwise it returns `Undecided`. A replay exception is logged at ERROR and is `Undecided`, so the capture continues in full.
8. Export writes v5 with `coverage.throughSlot = observedThrough` per window, and rates only for minutes of exported observations. A `FULL` capture exports `throughSlot = toSlot`.
9. `SimulationRunsService.start` and qualification compute `DatasetCoverage.undecided` before persisting. If any scenario is undecided, the run is refused with `MARKET_COVERAGE_ENDED`, nothing is stored and no run id is reserved. A pair with no coverage entry in a v5 dataset is invalid at load (`DATASET_INVALID`).
10. Qualification refusing on coverage gives `CaseClassification.INCOMPLETE_CAPTURE`, capture stage `MARKET`, reason `MARKET_COVERAGE_ENDED`, replay `NOT_RUN`. It is never a negative case.
11. Determinism: the evaluator uses no clock. Its only external input is the rate lookup, and Decision 4 re-checks it offline. Identical captured prefixes and rates give the identical X. A run's id, configuration hash and result checksum for v3/v4 datasets are unchanged; `dip-sample-v3` keeps its pinned values.
12. Extension (`FULL` on a window with a cutoff, or `DECIDED` with a different `CutoffIdentity`) clears the cutoff, reopens its `NOT_NEEDED` records as `PENDING`, never relists and never refetches a `CAPTURED` record.
13. Numbers: no new arithmetic. Slots are `Long`. Prices, rates and P&L come from the unchanged replay (`DecimalPolicy`, scale 38/12, HALF_EVEN).
14. Provenance: each decided window stores the outcome, X, engine version and configuration hash. The v5 dataset stores coverage, and the run report keeps its existing provenance.

**Negative cases (each gets a test):**

- a gap below X: no cutoff, full capture, gap blocks as today;
- a gap above X: cutoff, `NOT_NEEDED`, window yields observations;
- X inside a fetched batch: later records in that batch are `NOT_NEEDED` with no artifact reference;
- interrupted mid-batch then resumed: same manifest as uninterrupted;
- concurrency 1 vs 4: identical manifest bytes;
- a record shared by a decided and an undecided window: it stays and is fetched;
- an empty selection: decided or complete without requests;
- a rate unavailable for a prefix minute: undecided past it;
- `NO_DIP` over a prefix: undecided until `D = toSlot`;
- a replay exception: undecided and logged;
- a v5 dataset run with take profit 5000 bps closing beyond X: 409 `MARKET_COVERAGE_ENDED`, no row;
- the same with horizon 9000 on a full capture: refused, which today silently gives `NO_DIP`;
- a v5 dataset missing coverage for a pair: `DATASET_INVALID`;
- a manifest whose `NOT_NEEDED` record has an artifact, or a cutoff above `toSlot`, or below a non-captured record: inspection refuses (`CAPTURE_INVALID`);
- `DECIDED` without a rate source: `RATES_NOT_CONFIGURED` before any provider request.

## Data Model

No database schema change. Datasets are stored as JSON snapshots (`ReplayDatasetEntity.snapshot`), so coverage travels inside them.

Files change:

- **Market manifest schema version 3**, written only when a window has ever been decided (a `FULL` capture keeps writing version 2 byte for byte). It adds `window.cutoff` and the `NOT_NEEDED` outcome. Version 2 manifests load, resume and extend.
- **Replay dataset schema v5** (`docs/REPLAY_FORMAT.md`).

Nothing destructive.

## Transactions & Integration

- No database transaction is involved in capture. The run refusal happens before `SimulationRunLedger` persists anything, as validation does today.
- Provider calls:
  - Fewer `getTransaction` calls; listing is unchanged.
  - New: `SolUsdtRateSource.minuteRates` during capture, batched for the new minutes of each evaluation (about one request per window). It's the existing Binance adapter: same timeout, retry and rate-unavailable reasons. Failures make the window undecided, never stop the capture.
- Configuration: none new. The rates source's existing enablement decides whether `DECIDED` is allowed.

## What To Implement

1. **Coverage rule and dataset v5** (replay). `MarketCoverage`, v5 loader/writer/fingerprint, `ScenarioDecision.decidedAt`, `DatasetCoverage.undecided`, refusal in `SimulationRunsService` and `TargetTokenQualificationService`, and 409 mapping in `ConsoleExceptionHandler`. A v5 full-window export of an existing fixture replays identically to v4. Independent of 2.
2. **Cutoff mechanics** (marketdata, chaincapture). Oldest-first fetching, the `until` hook, the port and request fields, manifest v3, prefix and decision application, `NOT_NEEDED`, extension, and decode/case/inspection honouring `observedThrough`. Tested with a fake cutoff. Disjoint files from 1, so it can run in parallel.
3. **Dip-entry evaluator and export coverage** (replay). `DipOutcomeCutoff` over `ReplayRunner` with a rate memo, wiring in `ReplayMarketCaptureService`, v5 coverage in `RecordedReplayExportService` and `RecordedCaseReplayExportService`, and `-PcutoffMode` in `ReplayMarketArgumentParser`. Needs 1 and 2.
4. **Console wiring and Codex brief** (console). Market attempt `cutoffMode` (default `DECIDED` for wallet-token), MARKET request `cutoffMode`, an inspection summary of `observedThrough`/cutoff outcome, and `docs/plans/20261015-outcome-bounded-market-capture-frontend-brief.md`: a "Decided early at slot X (TAKE_PROFIT)" line, a "Capture the rest of the window" action (`cutoffMode: FULL`), and the 409 message on Runs. Nothing under `frontend/` is touched. Needs 3.
5. **Docs** with the change: `REAL_DATA_CAPTURE.md` (Market capture, Cost, wallet-token section), `REPLAY_FORMAT.md` (v5), the ADR below, README variable/format links if they mention v4.

## Verification

Test first, fixtures only, no network, no clock.

- `DatasetCoverageTest`: `decidedAt` for every outcome in Decision 2; undecided when `decidedAt > throughSlot`; v3/v4 never undecided.
- `ReplayRunnerTest`/`ReplayDatasetLoaderTest`: the v5 round trip, a pinned `dip-sample-v3` id/hash/result checksum unchanged, a v5 pair without coverage invalid.
- `SimulationRunsServiceTest` and the console run API test: 409 `MARKET_COVERAGE_ENDED`, no row; horizon 9000 on a full capture refused.
- `MarketWindowCutoffTest` (prefix and decision rules, a pure function): gaps below and above X, shared records, empty selection.
- `MarketHistoryCaptureServiceTest`: oldest-first request order; a fake cutoff decides at X; at most 99 wasted fetches; `NOT_NEEDED`; the extension equals a full capture.
- Existing `MarketHistoryParallelCaptureTest`: extend with `DECIDED` concurrency 1 vs 4 and interrupted/resumed equality. Note: two of its tests and two in `WalletHistoryParallelCaptureTest` already fail on this branch before this work; fix or explain them before relying on them.
- `DipOutcomeCutoffTest` on `ReplayMarketFixtures`: TP at S+40 with 300 later trades decides at S+40; SL; migration before entry decides at the migration slot; `UNSUPPORTED_SUPPLY` decides at S with zero fetches; `NO_DIP` undecided until the horizon; a missing rate minute; a replay exception.
- `TargetTokenQualificationServiceTest`: a decided v5 export qualifies exactly like the full-window v4 export (same classification, trigger, fill, close and P&L); a coverage shortfall gives `INCOMPLETE_CAPTURE`/`MARKET_COVERAGE_ENDED`.
- `RecordedMarketHistoryServiceTest` and case decode: `observedThrough` bounds extraction; inspection refusals for the inconsistent manifests listed above.

Commands:

```bash
./gradlew test --tests 'com.trenching.bot.marketdata.*' --tests 'com.trenching.bot.replay.*' --tests 'com.trenching.bot.console.*'
```

```bash
./gradlew build
```

## Documentation Sync

- ADR `docs/adr/20261015-outcome-bounded-market-capture.md` (accepted with this plan).
- `docs/REAL_DATA_CAPTURE.md`: oldest-first fetching, `DECIDED`/`FULL`, `NOT_NEEDED`, extension, cost.
- `docs/REPLAY_FORMAT.md`: v5 coverage.
- `docs/IMPLEMENTATION_STATUS.md` on delivery.

## Acceptance Criteria

- On the TP fixture, the `DECIDED` capture issues detail requests only for records with slot ≤ S+40 plus at most 99 more. The manifest window has `cutoff = {throughSlot: S+40, outcome: TAKE_PROFIT}`, and every later record is `NOT_NEEDED` without an artifact.
- That capture's v5 export has `coverage.throughSlot = S+40`. Qualification gives `QUALIFYING_DIP`, `Closed(TAKE_PROFIT)` and the same realized P&L as the full-window export of the same fixture.
- Starting a run on that dataset with take profit 5000 bps returns 409 `MARKET_COVERAGE_ENDED` naming the mint, and `simulation_runs` gains no row.
- Extending that capture with `FULL` makes no listing request and ends with a manifest equal to a `FULL` capture's.
- A `FULL` capture's `market-manifest.json` stays version 2 and byte-identical to today's for the same fixtures.
- Log line per decision: `market_capture cutoff mint=… throughSlot=… outcome=… configurationHash=… notNeeded=…`.

## Open Questions

All answered 2026-10-15: the user accepted every recommendation below.

1. **Cutoff configuration** (blocking). Should the capture decide under `SimulationConfig.mvp()` only, or under a configuration the user picks when starting the market capture? *Recommendation:* mvp only for now. It is what qualification replays. A different run later is refused, and the user can extend; per-capture configs add UI and resume-identity work for little gain.
2. **Refuse vs. partial result** (blocking). Should a run on a dataset whose coverage ends before the outcome be refused (409, nothing stored), or stored with a new `COVERAGE_ENDED` outcome? *Recommendation:* refuse (Decision 3). It keeps engine outcomes, persisted rows and result checksums unchanged, and a gap can never look like a strategy result.
3. **Defaults** (blocking). Should `DECIDED` be the default for console wallet-token captures, and `FULL` everywhere else (wallet-references scope, CLI)? *Recommendation:* yes; it keeps existing CLI behaviour and only changes the flow that needs speed.
4. **Dataset schema v5** (blocking, contract change). Is adding `coverage` to the replay fixture schema agreed? Other developers' tooling that writes datasets would emit v4, which keeps loading. *Recommendation:* yes; it also closes the silent longer-horizon gap.
5. **Capture-time rates source** (non-blocking). The evaluator calls the Binance SOL/USDT source during the market stage. Proceeding on the assumption that this is acceptable, since the rates stage already calls it. Not storing the values keeps the rates stage authoritative.
