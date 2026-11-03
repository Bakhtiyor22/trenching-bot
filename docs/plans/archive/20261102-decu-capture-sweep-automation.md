# Automated Decu capture and strategy sweep

> Discover Decu's new buys every night, capture each one with no clicks, replay a fixed sweep of up to 10 strategy
> variations on it, score every variation over rolling sessions, and show sessions, buys and scores in the console.

Status: Delivered (2026-11-03; backend units 1–6. Approved 2026-11-02, as written: the Open Questions'
recommendations stand)

## Context

Today every step is manual, through the local console:

- **Picking a buy.** `GET /api/wallets/{address}/activity` lists Decu's recent Pump.fun buys through the
  `WalletActivityLookup` port ([WalletActivityLookup.kt](../../../src/main/kotlin/com/trenching/bot/walletactivity/WalletActivityLookup.kt)).
  `overSignalThreshold` (curve payment > 1.9 SOL, fees excluded) is only a hint
  ([WalletActivitySignals.kt](../../../src/main/kotlin/com/trenching/bot/walletactivity/domain/WalletActivitySignals.kt)).
  No run applies spec P3; the preset lists it as unsupported
  ([AgreedStrategyPreset.kt](../../../src/main/kotlin/com/trenching/bot/console/domain/AgreedStrategyPreset.kt)).
- **Capture.** A wallet + token session is created through `CaptureSessionService.create`. It gets its wallet, market
  and rates attempts at once, and the user starts `WALLET`, `MARKET`, `RATES` and `EXPORT` operations through
  `CaptureOperationService.start`. A successful export queues `QUALIFY` on its own, which replays with
  `SimulationConfig.agreed()`. `POST /api/captures/{id}/dataset` (`CaptureCatalogService.register`) imports the dataset.
  Sessions, attempts and operations are JSON files under `trenching.console.capture.root-directory` (default
  `build/captures`), handled by one single-threaded worker. Retries, budgets, `Retry-After` handling, pause/cancel and
  restart recovery live in `CaptureOperationService` and `CaptureRetryScheduler`. Operations are idempotent per `requestId`
  plus fingerprint.
- **Runs.** `SimulationRuns.start(datasetId, config)` is synchronous and idempotent. Run id = hash(engine version,
  dataset checksum, configuration hash). `SimulationRunTransaction.insertWithOutcome` stores a full `dataset_snapshot`
  and copies the dataset's wallet events and market observations under the run id.
- **Results.** `DashboardService` shows health, global totals and the last 20 runs. The Runs page sorts and filters only
  the page it has loaded. `GET /api/runs` filters by `datasetId` only. `GET /api/runs/{id}` and its sub-resources already
  return a run's full detail by id.
- **No scheduling.** There is no `@Scheduled` or `@EnableScheduling` anywhere.

The architecture review of 2026-11-02 and the user's answers set the requirements:

| Area | Decision |
|---|---|
| Session | Decu trades about 19:00–03:00 UTC+5. A session ends after 60 minutes with no new buy. |
| Timing | Each buy is captured and simulated once its market window has passed, and scored at session close. |
| Hosting | This PC first, under the local profile with the console enabled. Must stay movable to a server later. |
| Default strategy | `SimulationConfig.agreed()` |
| Signal size (P3) | Strictly more than 1.9 SOL, counting curve payment plus protocol and creator fee. |
| Capture filter | 1.5 SOL, on the same fee-inclusive basis. |
| Variations | May change dip %, take profit / stop loss, the timed clocks, signal size and minimum market cap. |
| Success | Worst-case net P&L above 0 SOL over at least 10 signals, across the last 7 sessions. Flagged only: every variation runs and nothing is adopted automatically. |
| Provider | Helius free tier; buys per night unknown. |
| Retention | Keep everything. Sweep runs reference the shared dataset instead of copying it. |
| Dashboard | Three-state status (Success › No success › No result), then net P&L. Filters: session/date, config, outcome, capture health. No notifications. |
| Kafka / ClickHouse | Not now. |

## Scope

### In scope

1. A fee-inclusive minimum signal size rule in the strategy (engine `dip-paper-v6`), set to 1.9 SOL in `agreed()`.
2. Dataset-referenced runs: sweep runs store no dataset copy, and a dataset that runs reference cannot be deleted.
3. An automation orchestrator in `console.automation`:
   - discovery with a watermark;
   - session grouping;
   - a per-buy state machine that drives the existing capture services;
   - a rolling 24-hour request cap;
   - the sweep;
   - session scoring.
4. A discovery-only mode for measuring volume, with capture switched off.
5. Read API for sessions, buys, sweep runs and evaluations, with server-side sort and filter. This is the HTTP contract
   for the frontend.
6. Documentation: README, three ADRs, and reference SQL for production.

### Out of scope

- Frontend implementation. A separate frontend session builds it against the API here (`CLAUDE.md`, Frontend work).
- Kafka, ClickHouse and any new infrastructure. Notifications.
- Server hosting and authentication. Live or paper-live trading.
- Promoting a variation to the default automatically.
- The independent-case capture path. Automation uses the whole-capture wallet + token path, which yields one case per
  session.
- Backfilling sessions from before automation is enabled, beyond `initial-lookback`.

## Key Decisions

1. **The orchestrator lives in `console.automation`, not a new top-level module.** The capture state machine
   (`CaptureSessionService`, `CaptureOperationService`, `CaptureCatalogService`) is `console.capture` internals, enabled
   only with the local profile and the console. A sibling sub-package can call them without turning console internals
   into a public port. Rejected: a top-level `automation` module, which would need a new public capture contract out of
   `console`, more surface for no second consumer.
2. **Discovery uses a watermark and runs on a polling window; it is not a fixed 2-hour batch.** A window can only be
   captured after it has passed (buy + 4,500 slots, about 30 minutes). Polling every 10 minutes is cheap because
   `WalletActivityLookup` serves repeat transactions from the shared transaction cache. Market capture for a buy starts
   at block time + `market-delay`, so `HORIZON_NOT_ELAPSED` retries are not spent.
