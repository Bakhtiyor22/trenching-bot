# Always-on automation, trading days, run now and backfill

> Let automation run around the clock, group and score results by trading day, and add a "run now" trigger and a
> date-range backfill with filters, a dry run and its own request budget.

Status: Delivered (2026-11-03; backend units 1–4. Approved 2026-11-03 as written, amended by the user's review decisions below)

## Context

The automation delivered on 2026-11-03 ([plan](20261102-decu-capture-sweep-automation.md),
[decision](../../adr/20261102-automated-capture-and-sweep-evaluation.md)) discovers Decu's Pump.fun buys, captures each one,
replays sweep plan `decu-sweep-1` and scores the configurations. Today:

- **Discovery runs only inside a polling window**, 18:00–05:00 UTC+5 (`PollWindow`, `AutomationProperties.pollFrom/pollTo`,
  which must differ), plus morning scans for sessions waiting to close (`AutomationDiscoveryService.discover`,
  `AutomationDiscoveryTransaction.closeAwaitsScan`). It runs on the scheduler's fixed delays (`AutomationScheduler`), and
  there is no way to start it by hand.
- **Results are grouped by idle-gap sessions** (60 minutes without a buy), and **scoring is per closed session** over it and
  the 6 earlier evaluated sessions (`AutomationEvaluationTransaction`, stored once in `automation_evaluations`, Rule 26).
- **Discovery only moves forward** from the wallet's first scan (now − `initial-lookback`, 12 h). Nothing before that is
  ever read.

The user wants to run it for a month and test it during the day (decisions of 2026-11-03):

- **Always on:** capture around the clock; the window becomes optional.
- **Trading day:** results grouped by a noon-to-noon UTC+5 day, so one Decu night (about 19:00–03:00) is one day; idle-gap
  sessions stay underneath. Scoring moves to the last 7 trading days.
- **Simulation stays per buy**, as now (each buy is replayed once its capture completes, about 35 minutes after it).
- **Manual trigger:** "run now", plus a date-range backfill with filters: a minimum signal size, a token list, a
  discovery-only dry run and a request budget.

## Scope

### In scope

- An optional polling window (always-on mode).
- `POST` "run now" for live discovery.
- Trading days: sessions never cross a trading-day boundary; day views; day-based scoring over 7 trading days, computed on
  read.
- Backfill jobs: create, list, read, cancel; dry run; filters; their own request budget; their items captured and swept by
  the existing pipeline, after live items.
- README, ADR amendment, reference SQL.

### Out of scope

- The frontend controls and the day views. They follow in their own session once this HTTP contract is frozen (unit 5).
- Re-simulating already captured buys under other configurations (that is a new sweep plan, a separate change).
- Any engine or simulation-semantics change. `dip-paper-v6` is untouched.
- Notifications (the user said "not now").
- Changing the Helius plan or provider.

## Key Decisions

1. **Always-on is a mode, not a 24-hour window.** New property `trenching.automation.poll-always` (default `false`). When
   true, discovery runs on every discovery interval and the window and morning-scan logic are skipped. `PollWindow` keeps
   its own validation for window mode. Rationale: a window from 05:00 to 05:00 is refused today, and a mode makes the intent
   explicit in config and in `/status`.

2. **"Run now" queues one discovery pass on the scheduling thread.** Discovery, the pipeline, the sweep and session closing
   all share Spring's single scheduling thread today, which is what keeps them from running at the same time. A manual
   run must keep that property, so the endpoint hands one discovery pass to that thread and returns `202`. A second request
   while one is queued or running returns the same `202` and queues nothing more. It ignores the window, in both modes.
   Capture then follows on the pipeline's own one-minute tick. Rejected: running the scan on the HTTP thread (it would race
   the scheduled scan over the watermark row and fail on its `@Version`).

3. **A trading day runs from 12:00 to 12:00 UTC+5 and is named by its starting date.** New property `trading-day-start`
   (default `12:00`, in `zone`). Day `2026-11-03` is `[2026-11-03T12:00+05, 2026-11-04T12:00+05)`. Noon is the quietest
   point of Decu's day, so a night is never split. A session never crosses a day boundary: a buy joins an open session only
   if it falls in the same trading day, and regrouping and merges respect it too. No column is needed: a day's sessions
   are those whose `first_buy_at` lies in its range, which the existing index `(wallet, first_buy_at)` serves.

