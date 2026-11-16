# Automation trace, Stop control and console restructure

> Make every run and every token flow findable and traceable after it ends. Add a graceful **Stop** with its own
> `STOPPED` status. Fold Captures into Automation. Make an Analytics page the landing page. Redesign the scoreboards,
> the "Status by day" grid and the hints. No Kafka or ClickHouse.

Status: Approved (2026-11-16). Phase 1 implemented (2026-11-16); Phases 2–4 open.

**Phase 1 delivery notes (2026-11-16):**
- **Units 1 and 3 (backend) are uncommitted in the working tree; units 2, 4, 5 and 6 (frontend) are committed** (`fd042f6` and `6568f20`).
- **Deviations:**
  - The run search index backfill runs in every profile except `test`.
  - A failed index read is marked in `search_index_failed_at`.
  - The default `asOf` is 5 s in the past, so a run that is just being committed cannot slip into a later page. A run started a moment ago appears in the list after 5 s.
  - An event kind `REPLACED` was added, for when an earlier buy replaces an item.
  - `HISTORY_STARTED` is written by the first change to an item that has no events yet, as well as by the startup pass. Events are written under a row lock on the item.
- **Review:** six findings, all fixed with tests.
- **Full suite:** 2154 tests, 1 failure (`UsageApiIntegrationTest`). It was caused by the separate Oracle deployment change to the provider pacing in `application.yml`, not by this work.
- **Superseded:** `prod` (the Oracle server) now runs `ddl-auto: update` (CLAUDE.md, 2026-11-16). Wherever this plan says "prod only validates" or "apply the SQL by hand", the additive changes are now created at startup. `docs/schema/20261116-trace-stop.sql` is reference only. Destructive changes still go by hand.

## Context

The automation runs, but the console fails the basic questions: can I find it, understand it, and act on it without
help? This plan comes from a QA walk of the live console on 2026-11-15/16, using the user's own backend on `:8080` with
real data: 73 datasets, 720 runs and about 70 buys a day. It also draws on a read of the code. Verified facts carry a
file reference; assumptions are marked as such.

Terms used below:

| Term | Meaning in code |
|---|---|
| **Token flow** | One `AutomationItem`: one first buy of one mint by the tracked wallet. `uq_automation_items_mint (wallet, mint)` means a mint has exactly one flow. |
| **Day flow** | One trading day (12:00 → 12:00 UTC+5): its sessions and their items. |
| **Run** | One `simulation_runs` row (a replay). The sweep writes 10 per replayed buy, one for each variation. |

### Root causes found (Phase 1)

**B1. Finished token flows cannot be traced.** `AutomationItemEntity` keeps only the *current* state:
- `state`, `step`, `step_attempt`, `operation_id`, `failure_*`, `next_action_at`, `retries` and `updated_at`
  (`console/automation/infrastructure/persistence/AutomationItemEntity.kt:45-75`).

Nothing records the step history. A person's retry resets the attempt counter and overwrites the last failure
(`domain/AutomationItemProgress.kt:106-115`). Earlier transitions exist only in log lines
(`AutomationPipelineTransaction.kt:67`). So the selected buy on the Day page can show its 10 sweep results, but not which
steps ran, when, how many requests each used, or which errors came before success. The only deeper view is "Open
capture", which shows only the capture's *latest* operation and its attempt list. That view goes away with the Captures
section.

**B2. Runs page filters: the root cause is the list, not storage.** There are four separate causes. The first was
reproduced: "Loss" shows *No runs match · 0 of 20 on this page* while 22 pages exist.

1. **Outcome and text filters only see the current page.** `filterRuns` runs in the browser over the 20 rows already
   loaded (`frontend/src/features/runs/runList.ts:20-38`). `GET /api/runs` accepts only `page`, `size` and `datasetId`
   (`console/api/RunController.kt:48-53`). Any run that is not on the current page cannot match.
2. **Runs drift off the page.** Paging is offset-based and newest first (`replay/application/ReplayPaging.kt:10`). The
   sweep inserts about 300 runs a day. A run seen on page 1 is on page 3 an hour later, while the list polls
   (`api/queries.ts:186-200`). This is the "runs stop being visible after a while" symptom.
3. **Dataset names and the dataset filter are capped at 100.** The page loads `useDatasets(0, 100)`
   (`RunsPage.tsx:333`), and the backend caps the page size at 100 (`ReplayPaging.MAX_PAGE_SIZE`). There are 73 datasets
   today, and automation adds about 50 a day. From roughly the next day on, older datasets cannot be picked, and their
   runs show "—" instead of a name, so text search misses them as well.
4. **There is no filter for token, day, date range or status anywhere.** The sweep-run list filters by mint, but it
   covers sweep runs only.

**Ruled out, with evidence:**
- **No run is deleted.** No code path deletes `simulation_runs` rows. A dataset delete is refused while
  dataset-referenced runs exist (`ReplayDatasetTransaction.kt:55-63`).
- **No cleanup or retention job exists.** The only `@Scheduled` class is `AutomationScheduler`.
- **The data is intact.** The 14 datasets and 139 runs present on 2026-11-13 are all still there. The Postgres container
  and volume date from 2026-10-02.
- **No run data lives on local disk.** Run reports, configuration and results are in Postgres (`report_snapshot`,
  `dataset_snapshot`). Dataset-referenced runs read the catalog dataset, which is also in Postgres. Capture *evidence* is
  on disk, but runs never read it.

**Object storage (MinIO) is therefore not justified.** The fault is in querying, not in file management.

**B3. "Success / No success" in Automation is always empty, but not because of a bug in the filter.** Every day is
`PENDING` (`GET /api/automation/days`, live). A verdict needs the whole 7-day window to be complete (`DayScoring.kt:71-80`).
Two things keep that from happening:
- coverage only began on 7 Nov, so the windows include days that were never covered;
- capture is serialized to one operation at a time (`AutomationPipelineService.kt:86-96`,
  `CaptureOperationService.kt:33-36`), and buys from 10 Nov are still "in progress".

The filter code works; the data never reaches a final verdict. Phase 3 fixes how this reads. Capture throughput is out of
scope (see Open Questions).

### Other findings from the UX walk

- **The header search does not find mints.** The search says "Search runs, datasets, mints", but a mint returns
  "Nothing matches" (`CommandPalette.tsx`). Its entry "Automation: nightly sweep scoreboard" is stale: automation runs
  always-on.
- **Two control surfaces for one capture.** The Captures page lists 351 capture sessions. Most of them belong to
  automation ("Decu · …"), and 91 have a "Start wallet step" button. A person can drive an automation-owned capture by
  hand behind the pipeline's back.