3. **Automation captures in `FULL` mode.** Variations that hold longer than the agreed 4 minutes (V06) would be refused
   `MARKET_COVERAGE_ENDED` on a `DECIDED` window ([CaptureCutoffModes.kt](../../../src/main/kotlin/com/trenching/bot/console/capture/CaptureCutoffModes.kt)).
   It costs more requests per buy, which the 24-hour cap bounds.
4. **Every sweep configuration runs on every buy; there is no early stop.** Stopping at the first success makes the
   order of the variations decide the result. Replays are offline and fast.
5. **Success is judged across sessions, never per token.** With about one cycle per token, a per-token "success" is
   noise. This follows spec Section 12: net P&L across every signal, skips included.
6. **Sweep runs reference their dataset by checksum.** Each copied run duplicates 0.3–2.5 MB of dataset JSON plus
   thousands of observation rows, and ten runs per buy multiplies that by ten. Runs started outside automation keep
   copying, so nothing changes for them.
7. **The 1.9 SOL rule becomes a strategy rule (engine change); the capture filter is an automation property.** The two
   are kept consistent by validation at startup (Domain Rules 3 and 4). ADR required.
8. **PostgreSQL holds everything, including automation state.** Captures keep their files. Automation keeps its own
   ledger in tables keyed by deterministic ids, so restarts converge. No broker; the scheduler is an in-process
   `@Scheduled` trigger, and a single instance is assumed (see Transactions & Integration).

## Module Contracts

### `strategy` — additive; changes `agreed()` and the engine version

- `SimulationConfig` gains `val minSignalSol: BigDecimal?` (null means no rule, which keeps the `dip-paper-v5`
  behaviour), a parameter of `of(...)`, and `agreed()` sets `BigDecimal("1.9")`.
- `SimulationConfigBounds.MIN_SIGNAL_SOL`: greater than 0 and at most 1,000 SOL, whole lamports (scale 9).
- `DipWatchOutcome` and `RejectionReason` each gain `SIGNAL_BELOW_MIN_SIZE` and `SIGNAL_SIZE_UNAVAILABLE`.
- New `strategy.domain.SignalSize`: `fun lamports(curvePaymentLamports: BigInteger, feeBps: Int, creatorFeeBps: Int): BigInteger`.
- **Contract change, flagged.** `agreed()` gets a new configuration hash, so agreed run ids change from `dip-paper-v6`
  on. Stored runs keep their ids and engine versions. Qualification and the `DECIDED` cutoff both use `agreed()`, so a
  buy at or below 1.9 SOL now qualifies as a negative case.

### `protocol.pumpfun` — additive

- `PumpFunFees.lamports(amount: BigInteger, bps: Int): BigInteger` returns the program's fee, rounded up to whole
  lamports. `papertrading`'s `CurveTrade` fee calculation moves to it, so the signal rule and execution share one formula
  (rule: no duplicated logic). This is protocol knowledge, not a market rule, so it fits the module.

### `replay` — changes `SimulationRuns` and `ReplayDatasetCatalog` (both have one caller, `console`)

- `enum class RunStorage { COPY, DATASET_REFERENCE }`.
- `SimulationRuns.start(datasetId: String, config: SimulationConfig, storage: RunStorage = RunStorage.COPY): RunStart`.
  If the run already exists, it is returned as stored, whatever `storage` is requested (idempotent by run id).
- New `RunInputs` port (replay public API):
  - `fun observations(runId: String): List<MarketObservation>`
  - `fun transactions(runId: String): List<WalletTransaction>`

  It reads the run's own copy, or the referenced dataset's snapshot through `ReplayDatasetCatalog.load`. Console callers
  that read `MarketObservationStore.observations(runId)` today (`ConsoleService`, the chart services) switch to it.
- `ReplayDatasetCatalog.delete(id)` refuses a dataset that `DATASET_REFERENCE` runs still use, with
  `DatasetInUseException(datasetId, runCount)`, mapped to 409 `DATASET_IN_USE`. **Contract change, flagged.** Today
  delete always succeeds, because runs keep their own copy. The README sentence about that changes.
- `RunSummary` gains `storage: RunStorage`.

### `console.automation` — new, internal to `console`

- Ports used: `WalletActivityLookup`, `SimulationRuns`, `RunInputs`, `ReplayDatasetCatalog`, `ApiUsageReports`, and the
  services `CaptureSessionService`, `CaptureOperationService`, `CaptureCatalogService`.
- Services:
  - `AutomationDiscoveryService`: watermark, filter, session grouping;
  - `AutomationPipelineService`: the item state machine;
  - `AutomationSweepService`: the runs;
  - `AutomationEvaluationService`: scoring;
  - `AutomationQueryService`: the read API.
- Thin triggers: `AutomationScheduler`, with `@Scheduled` methods that call one service each.
- Sweep plans live in code, as `object SweepPlans` with `val DECU_SWEEP_1: SweepPlan`. A `SweepPlan` has
  `(id: String, entries: List<SweepEntry>)`, and a `SweepEntry` has `(label: String, config: SimulationConfig)`. A changed
  list is a new plan id, never an edit.

### HTTP contract — new, under `/api/automation`, local profile with console enabled

| Method · path | Returns |
|---|---|
| `GET /status` | `enabled`, `captureEnabled`, the polling window, the watermark (slot, signature), requests used in the last 24 h against the cap, the next tick, the open session id |
| `GET /sessions?page&size&from&to&status&sort` | Session rows: id, wallet, first and last buy times, state, item counts by state, best status and best config label, requests used. `sort` is `status` (default: status rank, then worst-case P&L descending) or `createdAt,desc` |
| `GET /sessions/{id}` | Session detail: its items, and its evaluations (one per sweep entry) |
| `GET /items/{id}` | Item detail: buy, step history, capture session id, dataset id, verdict, and sweep runs with `runId` links |
| `GET /evaluations?planId&label&status&from&to&sort&page&size` | The scoreboard. Each row: config label, window sessions, signals, scored, unscored, realized and worst-case P&L, status |
| `GET /sweep-runs?sessionId&mint&label&outcome&page&size` | Sweep run rows; the outcome filter takes close and skip reasons |
| `POST /sessions/{id}/close` | Closes an open session now (the manual end), idempotent. Body `{requestId}` |

A run's full detail stays at the existing `GET /api/runs/{id}` family.

## Domain Rules

### Signal size (strategy, engine `dip-paper-v6`)