4. **Scoring is per trading day and computed on read, not stored.** Day D's verdict per configuration covers trading days
   D−6 … D (seven calendar trading days, including days with no buys, which count as zero signals). It uses the existing
   tally (`EvaluationScoring`: Rules 22–24 on scored and unscored items) and the existing rule: SUCCESS when net P&L
   > `min-net-pnl-sol` and signals ≥ `min-signals`. Rationale: a backfill fills older days after newer ones were already
   scored, so stored verdicts would go stale, and Rule 26 forbids replacing them. Computing on read is cheap: a 7-day window
   holds at most a few hundred sweep rows. The `automation_evaluations` table stops being written; its entity is removed,
   the table is left in place (no destructive change), and the README says it can be dropped by hand.

5. **A day has a verdict only when its whole window is complete.** A day is *complete* when discovery has covered all of it
   (live coverage or a finished capturing backfill), every session in it is `CLOSED` and every item is terminal. If any of
   D−6 … D is not complete, day D shows `PENDING` with the first incomplete day, instead of a verdict. The three verdicts
   (SUCCESS › NO_SUCCESS › NO_RESULT) are unchanged; `PENDING` is a state, not a fourth verdict.

6. **A backfill extends history backwards; it never overlaps what is already covered.** Live discovery covers
   `[covered_from, discoveredThrough]` for the wallet without gaps (catch-ups page back to the watermark after any pause).
   A capturing backfill must end at or before `covered_from` and must not overlap another capturing backfill. When it
   finishes and its end meets `covered_from`, `covered_from` moves back to its start. This avoids two discoveries writing
   items for the same time. A dry run writes no items, so it may cover any range.

7. **Backfill items go through the same pipeline, after live items.** Discovery writes backfill buys as ordinary
   `automation_items` in their own sessions (tagged with the backfill id), so capture, the sweep and day scoring are
   unchanged. The pipeline orders due items live first, then backfill items oldest first, so a month of backfill never
   delays tonight's captures. There is still a single capture worker.

8. **A backfill has its own request budget, inside the shared 24-hour cap.** Its spend is its discovery requests
   (`WalletActivityPage.requestsUsed`) plus its items' `requests_used`. Before starting a capture step for one of its items,
   the pipeline checks that the spend plus one operation's recovery allowance (as `AutomationRequestCap` already does) fits
   the budget. If not, the item ends `FAILED` with `BACKFILL_BUDGET` (unscored) and the backfill moves to `STOPPED_BUDGET`
   once nothing is running. Backfill discovery also pauses while the shared 24-hour total is within
   `backfill-reserve-requests` (default 1,000) of the cap, so live capture keeps headroom.

9. **The filters narrow a backfill; they never widen what the sweep can use.**
   - **Minimum signal size:** an override of the capture filter, at or above `capture-min-signal-sol` (1.5 SOL). A lower
     value is refused: every sweep entry needs at least 1.5 SOL (V08), so smaller buys would all end
     `SIGNAL_BELOW_MIN_SIZE` and only spend requests.
   - **Tokens:** up to 50 mints. Only their buys become items; the rest are counted as skipped.
   - **Dry run:** scans the range, writes no items or sessions, and reports buys per trading day, buys passing the filter,
     distinct mints, the discovery requests spent and an estimate of capture requests. The estimate is the median
     `requests_used` of the wallet's captured items times the passing buys, or none when nothing has been captured yet.
   - **Request budget:** required, between 100 and `backfill-max-requests` (default 20,000).

   "Run now" takes no filters: it is live discovery started early. Date-range work is a backfill.

10. **Ranges are given in trading days.** A backfill takes `fromDay` and `toDay` (inclusive dates), converted to
    `[fromDay 12:00, toDay+1 12:00)` UTC+5. A capturing backfill's end is clipped to `covered_from`, so the partial day
    before live coverage starts can still be filled. Span at most `backfill-max-days` (default 31).

## Module Contracts

All inside `console.automation`; no other module's public contract changes. The existing ports used:
`WalletActivityLookup.recent` (unchanged), `ApiUsageReports.report`, the capture console services (unchanged).

**HTTP (owned by `console.automation.api`)**