- **Sessions that are already closing still offer to close.** Sessions stuck in `Closing` for days still show "Close
  session now".
- **Retry times lack a date.** "Next try 10:27" is shown with no date, even when that time is already in the past.
- **A run cannot lead back to its buy.** A run's breadcrumb goes to the capture and the dataset, never to the automation
  buy it came from.
- **Hints use 4 patterns.** There are 66 `hint=` subtitles, 31 `<Tooltip>`, **165 native `title=` attributes** (mouse
  only and invisible on touch), and a `Term`/glossary component used 3 times. The glossary has no automation terms
  (signal, worst case, unscored, variation, verdict, trading day, below filter).
- **`/api/automation` writes are not local-only.** `LocalAccessFilter` covers `/api/captures`, `/api/wallets` and dataset
  DELETE only. A Stop endpoint must not ship like that.

## Scope

### In scope
- **Phase 1:** B1 (token flow history), B2 (server-side run search), and B3's wording and filter honesty, each with
  tests that would have caught it.
- **Phase 2:** Stop and Restart for a token flow and a day flow, the `STOPPED` status, and an audit trail.
- **Phase 3:**
  - remove Captures and add "Capture a token" inside Automation;
  - the token page with full run history;
  - the Analytics landing page;
  - redesigned scoreboards and "Status by day";
  - one hint pattern.
- **Phase 4:** the decision on an analytics service, with the event catalogue. The recommendation is to defer it (no
  build).

### Out of scope
- Capture throughput: running more than one capture operation at a time, and the request budget. This is the real reason
  verdicts never finish. It needs its own plan (see Open Questions).
- Strategy semantics (dip rule, market-cap floor). These are the business decisions from the 2026-11-12 review.
- Authentication. There is still no Spring Security. The "who" in the audit trail is a label, not a verified identity.

## Key Decisions

1. **Record token flow history as an append-only event table, written in the same transaction as the item change.**
   *Rationale:* the transaction makes the history exact (no event without its state change, and no state change without
   its event). The table is also the natural outbox if an event stream is ever needed (Phase 4). *Rejected:* parsing
   logs (lossy, rotated); a JSON history column on the item (unbounded row growth, conflicts with `@Version`).
2. **Run search moves to the server, with a stable snapshot.** `GET /api/runs` gains filters (status, token, trading
   day, created range, dataset, free text) and an `asOf` instant. Page 2 is then cut from the same list as page 1, even
   while the sweep inserts. A "N new runs · show" notice replaces silent drift. *Rejected:* cursor-only paging, which
   loses page numbers and totals, both of which the user asked for ("correct counts").
3. **Token and anchor time are denormalized onto the run at persist time** (`simulation_run_tokens`,
   `simulation_runs.anchor_block_time`). They survive a dataset delete, and the filter needs no join into automation
   tables, which keeps the `replay` module free of `console.automation`. Existing rows are backfilled once.
4. **`STOPPED` is a new terminal item state.** It is never a failure: it is not in `SessionHealth.ISSUE_STATES`, never
   counted as failed, and not unscored-as-issue. A day whose items are all terminal (stopped included) can complete.
5. **Stop is cooperative.** A stop marks the item `stop_requested`. Work that is in flight then:
   - finishes, or reaches the capture worker's next checkpoint, through the existing `cancel` control
     (`AutomationCaptureCancellation`, `CaptureOperationService.kt:89-94`);
   - and the sweep stops between variations.

   Nothing is ever killed mid-write.
6. **A stopped flow can be restarted, not resumed in place.** Restart takes the item back to its last *completed* step,
   reusing the saved evidence. This is the same mechanism as today's retry (`AutomationItemProgress.retried`).
   *Why not resume:* the capture operation that was running was cancelled at a checkpoint, and its partial evidence is
   only trustworthy at step granularity. *Why allow it at all:* a stop means "not now", not "this is bad"; the buy is
   still wanted, for example after the request budget recovers.
7. **Stopping a day stops its future buys too** (user decision, 2026-11-16). A `automation_day_stops` row makes
   discovery write any later buy of that day directly as `STOPPED`. Discovery, other days, backfills and the scheduler
   carry on.
8. **No Kafka or ClickHouse (Phase 4).** See Phase 4. The WEEKLY_EXECUTION_PLAN out-of-scope list already excludes
   "Kafka … or a separate analytics database" (`docs/WEEKLY_EXECUTION_PLAN.md:51`).
9. **Analytics is the landing page (user decision).** It is built from today's Dashboard health strip and P&L summary,
   plus the automation verdict. The rest of today's Dashboard moves to where it belongs (see Phase 3).
10. **Manual capture lives inside Automation as "Capture a token" (user decision).** It stays a capture session, not an
    automation item, so the one-item-per-mint key is untouched. The backend capture API stays; only the UI moves.

## Module Contracts

| Module | Change | Kind |
|---|---|---|
| `replay` (public `SimulationRuns`) | `search(query: RunSearchQuery): RunSearchResult` alongside `page(...)`. `RunSearchQuery(statuses: Set<RunStatus>, mint: MintAddress?, anchorFrom/anchorTo: Instant?, createdFrom/createdTo: Instant?, datasetId: String?, text: String?, asOf: Instant, page: Int, size: Int)`. `RunSearchResult(page: PageResult<RunSummary>, statusCounts: Map<RunStatus, Long>)`. `RunSummary` gains `datasetName: String?`, `mints: List<MintAddress>`, `anchorBlockTime: Instant?`, `status: RunStatus`. | **Additive**. `page(...)` stays, so existing callers are untouched. |
| `replay` domain | `enum RunStatus { PROFIT, LOSS, FLAT, NO_ENTRY, OPEN }`, derived from the stored counts (Domain Rule R1). | New value type |
| `console.api` | `GET /api/runs` gains query params `status` (repeatable), `mint`, `anchorFrom`, `anchorTo`, `createdFrom`, `createdTo`, `q`, `asOf`. The response gains `asOf` and `statusCounts`. `GET /api/datasets` gains `q`. | Additive HTTP contract |
| `console.automation` | New: `GET /api/automation/items/{id}/events`; `GET /api/automation/tokens/{mint}` (flow, events, all runs on its datasets, capture operations); `POST /api/automation/items/{id}/stop`; `POST /api/automation/items/{id}/restart`; `POST /api/automation/days/{date}/stop`; `POST /api/automation/days/{date}/restart`. The bodies are `{ requestId, note? }`. | Additive |
| `console.automation` views | `AutomationItemView` gains `stopRequestedAt`, `stoppedAt`, `stoppedBy`, `stopNote`, `stoppedAtStep`. `DaySummary` gains `stopped: Int` and `dayStop: DayStopView?`. Evaluation rows gain `provisionalStatus` (Phase 3). | **Changes a response shape.** Additive fields only, but the frontend union type `ItemState` gains `STOPPED`. Frontend and backend must ship together. |
| `console.config.web` | `LocalAccessFilter` also covers `POST /api/automation/**`. | Behaviour change: a remote POST now gets 403 |