1. Signal lamports = `curvePayment + PumpFunFees.lamports(curvePayment, feeBps) + PumpFunFees.lamports(curvePayment, creatorFeeBps)`.
   `curvePayment` and both fee rates come from the wallet buy's own market observation: same pair, slot, signature and
   instruction, with the wallet as the trade's `trader` and `BUY` as its side. This is the observation `POST_TRADE`
   already resolves. All arithmetic is `BigInteger` lamports with no decimals. `minSignalSol` converts to lamports
   exactly (scale 9, `RoundingMode.UNNECESSARY`). The test is strictly greater than: exactly 1.9 SOL fails, and 1.9 SOL
   plus 1 lamport passes.
2. The size check runs at the buy, before the minimum market cap, following the spec's check order. A cycle at or below
   the minimum ends `SIGNAL_BELOW_MIN_SIZE` with no entry. A cycle without exactly one eligible buy observation ends
   `SIGNAL_SIZE_UNAVAILABLE`. There is no fallback to `WalletTransaction.quoteDelta`.
3. A run with `minSignalSol` set on a dataset older than schema v4 (no trades) is refused before anything is stored,
   with 409 `SIGNAL_SIZE_NEEDS_TRADES`, the same way as `SELL_CHECK_NEEDS_TRADES`.
4. The configuration hash appends `minSignalSol=<plain string>` only when it is set, so every config without it keeps its
   `dip-paper-v5` hash. The result checksum adds the signal lamports of each cycle that checked it.

### Discovery and sessions

5. **Capture filter.** A wallet `BUY` action is captured when
   `solLamports × (10,000 + capture-fee-margin-bps) / 10,000 > capture-min-signal-sol` in lamports. The product is
   rounded down (`RoundingMode.DOWN`); the default margin is 300 bps. `SELL` actions, and `BUY` actions at or below the
   filter, are skipped and counted on the session as `belowFilter`.
6. **Startup validation.** `capture-min-signal-sol` must be at or below the smallest `minSignalSol` in the active sweep
   plan; a config with no minimum counts as 0 and is refused. Otherwise startup fails fast, naming both values.
7. **One item per (wallet, mint).** Only the first discovered buy of a mint creates an item. A later buy of a captured
   mint is ignored, because spec P2 makes only the first-ever buy a trigger. Whether it really is the first buy is
   proven by the wallet + token capture, not by discovery. *Amendment (2026-11-03, fix pass; confirmed by the user
   2026-11-03):* when a catch-up later finds an earlier buy of the same mint, it replaces the later buy's item while
   that item is still `DISCOVERED` with no capture step, or `FAILED` with `MISSING_BLOCK_TIME`; otherwise the existing
   item stays and a WARN is logged. After a replacement the old session is rebuilt from its remaining items: its span
   is recomputed, it splits wherever two remaining buys are more than `session-idle` apart, and a session left empty is
   deleted, so the partition of timed buys does not depend on the order discovery found them. *(Second fix pass,
   pending user confirmation:)* the part holding the session's `firstItemId` (else the earliest part) keeps the
   session, its id and `below_filter_count`; every other part becomes a new session keyed by its earliest item and
   takes its items' sweep rows. A replacement happens only while the item's session is `OPEN`; a closing or closed
   session is never changed. The capture `CREATE_SESSION` step waits while the wallet has a catch-up in progress, so
   the real first buy is found before capture starts.
8. **Item id** = SHA-256 of `wallet|signature|instructionIndex|innerIndex`, where a top-level event uses `innerIndex` −1.
   Hex, 64 characters.
9. **Watermark.** The newest (slot, signature, instructionIndex, innerIndex) processed, kept per wallet. Discovery pages
   newest first until it reaches an action at or below the watermark, or `max-discovery-pages`. It then advances the
   watermark to the newest action seen, in the same transaction that inserts the items. If the page limit cuts a scan
   short, the watermark does not advance, and the next tick reads the same range again; duplicates are absorbed by
   Rule 7 and the unique keys. *Amendment (2026-11-03, found in Unit 3):* a backlog larger than the page cap would
   never be reached that way, so the watermark row also keeps a resume cursor (the oldest `nextBefore` read, nullable
   `resume_before varchar(88)`); the next tick continues from it until the watermark is reached, then clears it and
   advances the watermark. On first enable, the watermark starts at the clock's now minus `initial-lookback`
   (default `PT12H`).
10. **Sessions.** An item joins the wallet's `OPEN` session when its block time is at most `session-idle` (default 60
    min) after that session's last buy. Otherwise it opens a new session, whose id is SHA-256 of `wallet|first item id`.
    A session becomes `CLOSING` when the clock passes last buy + `session-idle`. It becomes `CLOSED` once all its items
    are terminal, or through the manual close endpoint, which marks every non-terminal item `ABANDONED`.
    *Amendment (2026-11-03, fix pass; confirmed by the user 2026-11-03):* a buy joins the open session whose
    [first buy − idle, last buy + idle] span it fits, otherwise it opens a new one (catch-ups can create older sessions
    after newer ones). A buy that fits two open sessions merges them into the one created first (ties: earlier first
    buy, then smaller id), whose id is kept; items and sweep rows move in bulk. A session's id is SHA-256 of
    `wallet|` the item it opened for and never changes; `firstItemId` is that item while it exists, else the session's
    earliest remaining item. A
    session becomes `CLOSING` only once a complete discovery scan covers last buy + idle; a scan covers up to its start
    minus a 60 s indexing margin. `automation_watermarks.updated_at` records the start of the last complete scan, and
    finishing a catch-up sets it to the start of the scan that began it. After the polling window ends, discovery keeps
    scanning newest first only while an `OPEN` session whose last buy is before the window's end has its idle time
    passed without a covering scan, so the night's sessions close the same morning. Sessions that daytime buys open or
    extend (including a night session extended past 05:00 by a morning scan) wait for the window to reopen (third fix
    pass, 2026-11-03). After the configured wallet changes, the old wallet's open sessions are closed manually.