| Method and path | Purpose |
|---|---|
| `POST /api/automation/discovery/run` | Queue one live discovery pass. `202` with `{ queued, alreadyQueued }`. `409 AUTOMATION_DISABLED` when automation is off. |
| `GET /api/automation/days?from=&to=` | Trading days in the date range (default the last 14, at most 62): date, completeness, sessions, buys, below-filter, captured, simulated, failed, abandoned, requests used, best verdict. Newest first. |
| `GET /api/automation/days/{date}` | One day: its sessions and items, its coverage, and its per-configuration rows. |
| `GET /api/automation/evaluations?from=&to=&label=&status=&sort=` | **Changed shape (contract change for the frontend):** rows are keyed by trading day instead of session. Each row: `day`, `planId`, `label`, `windowDays` (dates), `signals`, `scored`, `unscored`, `realizedPnl`, `worstCasePnl`, `status` (`SUCCESS`, `NO_SUCCESS`, `NO_RESULT` or `PENDING`), `pendingDay` (when pending). Default sort: status (SUCCESS › NO_SUCCESS › NO_RESULT › PENDING), then worst-case P&L descending, as approved. |
| `POST /api/automation/backfills` | Create. Body: `requestId`, `fromDay`, `toDay`, `dryRun`, `requestBudget`, optional `minSignalSol` and `mints`. `201` with the backfill; the same `requestId` returns the stored one. Field errors use the console's existing `400` shape. `409` codes: `CAPTURE_DISABLED` (a capturing backfill while `capture-enabled=false`), `BACKFILL_OVERLAPS_COVERAGE`, `BACKFILL_ACTIVE` (one is already active for the wallet). |
| `GET /api/automation/backfills` | Page of backfills, newest first. |
| `GET /api/automation/backfills/{id}` | One backfill with its counts, spend, dry-run summary and items' states. |
| `POST /api/automation/backfills/{id}/cancel` | Body `requestId`. Stops discovery; non-terminal items become `ABANDONED`. Idempotent. |

`/status` gains `mode` (`ALWAYS` or `WINDOW`), `manualRunQueued`, `coveredFrom`, `tradingDayStart` and the active backfill
id. The session endpoints stay and gain `tradingDay` and `backfillId`. The session evaluation fields disappear with the
session evaluations.

**Domain types (new, `console.automation.domain`)**

- `DiscoveryMode` — `ALWAYS` or `WINDOW(PollWindow)`, with `scansAt(instant)`.
- `TradingDay(date)` with `range(start, zone)`, `of(instant, start, zone)`. Uses `java.time` only.
- `BackfillRequest`, `BackfillFilter` (min signal lamports, mints), `BackfillState` (`QUEUED`, `SCANNING`, `CAPTURING`,
  `DONE`, `STOPPED_BUDGET`, `CANCELLED`), `DaySummary`, `DayVerdict`.
- Exceptions under the existing `AutomationException` base: `BackfillOverlapsCoverageException(from, to, coveredFrom)`,
  `BackfillActiveException(wallet, activeId)`, `CaptureDisabledException`, `AutomationDisabledException`,
  `BackfillNotFoundException(id)`.

## Domain Rules

Numbering continues from the delivered plan's Rules 1–27.

28. **Always-on.** With `poll-always=true`, every discovery interval scans, whatever the time; `closeAwaitsScan` is not
    consulted. With `false`, behaviour is exactly today's.
29. **Run now** queues at most one pass. It scans like a scheduled pass (same watermark, cursor and bounds), whatever the
    mode or window. With automation off, `409`. A provider that cannot be read skips the pass, as a scheduled one does.
30. **Trading day.** `TradingDay.of(t) = date(t in zone − trading-day-start)`; its range is `[date at start, date+1 at
    start)`. A buy exactly at 12:00:00 belongs to the day that starts then.
31. **Sessions stay inside one day.** A buy joins an open session only if both are in the same trading day; a buy that
    would bridge two sessions merges them only if all three are in the same day; `SessionIdleRule.runs` also splits at a
    day boundary. Negative case: buys at 11:50 and 12:10 are two sessions on two days even though 20 minutes apart.
32. **Coverage.** `covered_from` is set on the wallet's first scan (now − `initial-lookback`) and moved back only by a
    finished capturing backfill whose end meets it. A day is covered when its whole range lies within
    `[covered_from, discoveredThrough]` or within finished capturing backfills.