There is no break to the replay fixture schema and no change to simulation semantics.

## Domain Rules

**Runs (Phase 1)**
- **R1.** `RunStatus` is derived from the stored counts, in this order:
  - `filled_count = 0` → `NO_ENTRY`;
  - otherwise `open_count > 0` → `OPEN`;
  - otherwise the sign of `realized_pnl`: `PROFIT` above 0, `LOSS` below 0, `FLAT` at 0.

  Each run has exactly one status. Comparisons are exact `BigDecimal` at scale 12; there is no rounding.
- **R2.** For a fixed `asOf`, the union of all pages equals the filtered set. No run appears twice and none is skipped.
  Runs created after `asOf` never appear until the client asks for a new `asOf`.
- **R3.** `statusCounts` sum to `total` for the same filter, and are computed with every filter applied except
  `status`, so each count says how many you would get by choosing that status.
- **R4.** `mint` matches any run whose dataset contains that token, whether as a pair base mint or as the anchor buy's
  mint. Text `q` matches a run id, dataset id or configuration hash by prefix of at least 4 characters, or a dataset name
  case-insensitively. It is server-side and covers all runs.
- **R5.** `anchorFrom`/`anchorTo` select by the dataset's anchor buy time: half-open `[from, to)`, as UTC instants.
  The console converts a trading day into that range using the automation zone and day start. A run without an anchor
  (an imported dataset with no reference buy) never matches a day or anchor filter. It is still listed under "Any day".
- **R6.** Old runs are backfilled once, idempotently, by run id: rerunning the backfill changes nothing.
  - COPY runs: from their own `dataset_snapshot`.
  - DATASET_REFERENCE runs: from the catalog dataset.
  - A reference run whose dataset is missing keeps empty tokens, and the backfill logs it.

**Token flow history (Phase 1)**
- **H1.** Every change to an item's `state`, `step`, `step_attempt`, `operation_id` or `failure_*`, and every
  retry/stop/restart request, appends exactly one `automation_item_events` row in the same transaction. A rolled-back
  change leaves no event.