11. **Block time.** An action without a block time is not scheduled: its item is stored `FAILED` with
    `MISSING_BLOCK_TIME` and is never scored. *Amendment (2026-11-03, fix pass):* such an item attaches to the
    wallet's latest open session without changing its span, so it never opens or bridges a session by discovery time.
    With no open session it gets a session of its own at the discovery time, created `CLOSING`, which takes no buy.
    A session left holding only such buys after a replacement also goes `CLOSING`, with the first of them as
    `firstItemId`. An untimed item is replaced by an earlier buy of its mint while its session is `OPEN` or `CLOSING`
    (not `CLOSED`), and a session left empty is deleted (third fix pass). Which open session an untimed buy
    joins still depends on discovery order; such items are never scored, so evaluation is unaffected.
    Discovery's lookback bound also stops on the listing's own block times (`getSignaturesForAddress` returns them),
    so a history with no decodable trade is not paged to its end.
12. **No `Instant.now()`.** Every time comes from the injected `SimulationClock`, and every id is derived from input.

### Pipeline (per item)

13. **States.** `DISCOVERED → CAPTURING → CAPTURED → SIMULATED`, with the terminal states `SIMULATED`, `FAILED`,
    `ABANDONED` and `SKIPPED_NOT_FIRST_BUY`, and the wait states `WAITING` and `BLOCKED_BUDGET`. Steps: `CREATE_SESSION`,
    `WALLET`, `MARKET`, `RATES`, `EXPORT`, `QUALIFY`, `REGISTER`, `SWEEP`. Any other transition is illegal and fails with
    `IllegalItemTransitionException(itemId, from, to)`.
14. **Request ids.** Every capture call uses `auto-<step>-<first 24 hex of item id>-<stepAttempt>` as its `requestId`.
    This matches `CaptureFileStore.id`'s `[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}`. After a restart, a tick that repeats a call
    gets the existing session or operation back, by `requestId` and fingerprint. Session creation uses the wallet +
    token scope, the item's mint and `cutoffMode = FULL`.
15. **One operation at a time.** A tick starts at most one capture operation, and only when
    `CaptureOperationService.idle()`. `CAPTURE_BUSY`, from manual use of the console, means the item waits for the next
    tick; it is not a failure. Items are served oldest buy first.
16. **Market timing.** `MARKET` starts no earlier than block time + `market-delay` (default 35 min). `WALLET` may start
    right after discovery.
17. **Operation results.** `SUCCEEDED` advances the item. `WAITING_RETRY` and `RUNNING` wait. `PAUSED` waits and shows
    the pause. `FAILED`, `INCOMPLETE`, `INTERRUPTED` and `CANCELLED` retry the same step with `stepAttempt + 1`, up to
    `max-step-attempts` (default 2). After that the item is `FAILED`, with the operation's `code` and `message`
    (message truncated to 512 characters). Each `WALLET` and `MARKET` operation sends the automation's recovery limits.
18. **First buy not proven.** A qualification verdict whose first buy is unproven (`TargetBuyRefusal`) ends the item
    `SKIPPED_NOT_FIRST_BUY`. It is stored and counted, and never swept or scored.
19. **Request cap.** Before starting a `WALLET` or `MARKET` operation, the tick reads
    `ApiUsageReports.report(LAST_24_HOURS, null).requests`. If that plus the operation's request budget would exceed
    `max-requests-per-24h`, the item becomes `BLOCKED_BUDGET` and is checked again on every tick. Manual captures count
    toward the same cap, because the provider limit is shared.
20. **Discovery-only mode.** With `capture-enabled=false`, items stay `DISCOVERED` and sessions still open and close.
    This is phase 0: it measures buys per night and the filter's yield before any capture spends requests.

### Sweep and scoring

21. **Sweep.** For a `CAPTURED` item, each entry of the active plan runs `SimulationRuns.start(datasetId, config,
    DATASET_REFERENCE)` in entry order. One `automation_sweep_runs` row per (item, plan, label) is unique. An entry
    refused with `MARKET_COVERAGE_ENDED`, `SELL_CHECK_NEEDS_TRADES` or `SIGNAL_SIZE_NEEDS_TRADES` stores its code, gets
    no run id, and leaves the item unscored for that label. Re-running the sweep returns the same run ids.
22. **Signal.** An item is a signal for a sweep entry when the item's first buy is proven and that entry's run did not
    end `SIGNAL_BELOW_MIN_SIZE` or `SIGNAL_SIZE_UNAVAILABLE`. Later skips (`NO_DIP`, `REFERENCE_BELOW_MIN_MARKET_CAP`,
    `WALLET_SOLD` before entry, and so on) are signals with 0 P&L, as spec Section 12 requires. A run that ended
    `REFERENCE_UNAVAILABLE` (checked before the size, so its size is unknown) is not a signal either: it counts as
    unscored, like a failed capture (user decision, 2026-11-03).
23. **P&L per sweep run, in SOL, scale 12, `HALF_EVEN`.**
    - `realizedPnl` is the closed position's realized P&L, or 0.
    - `worstCasePnl` equals `realizedPnl` for a closed position or no entry. For a position still `OPEN` at the end
      (`HORIZON_REACHED`, `UNRESOLVED`), it is minus the entry's total cost: notional + entry fees + transaction cost.
24. **Evaluation.** One row per (closing session, plan, label), computed when the session closes. The window is that
    session and the `evaluation-window-sessions − 1` (default 6) previous closed sessions of the same wallet, ordered by
    first buy time.
    - `signals` is the number of signals in the window.
    - `unscored` is the number of `FAILED` and `ABANDONED` items, plus the items whose entry was refused under Rule 21
      or whose run has no usable result (`SIGNAL_SIZE_UNAVAILABLE`, `REFERENCE_UNAVAILABLE`, `NO_CYCLE`). `scored` is
      every other item with a run for the label, so the two never overlap. *(Clarified 2026-11-03 in Unit 5.)*
    - A session is evaluated only once every earlier session of the same wallet is `CLOSED`, so its window is
      complete. *(Clarified 2026-11-03 in Unit 5.)*
    - `realizedPnl` and `worstCasePnl` are summed over the signals.
25. **Status.**
    - `SUCCESS` when `signals ≥ min-signals` (10) and `worstCasePnl > min-net-pnl-sol` (0).
    - `NO_SUCCESS` when there are enough signals but the P&L is not above the threshold.
    - `NO_RESULT` when `signals < min-signals`.

    A session's status is the best status of its evaluations; the order is SUCCESS › NO_SUCCESS › NO_RESULT. Comparison
    uses `compareTo`, never `equals`.