33. **Completeness.** A day is complete when covered, every session in it is `CLOSED` and every item is terminal. A day
    with no buys and full coverage is complete with zero signals.
34. **Day verdict.** For each entry of the active plan, day D's row tallies the items of the sessions in D−6 … D under
    Rules 22–24. If any of the seven days is incomplete, the row is `PENDING` with `pendingDay` = the oldest incomplete day.
    The same data always gives the same rows (pure function of stored rows; no clock except for "is the day over").
35. **Backfill validation.** `fromDay ≤ toDay`; `toDay+1 12:00 ≤ now`; span ≤ `backfill-max-days`; `requestBudget` in
    `[100, backfill-max-requests]`; `minSignalSol` positive, whole lamports, ≥ `capture-min-signal-sol`; `mints` 1–50
    distinct valid base58 addresses. A capturing backfill needs `capture-enabled`, its clipped range must be non-empty, end
    at or before `covered_from`, and overlap no capturing backfill in any state but a `CANCELLED` one that created no item.
36. **One active backfill per wallet** (`QUEUED`, `SCANNING` or `CAPTURING`), enforced by the unique `active_wallet` column
    (set while active, null otherwise) and checked first in the service. Postgres treats nulls as distinct.
37. **Backfill discovery** pages newest first from the range end down to the range start, using its own cursor
    (`resume_before`) and stopping at `from` by action block time or the listing's block times (as `DiscoveryBound.Since`).
    It runs on the scheduler tick, at most `max-discovery-pages` per tick, and only while the shared 24-hour total is
    below the cap minus `backfill-reserve-requests`. A provider error skips the tick and leaves the cursor.
38. **Backfill items and sessions.** Buys that pass the backfill's filter (its minimum, or the capture filter, plus its
    token list) become items with `backfill_id`. They are placed only among the same backfill's open sessions (Rules 10,
    31). Rule 7 still applies across sources: an earlier buy found by a backfill replaces a live item only while that item
    is `DISCOVERED` with no capture step; otherwise the live item is kept and a WARN is logged. When the scan reaches the
    range start, all the backfill's open sessions become `CLOSING` (the scan covers them) and the backfill becomes
    `CAPTURING`, or `DONE` for a dry run.
39. **Backfill budget.** Spend = discovery `requestsUsed` + Σ items' `requests_used`. A capture step for a backfill item
    starts only if spend + the recovery allowance ≤ budget; otherwise the item ends `FAILED`/`BACKFILL_BUDGET`. A backfill
    in `CAPTURING` becomes `DONE` when all its items are terminal, or `STOPPED_BUDGET` when any ended `BACKFILL_BUDGET`.
    Discovery that would pass the budget stops the scan with `STOPPED_BUDGET`; the items already found still run, within
    the budget.
40. **Pipeline order.** Due items are taken live first (oldest buy first), then backfill items (oldest buy first). The
    shared 24-hour cap and `BLOCKED_BUDGET` apply to both, unchanged.
41. **Cancel** sets `CANCELLED`, clears `active_wallet`, stops discovery, closes the backfill's sessions as a manual close
    does (non-terminal items `ABANDONED`) and cancels a running capture operation through `AutomationCaptureCancellation`.
    Cancelling a finished backfill returns it unchanged.
42. **Dry-run summary** is stored once on the backfill: actions scanned, buys found, buys passing the filter, distinct mints,
    buys per trading day (JSON text, at most 62 entries), discovery requests, and the capture estimate (integer requests,
    the median rounded up; null without captured items).
43. **Idempotency.** `requestId` is unique on backfills (create) and reused for cancel; ids are SHA-256 of
    `wallet|requestId`. Run now needs none (it is a single-flight flag).
44. **No `Instant.now()`.** All times come from the injected `SimulationClock`.

Number types: SOL amounts `BigDecimal` at scale 12 (P&L as today); lamports `BigInteger`; request counts `Long`. The
estimate median rounds `RoundingMode.CEILING` to whole requests.

## Data Model

Additive only.

`automation_backfills` — `AutomationBackfillEntity` (assigned id, `Persistable`, `@Version`)

