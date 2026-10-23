# Record each market window from shortly before the wallet's first buy

> Market windows start 50 slots (about 10 s) before the wallet's first buy S instead of at S+1, so the capture also holds the rest of slot S and the market leading into the buy. Replay still decides nothing before S+1.

Status: Delivered (approved and delivered 2026-10-22)

## Context

A market window is planned per mint as `[S+1, S+horizon]`, S being the slot of the tracked wallet's first buy
(`replay/application/ReplayMarketWindowPlanner.kt:28`). Two things make the capture stop at the buy:

- Discovery lists the mint's signatures newest-first with `until = the buy's signature`
  (`MarketHistoryCaptureService.kt:98`, `ChainCaptureReads.signaturePage`), so the RPC never returns the buy or
  anything older, and `MarketWindowScan.apply` drops every entry below `fromSlot`.
- Replay planning checks `anchor.slot + 1 == window.fromSlot` (`RecordedWalletPlanning.kt:52`), and the cutoff
  evaluator accepts a decision only in `(fromSlot - 1)..prefixEnd` (`WindowCutoffEvaluator.kt:62`).

The cost shows on every real case. On run `fbd3…2b0b` the first buy (slot 459,490,743) filled at a $9,429 market cap,
but the first recorded trade, one slot later, is at $13,195: about 8.8 SOL of buys landed in slot S after Decu's and
were never recorded. Run `a5f6…` shows the same pattern ($9,573 → $13,231). The chart has to draw a dashed
"unrecorded" connector and explain the gap, and nothing before the buy is visible at all.

What makes the rest of slot S usable: a Pump.fun trade's reserves before it are exactly the previous trade's reserves
after it. On both real captures (1,674 and 1,689 trades) every slot's trades chain exactly and consecutive slots join
with no break, and the chart already orders a slot by that chain (`console/application/SlotExecutionOrder.kt`). Once
the buy's own transaction is captured as an observation, it takes its proven place among slot S's trades without any
special handling.

The measured slot pace on these captures is 212–216 ms, so 50 slots is about 10.6 s (20 s at the nominal 400 ms).

## Scope

### In scope

- New captures plan windows `[S - lead, S + horizon]`, `lead = 50` slots, recorded in the manifest.
- Discovery lists through the buy and back to `S - lead`, so slot S is recorded in full, the buy's own `TradeEvent`
  included.
- Replay planning, the cutoff evaluator, case export and dataset coverage accept the earlier start.
- Replay outcomes for the same capture are unchanged: the strategy, fills and exits still read from S+1.
- The chart needs no change: `coverage.fromSlot <= S` already removes the unrecorded connector, and the buy becomes an
  observation-backed marker.

### Out of scope

- Upgrading existing captures in place (Open Question 2). Existing manifests, cases, datasets and runs stay valid and
  unchanged.
- Letting the strategy see trades in slot S after the buy (Open Question 3). That changes simulation results.
- The `decu-dip` v2 engine ([its draft plan](20261015-decu-dip-v2-replay.md)). It benefits: its reference price, the
  post-trade price of the buy, becomes an observation.
- Frontend changes. None are needed.

## Key Decisions

1. **The lead is in slots, not seconds: 50.** Windows, coverage and replay are slot-based, and block times are whole
   seconds, so a time lead cannot be resolved exactly. 50 slots covers about 10 s at the measured pace; the exact value
   is Open Question 1.
2. **One formula, legacy included.** `fromSlot = S - leadSlots`. A manifest version 2 or 3 reads `leadSlots = -1`,
   which is today's `S + 1`, so old captures need no migration and every check uses the same expression.
3. **The anchor stays the buy's signature, but stops being a listing bound.** `untilSignature` keeps its role as the
   window's identity and anchor (`MarketCases`, `RecordedWalletPlanning.anchor`, `DipOutcomeCutoff`) under the same
   JSON name. A version 4 window lists with no `until`; the existing slot test in `MarketWindowScan` (`slot < fromSlot`
   completes the window) is the only lower bound. Legacy windows keep listing with `until`, so a resumed version 3
   capture behaves exactly as before.
4. **The lead only adds data before the decision point.** Replay already reads observations from `buy.slot + 1`
   (`DipEntryStrategy.watch`), fills after a trigger's slot and exits after the fill. Nothing in replay changes, and a
   test proves outcomes are equal with and without the lead.
5. **No dataset schema bump.** v5 coverage is already "the slots this pair was recorded through"; only its documented
   start (`REPLAY_FORMAT.md`: "S+1 after the wallet's first buy") changes. The loader enforces nothing about the start.
   `DatasetCoverage.undecided` keeps working, since `fromSlot - 1 <= S`. New datasets get new checksums and ids because
   the fingerprint covers coverage and observations; existing ones are untouched.
6. **An ADR records it.** The window start is a capture-fidelity decision and amends
   [outcome-bounded market capture](../adr/20261015-outcome-bounded-market-capture.md) and the window rule in
   `REAL_DATA_CAPTURE.md`.

Rejected: a separate "pre-window" per mint (two coverage entries per pair breaks the loader's one-entry-per-pair rule
and doubles the window bookkeeping), and recording from token creation (unbounded: the token can be days old).

## Module Contracts

All inside `marketdata` and `replay`; no new port.

- `marketdata.MarketCaptureWindowParameters`: add `leadSlots: Long`. **Public value type change** (other modules read
  `horizonSlots` and `maxAgeSlots` from it), additive. The constant `MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS = -1`
  and the default `MarketCaptureFormat.LEAD_SLOTS = 50`.
- `marketdata.MarketCaptureWindow`: unchanged fields. A new factory `MarketCaptureWindow.forFirstBuy(mint, buySlot,
  buySignature, parameters)` computes `[buySlot - leadSlots, buySlot + horizonSlots]`, so the formula lives in one place.
  `ReplayMarketWindowPlanner` and `RecordedWalletPlanning.anchor` both use it.
- `marketdata.MarketHistoryManifest` (`market-manifest.json`): **version 4** adds `windowParameters.leadSlots` and
  writes version 4 with or without a cutoff. Versions 2 and 3 stay readable, with lead `-1`.
- `chaincapture.ChainCaptureReads.signaturePage`: already takes a nullable `until`; version 4 windows pass null.
- Replay dataset v5: no format change; only the documented meaning of `coverage.fromSlot` widens.

## Domain Rules

1. A version 4 window for a first buy at slot S is `[S - 50, S + horizonSlots]`, and `S - 50 >= 0` (planning fails with
   `CaptureValidationException("MARKET_WINDOW_INVALID")` otherwise; it cannot happen on mainnet but is tested).
2. Discovery of a version 4 window lists with no `until` and completes at the first entry below `fromSlot` or at a short
   page, as today. The buy's own signature is in its selection.
3. Every successful transaction of the mint in `[S - 50, S + horizon]` is fetched, including slot S before and after
   the buy. Failed transactions are counted and skipped, as today.
4. The buy's own `TradeEvent` becomes an observation like any other trade, with the tracked wallet as trader.
5. A token created inside the lead has nothing before its creation; coverage still starts at `S - 50`, which is true,
   since no trade could exist earlier.
6. `RecordedWalletPlanning.anchor` accepts a window only if it equals `forFirstBuy(anchor, parameters)` for the
   manifest's parameters, a legacy `S + 1` window included. A version 4 window paired with a version 3 parameter set,
   or the reverse, is `MARKET_CAPTURE_MISMATCH`.
7. The cutoff evaluator accepts a decision slot only in `S..prefixEnd`, S being the anchor's slot: never a slot in the
   lead. A window decided at its buy records through S; coverage `[S - 50, S]` is valid.
8. Replay of a dataset exported from a version 4 capture produces the same scenario outcomes, trigger slots, fills,
   closes and P&L as the same capture exported without its lead. Only ids and checksums differ.
9. Calibration: the first buy's latency check uses the newest trade in a strictly earlier slot. With a lead, that trade
   usually exists, so the first buy gets a latency prediction instead of "no prior state". Its formula stays as written
   (`ExecutionCalibration`).
10. Rerunning the market stage of a version 4 capture is idempotent: the selection is keyed by signature, as today.
11. Overlap: windows of the same mint never overlap (`MarketWindowRules`). With one window per mint per capture, the
   lead cannot create an overlap; the rule is kept and tested with leads.
12. The over-cap and request bounds (`maxTransactions`, `MAX_REQUESTS`) include lead trades. A busy pre-buy market can
   push a window over its cap; it is reported as `OVER_CAP` as today, never silently trimmed.
13. Numbers: slots are `Long`; no decimal arithmetic is added.

## Data Model

No database schema change. Captures live in files: `market-manifest.json` gains `windowParameters.leadSlots` under
manifest version 4. Datasets keep schema v5. Capture console tables (`CaptureRecords.kt`) store windows' slots as they
already do.

## Transactions & Integration

- Provider: the same read-only `getSignaturesForAddress` and `getTransaction` through the existing `ChainCaptureReads`;
  no new provider, credential or configuration property. `SOLANA_RPC_URL` stays the only variable.
- Cost: one listing page (up to 1,000 signatures) usually covers the lead; details cost one request per extra trade.
  Run `fbd3…2b0b` had 75 trades in the 50 slots right after its first buy and 53 in the next 50; a token's market before
  a first buy is usually quieter, so expect tens of extra requests per window against today's 1,600–15,000. The API
  usage page counts them under the market step as usual.
- No database transaction is involved in capture; the console operation persists its state as today.

## What To Implement

1. **Window parameters and planning** (`marketdata`, `replay/application`): `leadSlots` in
   `MarketCaptureWindowParameters`; `MarketCaptureWindow.forFirstBuy`; `ReplayMarketWindowPlanner` uses it with
   `LEAD_SLOTS`; `RecordedWalletPlanning.anchor` checks with it (rule 6). Tests first.
2. **Manifest version 4** (`marketdata/infrastructure/capture`): read and write `leadSlots`; versions 2 and 3 read as
   lead `-1`; `MarketCaptureFormat.SUPPORTED_VERSIONS` adds 4. Depends on 1.
3. **Discovery through the buy** (`MarketHistoryCaptureService`, `MarketWindowScan`): version 4 windows list with no
   `until`; legacy windows unchanged. Depends on 2.
4. **Cutoff and export** (`WindowCutoffEvaluator`, `DipOutcomeCutoff`, `RecordedCaseReplayExportService`,
   `RecordedReplayExportService`): decision range `S..prefixEnd`; coverage `[S - lead, through]`. Depends on 1.
5. **Replay equivalence and calibration** (`replay`): the equivalence test of rule 8 and the calibration case of rule
   9. Depends on 3 and 4.
6. **Docs and ADR.** Can run beside 3–5.

Units 3 and 4 touch different files and can run in parallel once 1 and 2 are done.

## Verification

- `ReplayMarketWindowPlannerTest`: a first buy at S gives `[S - 50, S + 4500]`; legacy parameters give `[S + 1, …]`;
  S below 50 fails with `MARKET_WINDOW_INVALID`.
- `RecordedWalletPlanningTest`: a version 4 window anchors; a version 4 window with legacy parameters, and the reverse,
  is `MARKET_CAPTURE_MISMATCH`.
- `MarketWindowScanTest`: with no `until`, a page holding slots above, at and below S completes at the first entry
  below `S - 50` and keeps the buy's own signature; a legacy window still stops at its `until`.
- `MarketHistoryArtifactStoreTest`: a version 4 manifest round-trips `leadSlots`; versions 2 and 3 load with lead
  `-1`; an unknown version is refused.
- `MarketHistoryCaptureServiceTest` (fixture RPC): a version 4 capture requests no `until`, fetches the lead trades and
  the rest of slot S, and records the buy's `TradeEvent` as an observation.
- `WindowCutoffEvaluatorTest`: a decision at S is accepted, one in the lead is `CUTOFF_DECISION`.
- `RecordedCaseReplayExportServiceTest`: coverage starts at `S - 50`; the dataset loads.
- `LeadEquivalenceTest` (new, `replay`): the same fixture capture exported with and without its lead replays to equal
  scenario outcomes, trigger slots, fills, closes and realized P&L (rule 8).
- `ExecutionCalibrationTest`: a first buy with a lead trade in an earlier slot gets a latency prediction.
- `ChartConsoleServiceTest`: with coverage starting before the buy, the buy's marker comes from its observation, and
  the slot's ticks put it in its chained place (no frontend change; this pins the backend half).
- Commands: `./gradlew test`; then a console capture of one wallet + token case, checked on its run chart.

## Documentation Sync

- `docs/adr/20261022-market-window-lead.md` (new): decisions 1–5, alternatives, consequences, revisit trigger.
- `docs/REAL_DATA_CAPTURE.md`: the window rule `[S+1, S+4500]` becomes `[S-50, S+4500]` for manifest version 4, and
  the manifest version list.
- `docs/REPLAY_FORMAT.md:64`: `fromSlot` is "the first recorded slot: S−50 for captures from manifest version 4, S+1
  before".
- `README.md:197`: the market capture paragraph's `[S+1, S+4500]`.
- `docs/adr/20261015-outcome-bounded-market-capture.md`: a dated amendment pointing to the new ADR.

## Acceptance Criteria

- A new console capture of a wallet + token case writes `market-manifest.json` with `"schemaVersion": 4` and
  `"leadSlots": 50`, and its window's `fromSlot` is the first buy's slot minus 50.
- Its dataset's coverage for the pair starts at `S - 50`, and its observations include the first buy's own trade,
  with the tracked wallet as trader.
- On that run's chart the first buy's marker sits on the line, with no "unrecorded" connector and no "Why the line
  starts higher" section; the market from about 10 s before the buy is drawn.
- The chart's ticks for slot S all have `slotOrderProven: true`, with the buy in its chained position.
- Replaying that dataset gives the same outcome, reason and P&L as a capture of the same case without the lead.
- Existing captures, datasets and runs load and replay unchanged; their run ids and checksums are identical.

## Open Questions

1. **Lead size.** 50 slots is about 10.6 s at today's measured pace. Recommendation: 50, fixed in code and recorded per
   manifest. Making it a capture setting is easy later.
2. **Upgrading existing captures.** A version 3 window could be extended backward by listing older than its saved
   cursor down to `S - 50`, then re-exporting the case. Recommendation: leave it out of this plan; re-capture the cases
   you care about, then decide whether an "extend lead" operation is worth building.
3. **Trades in slot S after the buy, for the strategy.** With latency 1, a dip in slot S could fill in S+1, and the
   spec's "any later trade" includes them. Today replay cannot see them. Recommendation: keep replay unchanged here;
   decide it with the `decu-dip` v2 engine, which reads post-trade prices and will need its own rule for same-slot
   trades.

## Delivered

2026-10-22, with the three recommendations approved: a 50-slot lead fixed in code, no in-place upgrade of existing captures, and replay unchanged for slot S.

**What shipped**
- New captures plan each window as `[S-50, S+horizon]` through `MarketCaptureWindow.forFirstBuy`, list the mint with no `until`, record all of slot S (the buy's own `TradeEvent` included) and write market manifest version 4 with `windowParameters.leadSlots`. Captures from before the lead keep `[S+1, …]`, list with `until` and save as version 2/3 byte for byte.
- Every replay validation path (anchor, plan gate, both exports, calibration, capture workflow, inspection, capture resume and the console case inventory) plans with the lead of the capture it validates, read manifest-only through the new `RecordedMarketHistory.windowParameters(directory)`.
- Cutoff decisions are accepted only in `S..prefixEnd`; the evaluator is first asked once the lead and slot S are captured.
- Replay outcomes are unchanged: `LeadEquivalenceTest` replays the same data with and without the lead to equal outcomes, triggers, fills, closes and P&L, with a control proving lead prices would trigger if read.
- With a lead, a first buy gets a calibration latency prediction from the newest earlier-slot trade.
- Console case records and `inspectReplayMarkets` state `leadSlots` (-1 for captures from before the lead).

**Where it differs from the plan, and why**
- The lead constants live on `MarketCaptureWindowParameters` (`LEGACY_LEAD_SLOTS = -1`, `DEFAULT_LEAD_SLOTS = 50`), not on `MarketCaptureFormat`, which is internal infrastructure that `replay` cannot import.
- `MarketPlanGate` also hard-coded `S + 1` and was moved to `forFirstBuy`; the plan did not list it.
- Additive marketdata contract beyond the plan: the manifest-only `windowParameters` read (needed so the console case inventory keeps working for older captures without reading artifacts), `MarketCaptureWindow.firstBuySlot` and `leadFits`, and `MarketCaptureReasons.MARKET_WINDOW_INVALID`, which now goes through the planning refusal path with its WARN.
- Lead trades are held to the same completeness rules as the rest of the window (rule 3): a gap, blocking exclusion or missing rate inside the lead refuses export or keeps a DECIDED window undecided. The ADR states it.

**Docs updated:** new ADR `docs/adr/20261022-market-window-lead.md`, an amendment to `docs/adr/20261015-outcome-bounded-market-capture.md`, `docs/REAL_DATA_CAPTURE.md`, `docs/REPLAY_FORMAT.md` and README's `captureReplayMarkets` paragraph.

**Schema impact:** none in the database. The market manifest file gains version 4; datasets stay schema v5.

**Verification:** `./gradlew build` compiles and runs 1,421 tests; the only failures are 5 that also fail on the code before this work (`SharedTransactionCacheTest`, `WalletActivityConfigurationTest`, `TransactionCacheOptionTest` and two `WalletHistoryParallelCaptureTest` cases), so Gradle reports the build as failed. A console capture of a live wallet + token case and its run-chart check were not run; they need a provider.