26. **Re-evaluation.** Re-evaluating a session replaces nothing. It returns the stored rows, unique on (session, plan,
    label). A changed plan id gives new rows.

### Provenance

27. Each item stores the buy's signature, instruction and inner index, slot, block time, curve payment, capture session
    id, export dataset id and verdict classification. Each sweep run stores the plan id, label, run id, configuration
    hash and engine version (from `RunSummary`). Each evaluation stores the ids of its window sessions.

## Data Model

Schema changes are entity changes. `local` and `test` get them through `update`; production applies the SQL by hand.
Automation is off in production, but its entities are scanned in every profile, so `validate` needs the tables there.

### New entities, all in `console.automation.infrastructure.persistence`

`automation_watermarks` — `AutomationWatermarkEntity`

| Column | Type |
|---|---|
| `wallet` | `varchar(44)`, primary key |
| `slot` | `bigint not null` |
| `signature` | `varchar(88) not null` |
| `instruction_index` | `int not null` |
| `inner_index` | `int not null` |
| `updated_at` | `timestamptz not null` (amended: the start of the last complete scan) |
| `version` | `bigint not null` (`@Version`) |

*Amendment (2026-11-03):* the implementation added the resume cursor and scan bookkeeping columns, all nullable:
`resume_before`, `resume_since` and the four `pending_*` columns (Rule 9), and `catch_up_started_at timestamptz`
(second fix pass: the start of the scan that began the current catch-up). `docs/schema/V20261102120000__automation.sql`
has the exact DDL.

`automation_sessions` — `AutomationSessionEntity`

| Column | Type |
|---|---|
| `id` | `varchar(64)`, primary key |
| `wallet` | `varchar(44) not null` |
| `first_item_id` | `varchar(64) not null` |
| `first_buy_at`, `last_buy_at` | `timestamptz not null` |
| `state` | `varchar(16) not null` (`OPEN`, `CLOSING`, `CLOSED`) |
| `closed_at` | `timestamptz` |
| `close_reason` | `varchar(16)` (`IDLE`, `MANUAL`) |
| `below_filter_count` | `int not null` |
| `created_at` | `timestamptz not null` |
| `version` | `bigint not null` |

Unique: `uq_automation_sessions_first_item (first_item_id)`. Indexes:
`idx_automation_sessions_wallet_state (wallet, state, last_buy_at)` and
`idx_automation_sessions_first_buy (wallet, first_buy_at)`.

`automation_items` — `AutomationItemEntity`

| Column | Type |
|---|---|
| `id` | `varchar(64)`, primary key |
| `session_id` | `varchar(64) not null` |
| `wallet` | `varchar(44) not null` |
| `mint` | `varchar(44) not null` |
| `signature` | `varchar(88) not null` |
| `instruction_index` | `int not null` |
| `inner_index` | `int not null` |
| `slot` | `bigint not null` |
| `block_time` | `timestamptz` |
| `curve_payment_lamports` | `numeric(20,0) not null` |
| `state` | `varchar(24) not null` |
| `step` | `varchar(16)` |
| `step_attempt` | `int not null` |
| `capture_session_id` | `varchar(80)` |
| `operation_id` | `varchar(80)` |
| `dataset_id` | `varchar(64)` |
| `classification` | `varchar(32)` |
| `failure_code` | `varchar(64)` |
| `failure_message` | `varchar(512)` |
| `next_action_at` | `timestamptz` |
| `created_at`, `updated_at` | `timestamptz not null` |
| `version` | `bigint not null` |

Unique: `uq_automation_items_buy (wallet, signature, instruction_index, inner_index)` and
`uq_automation_items_mint (wallet, mint)`. Indexes: `idx_automation_items_due (state, next_action_at, block_time)` and
`idx_automation_items_session (session_id, block_time)`.

`automation_sweep_runs` — `AutomationSweepRunEntity`

| Column | Type |
|---|---|
| `id` | `varchar(64)`, primary key = SHA-256 of `item|plan|label` |
| `item_id` | `varchar(64) not null` |
| `session_id` | `varchar(64) not null` |
| `plan_id` | `varchar(32) not null` |
| `label` | `varchar(16) not null` |
| `run_id` | `varchar(64)` |
| `configuration_hash` | `varchar(64)` |
| `engine_version` | `varchar(64)` |
| `outcome` | `varchar(40) not null` (a close reason, a watch or rejection reason, or a refusal code) |
| `position_state` | `varchar(8)` |
| `is_signal` | `boolean not null` |
| `realized_pnl` | `numeric(38,12) not null` |
| `worst_case_pnl` | `numeric(38,12) not null` |
| `created_at` | `timestamptz not null` |

Unique: `uq_automation_sweep_runs_entry (item_id, plan_id, label)`. Indexes: `idx_automation_sweep_runs_run (run_id)`
and `idx_automation_sweep_runs_session (session_id, plan_id, label)`.

`automation_evaluations` — `AutomationEvaluationEntity`

| Column | Type |
|---|---|
| `id` | `varchar(64)`, primary key = SHA-256 of `session|plan|label` |
| `session_id` | `varchar(64) not null` |
| `plan_id` | `varchar(32) not null` |
| `label` | `varchar(16) not null` |
| `window_session_ids` | `text not null` (JSON array, ordered) |
| `window_sessions` | `int not null` |
| `signals` | `int not null` |
| `scored` | `int not null` |
| `unscored` | `int not null` |
| `realized_pnl` | `numeric(38,12) not null` |
| `worst_case_pnl` | `numeric(38,12) not null` |
| `status` | `varchar(12) not null` |
| `evaluated_at` | `timestamptz not null` |

Unique: `uq_automation_evaluations_entry (session_id, plan_id, label)`. Indexes:
`idx_automation_evaluations_rank (status, worst_case_pnl, evaluated_at)` and
`idx_automation_evaluations_config (plan_id, label, evaluated_at)`.

Associations are by id only, with no JPA relationships. Enum columns are `@Enumerated(EnumType.STRING)`. Every list
query is paged (at most 100 rows), and the sessions page reads item counts with one grouped query, not per row.

### Changed: `simulation_runs` (replay)