| Column | Type |
|---|---|
| `id` | `varchar(64)` primary key |
| `request_id` | `varchar(80) not null`, unique `uq_automation_backfills_request` |
| `wallet` | `varchar(44) not null` |
| `active_wallet` | `varchar(44) null`, unique `uq_automation_backfills_active` |
| `from_day`, `to_day` | `date not null` |
| `range_start`, `range_end` | `timestamptz not null` (after clipping) |
| `dry_run` | `boolean not null` |
| `min_signal_lamports` | `numeric(20,0) null` |
| `mints` | `text null` (JSON array) |
| `request_budget` | `bigint not null` |
| `discovery_requests` | `bigint not null` |
| `state` | `varchar(16) not null` |
| `resume_before` | `varchar(88) null` |
| `actions_scanned`, `buys_found`, `buys_passing`, `items_created`, `skipped_by_filter` | `int not null` |
| `summary` | `text null` (dry-run JSON) |
| `estimated_requests` | `bigint null` |
| `created_at`, `updated_at` | `timestamptz not null` |
| `finished_at` | `timestamptz null` |

Indexes: `idx_automation_backfills_wallet_created (wallet, created_at)`.

Changed tables (nullable columns, so `update` adds them to existing rows):

- `automation_items.backfill_id varchar(64) null`, index `idx_automation_items_backfill (backfill_id, state)`.
- `automation_sessions.backfill_id varchar(64) null`.
- `automation_watermarks.covered_from timestamptz null`. The reference SQL fills existing rows with the wallet's earliest
  session `created_at` minus 12 hours; a null value means "no backfill allowed yet" (refused with
  `BACKFILL_OVERLAPS_COVERAGE`) until it is set.

Removed from code, kept in the database: the `automation_evaluations` entity and repository. The table stays; dropping it
by hand is optional.

Production SQL is appended to a new `docs/schema/V20261103120000__automation_backfill.sql` (create table, add columns,
CHECK on `state`, fill `covered_from`).

## Transactions & Integration

- Run now: the controller calls `AutomationManualRun.request()`, which sets a single-flight flag and schedules one call of
  `AutomationDiscoveryService.discover(ignoreWindow = true)` on the application's `TaskScheduler` (the same single thread).
- Backfill discovery: provider calls outside any transaction; one `@Transactional` write per page batch (items, sessions,
  counts, cursor, spend), like live discovery. `@Version` on the backfill row guards against a concurrent cancel; the scan
  rolls back and the next tick sees `CANCELLED`.
- Day queries and verdicts: `@Transactional(readOnly = true)`, bounded: at most 62 days per list, 7 + 62 days of sweep rows
  loaded in one query by session ids, never per item.
- Provider: `WalletActivityLookup` only (Helius RPC via `SOLANA_RPC_URL`, configured by the user; never read by agents).
  Captures use the existing capture console with the existing per-operation budget and recovery.

New properties under `trenching.automation`, validated at startup like the existing ones:

| Property | Default | Bounds |
|---|---|---|
| `poll-always` | `false` | — |
| `trading-day-start` | `12:00` | any `LocalTime` |
| `backfill-max-days` | `31` | 1–62 |
| `backfill-max-requests` | `20000` | 100–500,000 |
| `backfill-reserve-requests` | `1000` | 0 to `max-requests-per-24h` − 1 |

## What To Implement

1. **Always-on and run now.** `DiscoveryMode`, `poll-always`, the manual-run trigger and endpoint, `/status` fields.
   Independent of the rest.
2. **Trading days and day scoring.** `TradingDay`, the day boundary in session placement, regrouping and merges,
   `covered_from`, completeness, day verdicts computed on read, `/days`, `/days/{date}`, the new `/evaluations`; removal
   of session evaluation writing. Owns all changes to `automation_sessions`, `automation_watermarks` and the reference SQL
   file.
3. **Backfill.** Entity and repository, create/list/read/cancel, validation, backfill discovery on the tick, dry-run
   summary, budget and pipeline ordering. Depends on 2 (trading days, `covered_from`, session tagging); adds its table and
   the `backfill_id` columns to the SQL file after 2.
4. **Docs.** README Automation section, an amendment to the automation ADR (always-on, trading-day scoring computed on read,
   backfill), `docs/REAL_DATA_CAPTURE.md` if it describes the window.
5. **Frontend (separate session, after 1–3 freeze the contract).** Run now button and status, day list and day detail,
   evaluation table by day, backfill form (date range, minimum signal size, tokens, dry run, budget) with its progress and
   dry-run estimate.