- **H2.** Events of one item are totally ordered by `seq`, which is gapless and starts at 1. `seq` is
  `max(seq)+1` under the item's `@Version` lock. Two concurrent writers conflict on the item version, and `uq (item_id,
  seq)` backs this up.
- **H3.** Events are never updated or deleted. A retry does not erase the earlier failure: the failure stays as its own
  event.
- **H4.** Items that existed before the table get one `HISTORY_STARTED` event carrying their state at migration time.
  The UI says "History recorded from <date>".
- **H5.** Failure messages are truncated to 512 characters (the current column size), and no secret or RPC URL may
  appear. The existing failure text is already safe, and the event copies the same field.

**Stop / Restart (Phase 2)**
- **S1.** Only non-terminal items can be stopped: `DISCOVERED`, `WAITING`, `BLOCKED_BUDGET`, `CAPTURING` and `CAPTURED`.
  Stopping a `STOPPED` item with any request id returns `200 Unchanged` (idempotent). Stopping `SIMULATED`, `FAILED`,
  `ABANDONED` or `SKIPPED_NOT_FIRST_BUY` returns `409 AUTOMATION_ITEM_FINISHED` with the current state, and changes
  nothing.
- **S2.** If no operation is attached and no sweep is running, the stop is immediate:
  - the item becomes `STOPPED` in one transaction;
  - `next_action_at` is cleared;
  - `stopped_at_step` is set to the current `step`;
  - a `STOPPED` event is written.
- **S3.** An item with a running capture operation becomes stop-requested. `stop_requested_at` and a `STOP_REQUESTED`
  event are written, and the capture `cancel` control is sent outside the transaction. The pipeline does not start a new
  operation for a stop-requested item. On the next poll the operation's end state (CANCELLED, or SUCCEEDED or FAILED if
  it ended first) moves the item to `STOPPED`. **A cancelled operation is never counted as a failed attempt.** The
  evidence of finished steps is kept.
- **S4.** A stop during the sweep is checked between variations. Sweep rows already written stay, and remain valid
  because each is an idempotent run. The item becomes `STOPPED`, and the event records how many of the 10 variations
  were done.
- **S5.** **Race with finishing.** Stop and the pipeline's `record` contend on the item's `@Version`:
  - if the pipeline commits first (for example SIMULATED), the stop answers `409 AUTOMATION_ITEM_FINISHED`;
  - if the stop commits first, `record` sees "changed meanwhile" and cancels any operation it just started.

  The existing escape path where a version conflict *throws* from `start()` without cancelling
  (`AutomationPipelineService.kt:217,313`) is fixed in the same unit.
- **S6.** **Stop during a retry.** A retry moves `FAILED` to a capture state, after which S2/S3 apply. A stop and a retry
  arriving together serialize on the version, and the loser gets 409 `AUTOMATION_CONFLICT` with the current state.
- **S7.** **Day stop:**
  - insert an `automation_day_stops(trading_day)` row, unique, so a second press is idempotent (200 Unchanged);
  - apply S2/S3 to every non-terminal item of the day, in pages of 200;
  - close each open or closing session of the day with the new reason `STOPPED`;
  - discovery writes any buy of a stopped day directly as `STOPPED`, with a `STOPPED` event and `stopped_by` = the day
    stop's actor.

  Other days, backfills, discovery and the scheduler are untouched. A backfill that owns items of the stopped day sees
  them as terminal and moves on.
- **S8.** **Children.** A token stop affects only that item. A day stop affects that day's items and sessions only. A
  manual capture ("Capture a token") is never touched by a day stop.
- **S9.** **Restart:**
  - Item: `STOPPED` → the step after `stopped_at_step`'s last completed step, using the same resume rule as retry.
    `retries` is unchanged; a separate `restarts` counter is added. Idempotent by `requestId`.
  - Day: delete the `automation_day_stops` row and restart every `STOPPED` item of the day. Sessions stay closed, and
    restarted items keep their session.
  - The pipeline then drives restarted items as usual.
- **S10.** **Accounting:**
  - `STOPPED` is in `AutomationItemState.TERMINAL`, so `complete` can become true;
  - it is not in `SessionHealth.ISSUE_STATES`;
  - `EvaluationScoring` does not score it (like `SKIPPED_NOT_FIRST_BUY`), but counts it in a separate `stopped` figure;
  - a verdict whose window has stopped buys is labelled "partial: N stopped". A stop never turns a day into a failure.
- **S11.** **Audit.** Every stop, stop request, restart and day stop records:
  - `actor`: the name typed in the confirm dialog, default "console";
  - `request_id`;
  - `at`;
  - `step`;
  - an optional `note` of at most 200 characters.

  These show in the trace. `actor` is a label, not an identity (see Open Questions).

**Determinism.** Every new timestamp comes from the injected `Clock` already used by the automation services. Event ids
are UUIDs from the existing id generator, and `seq` is the ordering key. There is no money arithmetic here beyond R1's
sign test.

## Data Model

There are no destructive changes. Every table and column below is new or nullable, or has a default. Locally,
`ddl-auto: update` adds them; prod needs the SQL below applied before deploying.

**`automation_item_events`** (entity `AutomationItemEventEntity`, `console.automation.infrastructure.persistence`)

| Column | Type | Notes |
|---|---|---|
| `id` | varchar(36) PK | |
| `item_id` | varchar(64) not null | the item id, no JPA association |
| `seq` | integer not null | `uq_automation_item_events_seq (item_id, seq)` |
| `at` | timestamptz not null | |
| `kind` | varchar(32) not null | `HISTORY_STARTED, STATE_CHANGED, OPERATION_STARTED, OPERATION_ENDED, ATTEMPT_FAILED, RETRY_REQUESTED, STOP_REQUESTED, STOPPED, RESTARTED, SWEEP_ENTRY_RECORDED` |
| `from_state`, `to_state` | varchar(24) | |
| `step` | varchar(16) | |
| `attempt` | integer | |
| `capture_session_id`, `operation_id` | varchar(80) | links to the capture console's own operation record (its inputs, outputs and evidence) |
| `requests_used` | integer | the requests this operation used, at its end |
| `failure_code` | varchar(64) | |
| `failure_message` | varchar(512) | |
| `actor` | varchar(80) | |
| `request_id` | varchar(80) | |
| `note` | varchar(200) | |
| `detail` | jsonb | small structured extras, such as sweep labels done; bounded at 4 KB by the service |

Index: `idx_automation_item_events_at (at)` for the Analytics recent-activity feed.

**`automation_items`** gains these columns:
- `stop_requested_at timestamptz`
- `stopped_at timestamptz`
- `stopped_at_step varchar(16)`
- `stopped_by varchar(80)`
- `stop_note varchar(200)`
- `stop_request_id varchar(80)`
- `restarts integer not null default 0`
- `restart_request_id varchar(80)`

`state` length 24 already fits `STOPPED`.

**`automation_day_stops`**: `trading_day date` PK, `stopped_at timestamptz not null`, `stopped_by varchar(80)`,
`note varchar(200)`, `request_id varchar(80) not null`.

**`automation_sessions.close_reason`** gains the value `STOPPED`. On an existing database, the enum CHECK widening is
handled by the schema-drift repair delivered on 2026-11-13.

**`simulation_runs`** gains:
- `anchor_block_time timestamptz`, with index `idx_simulation_runs_anchor (anchor_block_time, id)`;
- a `(realized_pnl, filled_count, open_count)` filter. The table is small, and the status predicates are ANDed with the
  `created_at` index. If the scan exceeds 50 ms at 100k rows, add `status varchar(16)`, written at persist. That is the
  measured trigger.

**`simulation_run_tokens`**: `run_id varchar(64)`, `mint varchar(44)`, primary key `(run_id, mint)`, index
`idx_simulation_run_tokens_mint (mint, run_id)`.

**Bounds.** Event growth is about 70 buys × ~20 events, roughly 1.4k rows a day. All list endpoints keep `MAX_PAGE_SIZE`
of 100. The token page loads events (bounded by one item) and runs (bounded by that token's datasets, about 10 per
variation set).

**Prod SQL** (applied by hand before deploying, `docs/schema/20261116-trace-stop.sql`):

```sql
create table automation_item_events (id varchar(36) primary key, item_id varchar(64) not null, seq integer not null,
  at timestamptz not null, kind varchar(32) not null, from_state varchar(24), to_state varchar(24), step varchar(16),
  attempt integer, capture_session_id varchar(80), operation_id varchar(80), requests_used integer,
  failure_code varchar(64), failure_message varchar(512), actor varchar(80), request_id varchar(80), note varchar(200),
  detail jsonb, constraint uq_automation_item_events_seq unique (item_id, seq));
create index idx_automation_item_events_at on automation_item_events (at);
alter table automation_items add column stop_requested_at timestamptz, add column stopped_at timestamptz,
  add column stopped_at_step varchar(16), add column stopped_by varchar(80), add column stop_note varchar(200),
  add column stop_request_id varchar(80), add column restarts integer not null default 0,
  add column restart_request_id varchar(80);
create table automation_day_stops (trading_day date primary key, stopped_at timestamptz not null,
  stopped_by varchar(80), note varchar(200), request_id varchar(80) not null);