- Add `storage varchar(20)`, nullable. Null reads as `COPY`, so existing rows need no backfill.
- `dataset_snapshot` becomes nullable. It is null exactly when `storage = 'DATASET_REFERENCE'`.
- New index: `idx_simulation_runs_storage_dataset (dataset_checksum, storage)`, for the delete check.
- Relaxing NOT NULL is not destructive, but `update` does not apply it. A local database Hibernate created needs the
  `ALTER` below once, or a reset.

### Production SQL

New file `docs/schema/V20261102120000__automation.sql`, applied by hand before deploying, following the precedent of
`V20261025120000__post_trade_price_and_wallet_sell.sql`:

```sql
ALTER TABLE simulation_runs ADD COLUMN storage varchar(20);
ALTER TABLE simulation_runs ALTER COLUMN dataset_snapshot DROP NOT NULL;
ALTER TABLE simulation_runs ADD CONSTRAINT ck_simulation_runs_storage
  CHECK ((storage IS NULL OR storage = 'COPY') AND dataset_snapshot IS NOT NULL
      OR storage = 'DATASET_REFERENCE' AND dataset_snapshot IS NULL);
CREATE INDEX idx_simulation_runs_storage_dataset ON simulation_runs (dataset_checksum, storage);
-- plus CREATE TABLE for the five automation tables, exactly as declared on the entities,
-- and the enum CHECK lists for intent and order reasons extended with
-- SIGNAL_BELOW_MIN_SIZE and SIGNAL_SIZE_UNAVAILABLE
```

**Rollback.** Drop the five automation tables. `storage` and the nullable snapshot can stay, since copy runs are
unaffected. To restore NOT NULL, the `DATASET_REFERENCE` runs must first be deleted or re-run as copies.

## Transactions & Integration

- **Transaction boundaries.**
  - Discovery: the provider call happens outside any transaction; then one transaction inserts the items, updates the
    sessions and advances the watermark.
  - Each item step: one transaction updates the item after the capture call returns. Capture calls write files and
    never run inside a database transaction.
  - Sweep: `SimulationRuns.start` per entry runs in its own transaction (existing), then one transaction inserts the
    sweep row.
  - Evaluation: one transaction per session.
- **Scheduler.** `@EnableScheduling` on `AutomationConfiguration`. Ticks use `fixedDelay`, so they never overlap.
  - `tick` every `tick-interval` (1 min) advances due items and closes idle sessions.
  - `discover` every `discovery-interval` (10 min), only inside the polling window.

  A single instance is assumed: two instances would race to start capture operations. The unique keys and `requestId`
  idempotency keep the state consistent even then, but requests could be spent twice. Each item is handled in its own
  try/catch: an unexpected exception is logged at `ERROR` with the item id, and the tick continues.
- **Provider.** Only the existing calls are used: discovery through `WalletActivityLookup` (Helius RPC, at most
  `limit + 2` requests per page, with cache hits free) and capture through the existing operations, with their budgets,
  timeouts and `Retry-After` handling. `PROVIDER_RATE_LIMITED` from discovery skips the rest of that tick and is logged
  at `WARN`. `RPC_NOT_CONFIGURED` makes `/status` report `rpcConfigured=false` and logs one `WARN` per tick. Rates come
  from Binance through the existing `RATES` step.
- **Configuration.** `AutomationProperties`, prefix `trenching.automation`, validated, defaults in `application.yml`,
  disabled unless both the console and automation are enabled:

| Property | Default | Rule |
|---|---|---|
| `enabled` | `false` | Needs `trenching.console.enabled` |
| `capture-enabled` | `false` | `false` is discovery-only (phase 0) |
| `wallet` | none | Base58 address; required when enabled |
| `zone` | `+05:00` | Valid `ZoneId` |
| `poll-from` / `poll-to` | `18:00` / `05:00` | Local times in `zone`; the window may cross midnight |
| `tick-interval` / `discovery-interval` | `1m` / `10m` | 10 s to 1 h |
| `initial-lookback` | `PT12H` | 0 to 7 days |
| `max-discovery-pages` | `5` | 1–20, each page with `limit` 20 |
| `session-idle` | `60m` | 10 min to 6 h |
| `market-delay` | `35m` | At least 31 min, since the window is 4,500 slots |
| `capture-min-signal-sol` | `1.5` | Whole lamports; at most the plan's smallest `minSignalSol` |
| `capture-fee-margin-bps` | `300` | 0–2,000 |
| `max-step-attempts` | `2` | 1–5 |
| `max-requests-per-24h` | `5000` | 100–1,000,000; see Open Questions |
| `operation-budget` | requests 400, bytes 100 MiB, timeout 10 min | The existing `CaptureBudget` bounds |
| `recovery` | requests 1,200, bytes 300 MiB, attempts 3 | The existing `CaptureRecoveryConfig` bounds |
| `sweep-plan` | `decu-sweep-1` | A known `SweepPlans` id |
| `evaluation-window-sessions` / `min-signals` / `min-net-pnl-sol` | `7` / `10` / `0` | 1–60 / 1–1,000 / ≥ 0, scale 12 |

  No secrets; the RPC URL stays `SOLANA_RPC_URL`. Changing `application-local.yml` needs the user's approval (strict
  rule 4): the plan only adds defaults to `application.yml`.

### The sweep plan `decu-sweep-1`

One factor changes at a time from `agreed()`. Every entry keeps `POST_TRADE`, `followWalletSells`, 0.1 SOL, 1 latency
slot and 0.001 SOL transaction cost.

| Label | Change from `agreed()` |
|---|---|
| V00 | none (the default) |
| V01 | dip 15% (1,500 bps) |
| V02 | dip 25% (2,500 bps) |
| V03 | take profit +35% (3,500 bps) |
| V04 | take profit +80% (8,000 bps) |
| V05 | stop loss −25% (2,500 bps) |
| V06 | max hold 5 min, hard exit 6 min |
| V07 | dip window 60 s |
| V08 | minimum signal 1.5 SOL |
| V09 | minimum market cap $20,000 |

## What To Implement