Units 1 and 2 touch different files except `AutomationProperties`, `AutomationController` and `/status`; give those
three to unit 2 and let unit 1 add its parts after, or run them in sequence.

## Verification

Test-first. Fixtures and fakes only; no live provider.

| Test class | Must prove |
|---|---|
| `DiscoveryModeTest` | Always scans at any hour; window mode equals today's `PollWindow` |
| `AutomationManualRunTest` | One queued pass for two requests; runs outside the window; `409` with automation off |
| `TradingDayTest` | 11:59:59 and 12:00:00 fall on different days; the date name; DST-free zone math |
| `AutomationDiscoveryServiceTest` (extended) | Buys at 11:50 and 12:10 open two sessions; no merge across days; regroup splits at the boundary |
| `DayVerdictTest` | Seven complete days give the approved verdict; an incomplete day gives `PENDING` with the oldest incomplete day; a day with no buys counts zero signals; a backfilled older day changes a newer day's row on the next read |
| `AutomationBackfillServiceTest` | Validation errors per rule 35; overlap and active refusals; idempotent create and cancel; dry run writes no item and stores the summary and estimate; the scan stops at the range start; sessions close when the scan ends; budget stops capture with `BACKFILL_BUDGET`; discovery pauses within the reserve |
| `AutomationPipelineServiceTest` (extended) | Live items are taken before older backfill items |
| `AutomationRepositoryIntegrationTest` (extended) | Unique `request_id` and `active_wallet`; the new columns round-trip; day range queries use `first_buy_at` |
| `AutomationControllerTest` (extended) | Each new endpoint's success and error shapes |

Commands: `./gradlew compileKotlin compileTestKotlin`, the classes above, then `./gradlew build` (native PostgreSQL
provider when Docker is down; the 5 known Windows-only failures are reported as such).

## Documentation Sync

- README Automation section: modes, run now, trading days, scoring by day, backfill and its filters, new properties, the
  unused `automation_evaluations` table.
- ADR `20261102-automated-capture-and-sweep-evaluation.md`: a dated amendment for Key Decisions 1–8.
- `docs/schema/V20261103120000__automation_backfill.sql`.
- `docs/IMPLEMENTATION_STATUS.md` on delivery.

## Acceptance Criteria

1. With `poll-always=true`, a buy at 14:00 UTC+5 is discovered on the next discovery interval.
2. `POST /api/automation/discovery/run` at 14:00 in window mode returns `202` and the log shows one
   `automation discovery wallet=…` line within a minute; a second request before it runs adds no second scan.
3. Buys at 11:50 and 12:10 UTC+5 are in two sessions, listed under two trading days by `/days`.
4. `/evaluations?from=D&to=D` returns 10 rows for day D, either all with verdicts or all `PENDING` naming the oldest
   incomplete day; after a backfill completes an older day, the same request returns updated rows.
5. A dry-run backfill over 7 days creates no `automation_items` rows and stores buys per day, distinct mints, discovery
   requests and an estimate (or null).
6. A capturing backfill overlapping live coverage is refused with `409 BACKFILL_OVERLAPS_COVERAGE`; a second active one
   with `409 BACKFILL_ACTIVE`; the same `requestId` returns the first backfill.
7. A capturing backfill with a 2-mint token list creates items only for those mints, and its items are captured only
   while no live item is due.
8. A backfill whose budget runs out ends `STOPPED_BUDGET`, its unstarted items `FAILED`/`BACKFILL_BUDGET`, and its spend
   never exceeds the budget by more than one operation's recovery allowance.
9. Cancelling a scanning backfill leaves it `CANCELLED` with its open items `ABANDONED` and frees `active_wallet`.

## Open Questions

1. **Turn always-on on locally.** It is off by default in `application.yml`. *Recommendation:* you set
   `trenching.automation.poll-always=true` in your local config, or allow me to edit `application-local.yml` for it.
2. **First month order.** *Recommendation:* run a day or two in phase 0 (`capture-enabled=false`) with always-on to
   measure buys per day and discovery requests, then a 7-day dry-run backfill to get the capture estimate, then switch
   capture on. Check the totals against your Helius free-tier credits before turning capture on for the month.
3. **Old session evaluations.** Day scoring replaces them; the table stays but is no longer written. *Recommendation:*
   leave it and drop it by hand later, since automation has not run in production.