alter table simulation_runs add column anchor_block_time timestamptz;
create index idx_simulation_runs_anchor on simulation_runs (anchor_block_time, id);
create table simulation_run_tokens (run_id varchar(64) not null, mint varchar(44) not null, primary key (run_id, mint));
create index idx_simulation_run_tokens_mint on simulation_run_tokens (mint, run_id);
-- plus widening the automation_items.state and automation_sessions.close_reason CHECKs, if prod has them
```

**Rollback.** Everything is additive: drop the new tables and columns. `STOPPED` rows must first be restarted, or set to
`ABANDONED`, before the old code runs. The old enum does not know `STOPPED` and would fail to load the item. Write this
into the release note.

## Transactions & Integration

- **Event writes.** Each event is written inside the transaction that already makes the change:
  `AutomationPipelineTransaction.record`, `AutomationRetryTransaction.retry`, `AutomationSessionTransaction.closeManually`,
  `AutomationSweepTransaction.record`, `AutomationScanWriter`, and a new `AutomationStopTransaction`. No new transaction
  boundary is added.
- **Remote calls stay outside transactions.** The capture `cancel` control is called after the stop transaction commits,
  as `AutomationSessionService.close` does today. A refused or failed cancel is logged; the next poll still resolves the
  item through S3.
- **No provider call is added.** Stop and Restart touch only Postgres and the local capture console. Everything is
  testable with the existing fakes (`FakeWalletActivityLookup`, `idleOperations`).
- **Configuration:** `trenching.automation.stop.day-batch-size` (default 200, at least 1). There is no feature flag:
  Stop is inert until pressed.
- **Backfill of runs (R6).** An `ApplicationRunner` in `replay`. It is idempotent, pages 200 runs at a time with one
  transaction per page, and runs in every profile except `test` (decided 2026-11-16 during implementation). In prod
  that happens after the SQL below has been applied by hand. Each run is marked indexed in
  `simulation_runs.search_indexed_at`, so it is processed once.
- **Anchor buy (decided in implementation).** The replay dataset schema has no explicit anchor field. The anchor is the
  tracked wallet's earliest successful buy on a supported route, ordered by slot, signature and instruction index.
  Automation and case datasets hold exactly one such buy, so for them this is exact.

## What To Implement

### Phase 1: bugs (backend and frontend; data: two new tables, one column, one backfill)

1. **Server-side run search (B2).**
   - In `replay`: the R1 status, `simulation_run_tokens`, `anchor_block_time` written at persist, the R6 backfill,
     `SimulationRuns.search` with `asOf` and status counts, and `datasetName` on the summary.
   - In `console.api`: the new query params on `GET /api/runs`, and `q` on `GET /api/datasets`.
2. **Runs page on the server contract.**
   - `filterRuns` is deleted.
   - Filters for status (All / Profit / Loss / Flat / No entry / Open, each with its count), token (mint, validated as a
     Solana address), trading day, created range, dataset (a server-searched combobox) and text. All of them live in the
     URL.
   - The `asOf` snapshot, with an "N new runs · Show" notice.
   - Every row shows its dataset name from the server, and a "From buy" link when the run is a sweep run.
   - The empty state names the active filters and offers "Clear filters".
3. **Token flow history (B1).** In `console.automation`: the event table and entity, writes in every transaction listed
   above, `HISTORY_STARTED` for existing items, and `GET /items/{id}/events`. It depends on nothing else and can run in
   parallel with 1–2.
4. **Trace panel on the selected buy.** In `features/automation`: a vertical timeline of events, with step, start and end
   times, duration, requests, attempt and error. Each capture operation expands to its inputs and outputs (from the
   capture operation record), with a link to its evidence. It depends on 3.
5. **B3 honesty.** The "Best status" filter says how many days are still pending and why ("12 days pending: buys still
   capturing"), instead of a bare "No days match". The final fix comes in Phase 3, unit 14.
6. **Small defects found on the walk.**
   - Mint search in the header palette, going to the token page or the day page and buy.
   - Rename the "nightly" palette entry.
   - Hide "Close session now" on `CLOSING` sessions; show "Closing: waits for N buys".
   - Show "next try" with a date whenever it is not today, and "overdue" when it is in the past.

#### Phase 1 HTTP contract (pinned 2026-11-16; backend and frontend build against exactly this)

Every change is additive. Existing fields and parameters keep their meaning.

**`GET /api/runs`**

Query parameters. All are optional and are combined with AND.

| Parameter | Format | Meaning |
|---|---|---|
| `page`, `size` | integers | As today. `size` is capped at 100. |
| `datasetId` | string | As today. |
| `status` | repeatable: `PROFIT`, `LOSS`, `FLAT`, `NO_ENTRY`, `OPEN` | Any of the listed statuses. |
| `mint` | base58 address | Must be a valid address, otherwise 400 `INVALID_REQUEST` with a `fields` entry. |
| `anchorFrom`, `anchorTo` | ISO-8601 instants | Half-open range on the anchor buy time. |
| `createdFrom`, `createdTo` | ISO-8601 instants | Half-open range on the creation time. |
| `q` | free text, 2–100 characters after trim | A run id, dataset id or configuration hash matches by prefix of at least 4 characters; a dataset name matches as a case-insensitive substring. |
| `asOf` | ISO-8601 instant | Only runs with `createdAt <= asOf`. When absent, the server uses now and returns the value it used. |

Error cases, each a 400 `INVALID_REQUEST`:
- an unknown `status` value;
- a `from` that is not earlier than its `to`;
- `q` outside 2–100 characters.

Response `RunPageResponse`. The existing `items`, `page`, `size` and `total` stay, and two fields are added:

| Field | Type | Meaning |
|---|---|---|
| `asOf` | instant | The snapshot the page was cut from. |
| `statusCounts` | object | Has all five keys, each a non-negative integer. Counted with every filter except `status`. |

Each item, `RunSummaryResponse`, keeps its existing fields and gains:

| Field | Type | Meaning |
|---|---|---|
| `status` | one of the five above | The run's derived status. |
| `datasetName` | string or null | The dataset's name. |
| `mints` | string[] | Sorted; empty when unknown. |
| `anchorBlockTime` | instant or null | The anchor buy's block time. |

The same item fields appear wherever `RunSummaryResponse` is returned (run start, dashboard).

**`GET /api/datasets`**

Adds an optional `q` (2–100 characters), matched as a case-insensitive substring of the name or a prefix of the id. Paging
and the response are unchanged.

**`GET /api/automation/items/{id}/events`**

Returns 200 with `{ itemId, events: [...] }`, ordered by `seq` ascending, or 404 `AUTOMATION_ITEM_NOT_FOUND`. Each event:

| Field | Type |
|---|---|
| `seq` | integer |
| `at` | instant |
| `kind` | `HISTORY_STARTED`, `STATE_CHANGED`, `OPERATION_STARTED`, `OPERATION_ENDED`, `ATTEMPT_FAILED`, `RETRY_REQUESTED` or `SWEEP_ENTRY_RECORDED`. `STOP_REQUESTED`, `STOPPED` and `RESTARTED` arrive in Phase 2, so clients must render unknown kinds generically. |
| `fromState`, `toState` | string or null |
| `step` | string or null |
| `attempt` | integer or null |
| `captureSessionId`, `operationId` | string or null |
| `requestsUsed` | integer or null |
| `failureCode`, `failureMessage` | string or null |
| `actor`, `requestId`, `note` | string or null |
| `detail` | object or null. `SWEEP_ENTRY_RECORDED` carries `{ planId, label, runId, outcome }`; `OPERATION_ENDED` carries `{ operationState }`. |

**Deviation from unit 2.** The "From buy" link on a run row goes to the day page of the trading day containing
`anchorBlockTime`. Linking to the exact buy needs the token page (Phase 3, unit 13). This is because the `replay` module
must not depend on `console.automation`.

### Phase 2: Stop control (backend and frontend; data: one table, eight columns)

7. **The `STOPPED` state and its accounting (S1, S10).**
   - Add it to the enum, transitions, `TERMINAL`, `EvaluationScoring` (`stopped` count), `DayScoring`, `SessionHealth`
     (unchanged, with a test that it stays out), and `isRetakeable`. A `STOPPED` item is not retakeable by a backfill.
   - Add it to the count maps and `SessionCloseReason.STOPPED`.
   - Frontend: the `ItemState` union and `ITEM_STATES`, `BUCKET_OF` → a new `'stopped'` bucket (a neutral grey with a ■
     glyph, never red), labels, `stepMarks`, `TERMINAL` in `AutomationDayPage`, and the mocks.
8. **Token stop and restart (S2–S6, S9, S11).**
   - `AutomationStopService` and `AutomationStopTransaction`.
   - The pipeline honours `stop_requested_at`, both when polling (S3) and in the sweep loop (S4).
   - The `start()` cancel-on-conflict fix (S5).
   - The endpoints, with `LocalAccessFilter` covering `POST /api/automation/**`.
9. **Day stop and restart (S7, S8).** `automation_day_stops`, plus the discovery check before writing a new item.
   Depends on 8.
10. **Stop in the UI.**
    - "Stop" on the selected buy and on the day header. The confirm dialog says what will happen ("The current step
      finishes or stops at its next safe point; nothing saved is lost; you can restart later"), with an optional name
      and note.
    - "Stopping…" while a stop is requested.
    - "Restart" on stopped items and days.
    - The trace shows "Stopped by <actor> at <time> during <step>". Depends on 7–9.

### Phase 3: restructure and UX redesign (mostly frontend; backend: token endpoint and `provisionalStatus`)

11. **Navigation.**
    - Remove the "Captures" nav entry, routes and palette entries.
    - `/captures`, `/captures/new` and `/captures/:id` redirect to their Automation equivalents, so bookmarks keep
      working.
    - Move `captureReasons` to `lib/` (it is imported by 5 non-capture modules).
    - Re-point the dataset, run, chart and recommendation links ("From capture", "Go to Captures", "Capture another
      token") to the token page or "Capture a token".
    - Automation tabs become: **Overview · Days · Tokens · Backfills · Sweep runs**.
12. **"Capture a token" inside Automation.**
    - The `NewCapturePage` form moves to `/automation/capture` as a primary action on every Automation tab.
    - Manual captures appear under **Tokens** with the source "Manual".
    - The capture detail (operations, attempts, evidence, calibration, register and download) becomes the trace section
      of the token page. Controls that move a capture by hand are shown **only** for manual captures; an
      automation-owned capture is controlled only through Stop, Retry and Restart.
13. **Token page `/automation/tokens/:mint` (new issue 2).**
    - Backend `GET /api/automation/tokens/{mint}`: the flow, all events, all runs whose `simulation_run_tokens.mint`
      matches (sweep runs and manual runs on its datasets, every plan and engine version), and its capture sessions.
    - Frontend: a header with state, Stop/Restart and key figures; **All runs** (a table of status, variation or
      configuration, created, realized and worst case, and a link); and **Trace** (unit 4's timeline).
    - Reached from the Day page buy rows, sweep runs, the run breadcrumb ("From buy"), and mint search.
14. **Scoreboards** (redesign below). The backend adds `provisionalStatus` to evaluation rows: the same rule, applied to
    the data so far.
15. **"Status by day"** (redesign below). The backend adds `stopped` to `DaySummary`, and nothing else; the day list
    already carries the counts.
16. **Analytics landing page** (redesign below). It replaces `DashboardPage` at `/`.
17. **Hints** (pattern below): an `InfoTip` component, `glossary.ts` extended with automation terms, an inventory, and
    migration of every page. Units 14–16 use the pattern from the start, so this unit runs first or alongside.

Units 11–13 touch the same route and link files, so one owner does them in order. Units 14, 15 and 17 are independent
of each other and of 11–13. Unit 16 depends on 14 (the verdict tile).

### Phase 4: analytics service

18. **Build nothing.** Write ADR `20261116-no-separate-analytics-store.md` with the comparison and revisit triggers
    below, and keep the event catalogue in it. No code.

## Phase 3 screens: before and after

**Analytics (landing, `/`)**
- *Before:* "Dashboard" mixes four jobs:
  - system health;
  - replay P&L (430 runs, almost all 0 P&L);
  - recent runs and activity feeds;
  - runtime gauges and an automation card.

  The automation verdict, which is the reason the system exists, is a small card halfway down.
- *After:* "Analytics: is the strategy working, and is the system healthy?"
  - **Row 1, health strip:** application, database, Solana, automation mode, and the requests meter against the 24 h
    cap. Each is a one-word state with a coloured dot; a click opens the detail.
  - **Row 2, P&L summary:**
    - realized P&L per quote mint;
    - the best variation over the last 7 trading days against the default (V00), with provisional/final;
    - trades counted against needed;
    - today's buys as captured, in progress, failed or stopped.
  - **Row 3:** cumulative realized P&L, and "How scenarios ended" (both kept from today).
  - Recent runs and activity move to the Runs page header. Runtime gauges move to API usage, renamed "System".

**Scoreboard: "Scoreboard · which variation works" (Automation Overview)**
- *Question it should answer:* "Over the last 7 trading days, which variation would have made the most money, how does
  it compare with the default, and is there enough evidence to trust it?"
- *Why it fails today:*
  - Every row says **Pending** even with 35 counted signals against 10 needed. The status is gated on day
    completeness, which the user cannot see.
  - "Net P&L · 0 in the middle" draws a bar plus a tick that coincide whenever realized equals worst case, which is
    every row today.
  - There is no comparison with the default: V00 sits in the middle of the list.
  - "Unscored 363" dwarfs "Signals 35" with no explanation (it means "not replayed yet").
  - Jargon: signal, worst case, unscored.
  - The sort puts status before money, so the order looks random.
- *After:* title **"Which variation makes money? Last 7 trading days"**.
  - A ranked dot-and-whisker chart: each variation is a row, the dot is worst-case net SOL and the whisker reaches
    realized, so a whisker appears only when they differ.
  - A **vertical reference line at the default (V00)** and a column **"vs default"** (+0.0476 SOL, green; −0.0423 SOL,
    red).
  - Sorted by worst case, best first, so V00 is visibly a line, not a row lost in the middle.
  - A status column with three plain states:
    - **Final ✓/✕**: the window is complete.
    - **Provisional ✓/✕**: enough trades, but N days still capturing, shown hatched.
    - **Too few trades**: "35 of 10 needed" becomes a check; under 10 it reads "6 of 10 needed".
  - **"Not counted yet: 300 capturing · 3 failed · 0 stopped"** replaces "Unscored".
  - Every header has an `InfoTip`.

**Scoreboard: "Scoreboard for the 7 trading days ending here" (Day page)**
- *Question it should answer:* "What did each variation do with **this day's** buys?"
- *Why it fails today:* it repeats the 7-day window table from the Overview with the same rows. On a day page this reads
  as the day's own result, and the figures (43 signals) are not that day's.
- *After:* title **"How each variation did on Wed 11 Nov"**. A compact table, sorted by P&L, with the V00 row pinned on
  top as the baseline. Columns:
  - buys replayed;
  - entered;
  - closed by take profit / stop loss / wallet sold / time;
  - realized SOL;
  - vs default.

  Below it, one link: "See the 7-day window ending this day →", which opens the Overview scoreboard scored at this day.

**"Status by day"**
- *Before:* a 10 × 14 grid of rolling 7-day verdicts. Today it is every cell ◷, so it shows zero information. It answers
  "is variation X consistent", not "what happened on each day". It never shows failures or stops, and a cell leads to
  the day only through one specific variation.
- *After:* title **"Days at a glance"**, a calendar strip of the last 14/30 days with one tile per day.
  - **Fill = how the day ended:**
    - Complete (solid);
    - Has failed buys (red outline and count);
    - Stopped (grey ■);
    - In progress (animated stripe);
    - Not covered (empty).
  - **Corner mark = money:** the day's best-variation realized P&L sign (+/−/0).
  - The tile text is "44 buys · 3 failed". Hover or focus shows the breakdown bar.
  - **One click opens the day.**
  - The legend sits above the strip, with plain words.
  - The variation-consistency grid moves under the scoreboard as a collapsible "Consistency by variation", and also
    gains Provisional states, so it carries information before days complete.

**Day page**
- *Before:* stop and trace are impossible; "Close session now" is shown on closing sessions; scoreboard as above.
- *After:*
  - Header actions: **Stop day** / **Restart day**.
  - Each buy row links to its token page.
  - The selected buy shows the trace timeline (Phase 1, unit 4).
  - The day scoreboard as redesigned.

**Runs page**
- *Before:* page-local filters, drift, dataset names missing after 100 datasets.
- *After:* server filters with counts, a stable snapshot, an always-named dataset, and a "From buy" link (Phase 1).

## Hint pattern (unit 17)

**One pattern, three tools.**
1. **Panel subtitle** (`hint` on `Section`): one sentence that says what the panel shows. It is always visible, never
   hidden behind an icon. Existing 66 uses: keep them, but cut each to one sentence. Definitions move out.
2. **`InfoTip`** (new): an ⓘ icon placed **right after the label it explains**. It opens a Mantine `Popover` on click,
   tap, focus or hover. It is reachable from the keyboard, readable by screen readers through `aria-describedby`, and
   works on touch. It is used for every metric or column header that is not self-explanatory.
3. **`Term`**: a dotted underline on a glossary word in running text, opening the same popover, with its text from
   `glossary.ts`.

**Wording rules:**
- start with what it is: "Worst case: net P&L if every position still open had lost its entry cost";
- at most 2 sentences;
- units spelled out;
- no internal codes.

**Banned:** native `title=` for explanations. Of the 165 uses, keep only those showing the full value of truncated text.
Ad-hoc `<Tooltip>` for definitions is replaced by `InfoTip`.

**Glossary additions:** trading day, session, buy, variation, default (V00), signal, trade counted, worst case, realized,
provisional, final, not counted, below the filter, stopped, sweep run, token flow.

**The audit** is a deliverable: `docs/ux/20261116-hints-inventory.md`, one row per hint (page, element, current
pattern, new pattern, new wording), generated with `rg` and finished by hand.

## Phase 4: analytics service decision (recommendation: do not build now)

**Events, should one ever be published.** These come straight from `automation_item_events`, which is a transactional
outbox:

| Event | Fields |
|---|---|
| `flow.discovered` | itemId, mint, tradingDay, blockTime, curvePayment |
| `flow.step_started` / `flow.step_ended` | itemId, step, attempt, operationId, at, durationMs, requestsUsed, outcome |
| `flow.failed` | itemId, step, failureCode, at |
| `flow.retry_requested` / `flow.restarted` | itemId, requestId, actor, at |
| `flow.stop_requested` / `flow.stopped` | itemId, step, actor, note, at, sweepLabelsDone |
| `flow.simulated` | itemId, at |
| `sweep.entry_recorded` | itemId, planId, label, runId, outcome, realizedPnl, worstCasePnl, signal |
| `day.stopped` / `day.restarted` | tradingDay, actor, at |

**Comparison:**

| | Main Postgres (recommended) | Kafka + ClickHouse |
|---|---|---|
| Volume fit | ~1.4k events and ~300 runs a day; aggregates over this size answer in milliseconds | Built for millions of events a second; idle here |
| Consistency | Filters, counts and detail read the same rows in the same transaction, so counts always agree | Eventually consistent: a count can disagree with the detail page, the very class of bug this plan fixes |
| Latency | Immediate | Seconds of pipeline lag, plus consumer lag |
| Complexity | 2 tables and indexes | 2 new stateful services in Compose, schemas, a consumer, replay and backfill tooling, and exactly-once concerns; another single copy of data with no backup |
| Fit with the agreed plan | In line | Explicitly out of scope (`WEEKLY_EXECUTION_PLAN.md:51`) |

**Revisit when any of these holds:**
- events pass about 10 M rows, or a status-count query exceeds 1 s p95 after indexing;
- a second consumer needs the events (for example Telegram alerts);
- more than one instance writes.

The first step would then be a transactional-outbox relay from `automation_item_events`, not a rewrite.

## Verification

**Backend (JUnit 5, Testcontainers through `PostgresTestDatabaseConfiguration`; never the real DB)**
- `RunStatusTest`: R1 for every combination, including `filled=0, open>0` (NO_ENTRY wins) and exact zero.
- `SimulationRunSearchIntegrationTest`:
  - **the test that would have caught B2:** 45 runs, 3 of which are LOSS, placed on page 3 of 20; `status=LOSS`
    returns exactly those 3 with `total=3`;
  - R2 page union with inserts after `asOf`;
  - R3 counts;
  - R4 mint and text across every page;
  - R5 half-open day bounds at 12:00 UTC+5;
  - 150 datasets: names present on every row.
- `RunTokenBackfillTest`: idempotent rerun; COPY versus REFERENCE; a missing dataset.
- `AutomationItemEventsTest`:
  - H1 one event per change in each transaction class;
  - H1 rollback leaves no event;
  - H2 concurrent writers produce gapless `seq`;
  - H3 a retry keeps the earlier failure event;
  - H4 history start.
- `AutomationStopServiceTest` / `AutomationStopDatabaseTest`, one test each:
  - S1 every source state;
  - S2 immediate;
  - S3 a running operation is cancelled and the item becomes STOPPED, not FAILED, and the attempt is not counted;
  - S4 a mid-sweep stop keeps rows written so far;
  - S5 both orders of the stop and finish race, plus the `start()` conflict cancels its operation;
  - S6 stop against retry;
  - S7 a day stop, then a buy discovered later lands as STOPPED, and other days are untouched;
  - S9 restart resumes from the last completed step and is idempotent;
  - S10 a day with stopped items completes, is not in ISSUES, and its verdict is flagged "partial".
- `LocalAccessFilterTest`: a remote `POST /api/automation/items/x/stop` gets 403.
- **Test isolation:** every new test uses the test profile and Testcontainers. The startup guard added on 2026-11-13
  must cover them. No test may reach `localhost:5433`.

**Frontend (Vitest + MSW)**
- `RunsPage.test.tsx`: a filter sends the server params, renders the counts, and handles "N new runs"; the empty state
  names the filters. **The regression test:** a LOSS run that exists only on page 2 is found.
- `TraceTimeline.test.tsx`: the events render in order with durations and errors, plus the history-started notice.
- `StopControls.test.tsx`:
  - the confirm text;
  - "Stopping…";
  - a second click is harmless;
  - 409 finished shows the final state;
  - Restart.
- `TokenPage.test.tsx`: all runs across plans, the trace, and manual versus automation controls.
- `ScoreboardRedesign.test.tsx`: the baseline line, the vs-default deltas, and the provisional/final/too-few states.
- `DaysAtAGlance.test.tsx`: the five fills and a one-click link.
- `InfoTip.test.tsx`: keyboard, touch and `aria-describedby`.
- Redirects: `/captures/:id` goes to Automation.

**In the browser:** each phase is checked with `frontend-mocks` and against a **scratch backend on a copy of the DB**
(`:8091`, as on 2026-11-13). The live database is not used. Screenshots go in `frontend/qa/2026111x-*`.

**Commands** (one Gradle build at a time; `JAVA_HOME=C:/Users/user/.jdks/ms-21.0.10`):
- `./gradlew test`
- `npm --prefix frontend run lint`
- `npm --prefix frontend test`
- `npm --prefix frontend run build`
- `npm --prefix frontend run format:check`

## Documentation Sync

- **README:** console sections (navigation without Captures, Analytics landing); the run search parameters; Stop and
  Restart; the "Local schema repair" note covers the new enum values.
- **ADRs:**
  - `20261116-stopped-flows-not-failures.md`: S10's effect on verdicts, which affects evaluation fidelity;
  - `20261116-no-separate-analytics-store.md` (Phase 4).
- `docs/schema/20261116-trace-stop.sql` with the prod SQL.
- `docs/ux/20261116-hints-inventory.md`.
- `IMPLEMENTATION_STATUS.md`: one entry per phase.
- The 2026-11-03 automation UX plan gets a "superseded in part by" note.

## Acceptance Criteria

**Phase 1**
- With 720+ runs, "Loss" on the Runs page lists every LOSS run across all pages. Its count equals
  `select count(*) … where realized_pnl < 0 and filled_count > 0 and open_count = 0`.
- Filtering by a token mint, a trading day or a created range gives the same rows as the equivalent SQL, with correct
  counts.
- A run created 30 days ago is found by its token and its day.
- While the sweep inserts runs, paging forward never shows a duplicate or skips a run, and a "N new runs" notice
  appears.
- With 150 datasets, every run row shows its dataset name.
- A finished buy's trace shows every step with start, end, duration, requests and attempt. A retried buy shows the
  original failure and the retry as separate events.
- `automation_item_events` has a gapless `seq` per item.

**Phase 2**
- Stopping a buy mid-capture leaves `state=STOPPED` and `stopped_at_step` set. The capture operation ends `CANCELLED`,
  `step_attempt` is unchanged, and no FAILED appears.
- A second Stop returns 200 Unchanged.
- Stopping a SIMULATED buy returns 409 `AUTOMATION_ITEM_FINISHED`.
- Stopping a day stops all its unfinished buys. A buy discovered later for that day is stored as STOPPED and never gets
  an `operation_id`. Other days keep advancing on the next tick.
- Stopped buys appear as "Stopped" (grey) and never in "With failed buys", failed counts or "unscored". A day whose only
  unfinished buys were stopped shows Complete.
- The trace shows "Stopped by <name> at <time> during <step>".
- Restart resumes from the last completed step and the buy reaches Replayed.
- A remote POST to a stop endpoint gets 403.

**Phase 3**
- There is no "Captures" entry in the nav or the palette. `/captures/:id` redirects.
- "Capture a token" starts a manual capture from Automation, which shows under Tokens.
- The token page lists every run of that token: 10 sweep runs plus any manual runs.
- `/` is Analytics with the health strip and P&L summary.
- The overview scoreboard shows V00 as a reference line with vs-default deltas, and "Provisional" when the window is
  incomplete but has ≥10 trades.
- "Days at a glance" distinguishes complete, failed, stopped, in progress and not covered, and opens a day in one click.
- No native `title=` remains as an explanation. Every metric header in Automation and Analytics has an `InfoTip` that
  works by keyboard.

**Phase 4**
- The ADR is merged; no new service is in Compose.

## Open Questions

1. **"Who" stopped it.** There is no authentication, so `actor` is whatever name is typed (remembered in this browser),
   defaulting to "console". *Recommendation:* accept this now and record the limitation in the trace ("name as typed").
   Real identity needs authentication, which is a separate decision.
2. **Capture throughput.** Verdicts stay pending mostly because one capture runs at a time, and buys from 10 Nov are
   still in progress. *Recommendation:* a separate plan (parallel operations within the request cap). Until then,
   Phase 3's "Provisional" status keeps the scoreboard useful.
3. **Should a FAILED buy be stoppable** ("give up, don't count it as a failure")? *Recommendation:* no. That would let a
   stop hide real failures. A failed buy stays failed until it is retried.
4. **Old runs whose dataset is gone** (dataset-referenced runs only). There are none today. *Recommendation:* show them
   with "token unknown" rather than guessing.
5. **Analytics content.** You asked for the health strip and P&L summary. *Recommendation:* also include the automation
   verdict tile in Row 2, because it is the one number that says whether the strategy works. Say if you want it left
   out.