1. **Fee-inclusive signal size (engine `dip-paper-v6`).** `PumpFunFees`, `SignalSize`, the `minSignalSol` field, the
   two new outcomes, the v4+ dataset refusal, the hash and checksum additions, and `agreed()` at 1.9.
   `AgreedStrategyPreset` drops P3 from its unsupported rules. The console's `ConsoleConfigRequest`, validator,
   `GET /api/config/defaults` and run detail carry `minSignalSol`. ADR. *Independent of 2.*
2. **Dataset-referenced runs.** `RunStorage`; `SimulationRuns.start` with storage; `SimulationRunTransaction` skipping
   the snapshot and observation copy for references; the `RunInputs` port; console reads (detail, observations, chart,
   USDT view) switched to `RunInputs`; delete refused with 409 `DATASET_IN_USE`. ADR. *Independent of 1 (different
   files, except `SimulationRunTransaction`, which only 2 touches).*
3. **Discovery-only automation (phase 0).** `AutomationProperties` with startup validation; the watermark, session and
   item entities; `AutomationDiscoveryService` (filter, item ids, session grouping, idle close); the scheduler; `GET
   /status`, `GET /sessions` and `GET /sessions/{id}`. Shippable on its own with `capture-enabled=false`. *Depends on 1,
   for the sweep plan's smallest `minSignalSol` in the startup check.*
4. **The capture pipeline.** `AutomationPipelineService`: the state machine over the capture services, request ids,
   market delay, retries, the request cap, first-buy skips, the manual close endpoint, and `GET /items/{id}`.
   *Depends on 3.*
5. **Sweep and scoring.** `SweepPlans.DECU_SWEEP_1`, `AutomationSweepService`, `AutomationEvaluationService`, the sweep
   run and evaluation entities, `GET /evaluations` and `GET /sweep-runs`, and server-side sort and filter. ADR for the
   success rule. *Depends on 1, 2 and 4.*
6. **Documentation sync and production SQL** (below). *Last.*
7. **Frontend Automation page.** A separate frontend session, after 3 and 5 freeze the HTTP contract. Not part of this
   plan's backend work.

Units 1 and 2 can run in parallel; 3 → 4 → 5 is sequential.

## Verification

Test-first. All tests run offline: wallet activity is a fake `WalletActivityLookup`, and capture uses a fake
`CaptureWorkflow` behind the real `CaptureOperationService` with a temporary root.

| Test class | Must prove |
|---|---|
| `SignalSizeTest` | Fees rounded up per component; exactly 1.9 SOL fails and 1.9 SOL + 1 lamport passes; 1.88 SOL curve payment at 125 bps passes (1.9035 SOL) |
| `PumpFunFeesTest` | Matches the existing `CurveTrade` fee values (moved, not changed) |
| `DipEntryStrategy*Test` (existing, extended) | `SIGNAL_BELOW_MIN_SIZE` before the market-cap check; `SIGNAL_SIZE_UNAVAILABLE` with zero or two buy observations; null `minSignalSol` keeps the v5 outcomes and hashes |
| `SimulationConfigTest` | Bounds; `agreed()` hash changes; `mvp()` hash unchanged |
| `ReplayRunnerTest` / `PersistedReplayIT` | A v3 dataset with `minSignalSol` is refused `SIGNAL_SIZE_NEEDS_TRADES` and stores nothing; the sample replay totals unchanged under `mvp()` |
| `SimulationRunTransactionIT` | A reference run stores no snapshot and no observations; `RunInputs` returns the same observations as a copy run of the same dataset; a repeat start returns the stored run whatever storage is requested; the CHECK constraint holds |
| `ReplayDatasetCatalogIT` | Delete refused 409 `DATASET_IN_USE` with the count while reference runs exist; allowed with copy runs only |
| `AutomationPropertiesTest` | Capture filter above the plan's smallest minimum fails startup; midnight-crossing window; missing wallet fails |
| `AutomationDiscoveryServiceTest` | Fee-margin filter at the boundary; sells and repeat mints ignored; the watermark stops paging and advances only on a complete scan; a page cap keeps the watermark; duplicate delivery inserts once; an out-of-order page sorts deterministically; a missing block time gives `FAILED` `MISSING_BLOCK_TIME`; sessions split at idle + 1 ms and join at exactly idle; rate limit and unconfigured RPC skip the tick |
| `AutomationPipelineServiceTest` | Each step's request id; restart mid-operation reuses the operation; `CAPTURE_BUSY` waits; `MARKET` not before the delay; retry then `FAILED` with the code; budget block and release; an illegal transition throws; an unproven first buy gives `SKIPPED_NOT_FIRST_BUY`; manual close abandons pending items and is idempotent |
| `AutomationSweepServiceTest` | Ten runs in label order; a rerun gives the same run ids and no new rows; a refused entry is stored with its code; worst-case P&L for an `OPEN` position equals minus its entry cost |
| `AutomationEvaluationServiceTest` | Window of 7 by first buy time; signals per Rule 22; the boundaries `signals = 10` and `worstCasePnl = 0`; status ranking; re-evaluation idempotent |
| `AutomationRepositoryIT` (Testcontainers) | Every unique key rejects its duplicate; the due-items and ranking queries use their indexes and are paged |
| `AutomationControllerTest` | Sort by status then P&L; each filter; 404 for an unknown id; 400 for a bad sort |

Fixtures: one v6 wallet + token dataset (sanitized from an existing local export, public on-chain data only) with a
buy just over 1.9 SOL including fees, and a copy with a buy just under it. The `dip-sample-v3` checksums stay unchanged.

Commands: `.\gradlew.bat unitTest`, `.\gradlew.bat test`, `.\gradlew.bat replay`.

## Documentation Sync

- README:
  - dip-entry rules: the new P3 rule and engine `dip-paper-v6`;
  - Console: a new Automation section with properties, phases and the request cap;
  - the variables table: `trenching.automation.*`;
  - Deleting: `DATASET_IN_USE`;
  - the schema notes: the SQL file and the local `ALTER`.
- The README and `docs/REAL_DATA_CAPTURE.md` recommend moving `trenching.console.capture.root-directory` out of
  `build/`. The value goes in `application-local.yml` only with the user's approval.