4. **Seven calendar trading days vs. seven days with buys.** A night Decu does not trade counts as a day with zero
   signals. *Recommendation:* keep calendar days, as it matches "last 7 nights"; the other reading would stretch the
   window over idle weeks.

## Amendments (2026-11-03, review decisions by the user)

The review found gaps the plan left open; the user decided each. The ADR's amendments 3 and 4 hold the detail.

- **Reserve (Key Decision 8, Rule 39).** The reserve also holds back backfill *captures*: a backfill step starts only if
  the 24-hour total + the operation's recovery allowance ≤ cap − reserve. It must be at least `recovery.requests` +
  `max-discovery-pages` × 22; the default is now 1,400. A capturing backfill's budget is at least `recovery.requests` +
  22 (1,222); a dry run keeps 100.
- **Phase-0 buys (Rule 33, Rule 40).** Once capture is on, `DISCOVERED` items of closed sessions are captured too, so phase-0
  days complete with real data. Due order: live items of open or closing sessions, then live items of closed sessions
  (the phase-0 backlog), then backfill items, each oldest buy first. The sweep is live-first too.
- **Coverage and the live floor (Key Decision 6, Rules 32, 35).** Coverage is the union of live coverage and every ended
  unfiltered capturing backfill (from `scanned_to`, the oldest point its scan reached). `covered_from` is the live floor:
  only a `DONE` unfiltered backfill moves it. A capturing backfill is clipped to the live floor and blocked only by an
  active backfill or a `DONE` unfiltered one, so a retry can re-read dates a stopped or cancelled backfill covered and
  take over its items that produced nothing.
- **Filtered backfills (Key Decision 9).** A backfill with a token list or a minimum above the capture filter captures its
  buys but never counts as coverage and blocks no one.
- **Budget-stopped days (Rule 33).** A day with an item `FAILED`/`BACKFILL_BUDGET` stays `PENDING` until a retry fills it.
- **Dry-run estimate (Rule 42).** Median `requests_used` × distinct passing mints that have no item yet (or whose item a
  backfill would take over), not × passing buys.
- **Cancel and manual close (Rule 41).** They also cancel a `STOPPED_BUDGET` backfill with waiting items and abandon
  non-terminal items in sessions that are already `CLOSED`.
- **Smaller fixes.** Old watermark rows get `covered_from` repaired in code; live sessions touched by a cross-source
  replacement are rebuilt; closing reads only closable sessions; pages read before a provider error are kept and charged;
  the scheduler pool is pinned to one thread; a replayed create returns the stored row even with automation off;
  `/sessions?sort=status` is accepted as an alias for the default order.

## Delivered

2026-11-03, backend units 1–4. Unit 5 (frontend controls and day views) is a separate session.

What shipped:
- `poll-always` mode and `POST /api/automation/discovery/run` (single-flight, on the scheduler's one thread);
- noon-to-noon UTC+5 trading days, sessions that never cross one, `/days`, `/days/{date}` and day-keyed `/evaluations`
  computed on read; stored session evaluations removed (table left in place);
- backfill jobs (`/backfills`: create, list, read, cancel) with dry run, token list, minimum signal size and request
  budget, their own scan cursor, live-first capture and the reserve;
- `docs/schema/V20261103120000__automation_backfill.sql`, README Automation section, ADR amendments 2–4.

Where it differs from the plan: the amendments above; also an extra index `idx_automation_sessions_backfill`, the
columns `scanned_to`, `skipped_no_block_time` and `filtered` on `automation_backfills`, and a backfill that starts its
listing before the wallet's earliest stored buy at or past the range end (no `walletactivity` change). Creating a
backfill while automation is off is refused with `409 AUTOMATION_DISABLED`.

Verification: `./gradlew build --continue` with the native PostgreSQL provider ran 1997 tests; the only 5 failures also
fail on a pristine HEAD (SharedTransactionCacheTest, TransactionCacheOptionTest, WalletActivityConfigurationTest,
WalletHistoryParallelCaptureTest ×2). Everything was proven with fixtures and fakes.

Not verified: a real day of always-on discovery or a real backfill against the wallet (user-run), and the production
SQL, applied by hand. The committed frontend still uses the old `/evaluations` shape until unit 5.