- `docs/REPLAY_FORMAT.md`: the hash and checksum additions.
- ADRs:
  - `docs/adr/20261102-fee-inclusive-signal-size.md`
  - `docs/adr/20261102-dataset-referenced-runs.md`
  - `docs/adr/20261102-automated-capture-and-sweep-evaluation.md` (sessions, `FULL` capture, the success rule, why no
    Kafka or ClickHouse, and the revisit triggers: more than about 50M observation rows, dashboard p95 above 1 s after
    indexing, or a second consumer of the events)
- `docs/strategy/decu-dip-v2-spec.md`: P3 now applied by the engine.
- `docs/IMPLEMENTATION_STATUS.md`.

## Acceptance Criteria

1. Under `agreed()`, a dataset whose wallet buy pays 1.9 SOL including fees ends `SIGNAL_BELOW_MIN_SIZE` with no order.
   At 1.9 SOL + 1 lamport, it proceeds to the market-cap check. `GET /api/runs/preset` no longer lists P3.
2. Rerunning `mvp()` on `dip-sample-v3` keeps its configuration hash and every P&L value. The run id and result
   checksum change with the engine version, as in every earlier engine bump; replaying under the `dip-paper-v5` run id
   reproduces the old checksum.
3. A `DATASET_REFERENCE` run has `dataset_snapshot IS NULL`, zero `market_observations` rows for its id, and a
   `/api/runs/{id}/observations` response identical to a copy run of the same dataset.
   `DELETE /api/datasets/{id}` then returns 409 `DATASET_IN_USE`.
4. With `capture-enabled=false`, two simulated nights produce two `CLOSED` sessions split by the 60-minute idle gap,
   with one item per mint and `below_filter_count` filled in, and spend no capture requests.
5. With capture on, killing the backend during `MARKET` and restarting continues the same operation id; no second
   capture session exists for the item.
6. Each `SIMULATED` item has exactly 10 `automation_sweep_runs` rows, and each `run_id` opens at `/api/runs/{id}`.
7. After 7 closed sessions, `GET /api/automation/evaluations` returns 10 rows for the last session, ordered SUCCESS ›
   NO_SUCCESS › NO_RESULT, then by worst-case P&L descending. Each row's numbers can be rebuilt by hand from its sweep
   runs.
8. Once the rolling 24-hour requests reach the cap, the next due item shows `BLOCKED_BUDGET`, and the cap is never
   crossed by an operation that automation started.
9. The log carries `automation item advanced itemId=… step=… state=…` at `INFO` per transition and `automation session
   closed sessionId=… reason=… items=…`, and never the RPC URL.

## Open Questions

1. **The value of `max-requests-per-24h`.** The free tier's real allowance isn't known here. *Recommendation:* run phase 0
   for 2–3 nights, read buys per night and the filter yield on `/status`, check the allowance in the Helius dashboard,
   then set the cap at about 70% of it. The default of 5,000 is a guess and is safe only because phase 0 spends nothing.
2. **Unscored items.** Should an evaluation be blocked when many items are `FAILED`? *Recommendation:* no. Show
   `unscored` on every row and in the session, and revisit if unscored exceeds about 20% of items in practice.
3. **Valuing open positions.** *Recommendation:* the worst case (minus the entry cost, Rule 23), so an unresolved
   position can never make a variation look successful.
4. **The evidence root.** It should move outside `build/` (for example `D:/trenching-data/captures`).
   *Recommendation:* you pick the folder, and I add it to `application-local.yml` with your approval.
5. **Sweep list `decu-sweep-1`.** Approve or edit V01–V09. A later change becomes `decu-sweep-2`, and earlier
   evaluations keep their plan id.
6. **The polling window of 18:00–05:00 UTC+5.** *Recommendation:* keep it. Idle detection ends sessions, and the window
   only stops discovery during the day.
7. **Sleep and power.** The PC has to stay awake overnight. *Recommendation:* check the Windows sleep settings yourself.
   `/status` shows the last tick time, so a missed night is visible.

## Delivered

2026-11-03, backend units 1–6. Unit 7 (the frontend Automation page) is a separate session.

What shipped:
- engine `dip-paper-v6` with the fee-inclusive `minSignalSol` rule (`agreed()` = 1.9 SOL), `trade_intents.signal_lamports`
  and the outcomes `SIGNAL_BELOW_MIN_SIZE` / `SIGNAL_SIZE_UNAVAILABLE`; P3 leaves the preset's unsupported rules;
- `RunStorage.DATASET_REFERENCE` runs through the `RunInputs` port, with `DATASET_IN_USE` (409) on delete;
- `console.automation`: watermark discovery with a resume cursor and a lookback bounded by the listing's block times,
  idle-gap sessions, the per-item capture pipeline under the rolling 24-hour request cap, sweep plan `decu-sweep-1`
  (V00–V09), rolling 7-session evaluation, the read API and the scheduler, all off by default;
- `docs/schema/V20261102120000__automation.sql`, README, `docs/REAL_DATA_CAPTURE.md`, and the ADRs
  `20261102-fee-inclusive-signal-size`, `20261102-dataset-referenced-runs` and
  `20261102-automated-capture-and-sweep-evaluation`.

Where it differs from the plan (each recorded as a dated amendment above):
- **Resume cursor** (Rule 9) and the extra watermark columns, because a backlog over the page cap was unreachable.
- **`REFERENCE_UNAVAILABLE` is unscored** (Rule 22, user decision) and `scored`/`unscored` are disjoint (Rule 24).
- **Session placement** (Rules 7, 10, 11): spans, merges with a fixed tie-break, replacement by an earlier buy that
  regroups and can split a session, closing only after a covering scan with a 60 s indexing margin, morning scans
  limited to the night's sessions, untimed buys kept out of placement, manual close after a wallet switch.
- **Capture waits for a catch-up** before creating a capture session, so the real first buy is captured.
- **Review rounds:** one review and three fix passes. The second and third were asked for by the user.

Verification: `./gradlew build --continue` with the native PostgreSQL provider (Docker was not running) ran 1842 tests;
the only 5 failures also fail on a pristine HEAD (SharedTransactionCacheTest, TransactionCacheOptionTest,
WalletActivityConfigurationTest, WalletHistoryParallelCaptureTest ×2). The acceptance criteria are proven with fixtures
and fakes only.

Not verified: a real night of phase 0 (discovery only) or phase 1 against Decu's wallet, which is user-run; and the
production schema SQL, which is applied by hand.
