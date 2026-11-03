# Automation console: trading days, run now and backfill

> Bring the Automation pages in line with backend commit `555f046`: fix what the new contract broke, group results by
> trading day, and add a "Run now" button and a backfill form with a dry-run estimate and progress.

Status: Implemented (2026-11-03). Approved as proposed. Frontend only; no backend change. Verification: `frontend/qa/20261103-automation-days/verification.md`.

Backend plan: [20261103-always-on-automation-trading-days-backfill.md](archive/20261103-always-on-automation-trading-days-backfill.md),
unit 5, including its Amendments and Delivered sections. It builds on the pages from
[20261103-automation-console-ux.md](20261103-automation-console-ux.md) (commit `ff3560b`). Design:
the same canvas, row 4, plus a new artboard 12 for backfills.

## What is broken today

| Page | Cause | What the user sees |
|---|---|---|
| Overview | `/evaluations` now takes ISO dates for `from`/`to`; the page sends an instant, so the backend returns 400. Rows have `day`/`windowDays` instead of `sessionId`/`windowSessionIds`. | An error instead of the scoreboard, grid and trend |
| Night detail | `/sessions/{id}` no longer returns `evaluations`; the page reads `evaluations[0]` | The page crashes |
| Nights, Recent nights | Sessions lost `bestStatus`/`bestLabel`; the `status` filter is gone | Every night shows "Not scored yet", and the Best status filter does nothing |
| Sweep runs | Works | — |

## Contract used (from `api/dto/AutomationResponses.kt`)

- `/status` adds `mode` (`ALWAYS`/`WINDOW`), `manualRunQueued`, `tradingDayStart`, `coveredFrom`, `activeBackfillId`.
- `POST /discovery/run` returns 202 `{queued, alreadyQueued}`, or 409 `AUTOMATION_DISABLED`.
- `GET /days?from&to` (ISO dates, at most 62 days, newest first; default the last 14) returns per-day summaries:
  `covered`, `complete`, sessions, buys, below filter, captured, simulated, failed, abandoned, requests,
  `bestStatus`/`bestLabel`/`pendingDay`.
- `GET /days/{date}` returns the summary, coverage ranges, sessions, items and that day's rows for each configuration.
- `GET /evaluations?from&to&label&status&sort=status|day,desc` returns day-keyed rows. Status is SUCCESS, NO_SUCCESS,
  NO_RESULT or PENDING, with `pendingDay`.
- Sessions lose their evaluation fields and gain `tradingDay` and `backfillId`; `sort=status` is now only an alias.
- `/backfills`: create, list, read and cancel, with the states and 409 codes in the backend plan.

## Proposed UI

### Words and structure

- **Day**, not night. A trading day runs from 12:00 to 12:00 UTC+5 and is named by its start date ("Mon 2 Nov"). The
  noon rollover already used for "nights" matches it, so labels stay the same. Each day page shows "12:00 → 12:00
  UTC+5". Idle-gap sessions become "sessions" inside a day (usually one per day). A backfill's sessions carry a
  "Backfill" tag.
- **Tabs:** Overview · Days · Backfills · Sweep runs. Routes:
  - `/automation/days` and `/automation/days/:date`;
  - `/automation/backfills` and `/automation/backfills/:id`;
  - the old `/automation/nights/:id` and `/automation/items/:id` links redirect to the day of that session or buy.
- **PENDING** gets its own chip: "◷ Pending", grey with a clock glyph. Its tooltip names the day it is waiting for
  ("waiting for Sat 31 Oct to complete"). It is a state, not a verdict, so it sorts last, as in the backend.

### 1 · Overview

1. **Status strip:**
   - **Mode**: Always on, or Window 18:00–05:00.
   - **Last check / next**, with a **Run now** button. While `manualRunQueued` is set, the button reads "Queued…" and
     `/status` refreshes every 5 s instead of every 30 s. When automation is off, a 409 shows "Automation is off" and
     the button is disabled.
   - **Requests 24 h** meter, as now.
   - **Coverage**: "Covered since 20 Oct, 12:00".
   - **Today**: buys on the current trading day.
   - **Backfill**: if one is active, its state and progress, linking to it.
2. **Scoreboard**: "Scored day", defaulting to the newest day with a verdict, not a pending day. Rows come from
   `/days/{date}`. Pending rows keep their counts, faded, with the Pending chip. If every recent day is pending (phase 0,
   or a backfill still running), a short note explains why: no day completes until capture is on and every buy has
   finished. The status filter gains "Pending".
3. **Status by day** grid (option A): `/evaluations?from&to&sort=day,desc`, 7/14/30 days, read in pages of 100. Days
   with no buys are real columns, because calendar days count. Pending cells show a dotted outline and "◷".
4. **Trend** (option B) plots days. A pending day is shown as a hollow, faded point, and the signals chart is
   unchanged.
5. **Recent days** replaces Recent nights: the last 5 days from `/days`, with a buy-state bar built from the day
   counts (simulated, in progress, failed or abandoned), best verdict and requests.

### 2 · Days tab (replaces Nights)

- Filters: range presets (Last 7 / 14 / 30 / 62 days) or a **From–To date range**, which is the date filter you asked
  for. The span is capped at 62 days, as the API allows, with a message when it is exceeded.
- Also Best status (Success / No success / No result / Pending), and Capture health (Any / With issues / Incomplete).
- The API returns the whole span in one response of at most 62 rows. Status and health therefore filter on the page,
  with no extra requests. Filters stay in the URL.
- Columns:
  - the day, with "covered" or "not fully covered";
  - complete or not;
  - sessions;
  - buys, as a state bar;
  - below filter;
  - best variation, or Pending with the day it waits for;
  - requests.

### 3 · Day detail (replaces Night detail)

- **KPI strip**: buys, below filter, best variation (or pending), unscored, requests.
- **Coverage line**: "Fully covered", or the covered time ranges, plus "not complete: 2 buys still capturing" when
  that applies.
- **Sessions**: one row per session, with live or backfill, span, state, and **Close session now** for an open one.
  This keeps the existing confirm dialog and request-id handling.
- **Buys this day** and the selected buy's 10 sweep runs, both unchanged.
- **This day's scoreboard**: `evaluations` from the day detail, with Pending handled.

### 4 · Backfills tab (new; artboard 12)

- **New backfill form**:
  - **From day** and **To day**, as date inputs. Both must be finished days, at most 31 days apart.
  - **Dry run**, on by default, so the first step is always an estimate.
  - **Request budget**. The default is 500 for a dry run and 1,222 for a capture; those are also the minimums.
  - **Minimum signal size**, optional, at or above 1.5 SOL.
  - **Tokens**, optional, up to 50 mints, one per line, each checked as base58.
- A live note says whether the backfill will be **filtered**: a token list, or a minimum above 1.5 SOL, means "captures
  these buys but does not count toward day verdicts". The note appears before submitting.
- 400 field errors appear on their fields. The 409 codes get plain explanations:
  - `BACKFILL_ACTIVE`: one is already running, with a link to it;
  - `BACKFILL_OVERLAPS_COVERAGE`: the range overlaps what is already covered, so end it before the covered date;
  - `CAPTURE_DISABLED`: capture is off, so only a dry run is possible;
  - `AUTOMATION_DISABLED`.
- The request id is kept in session storage until the call succeeds, so a retry cannot create a second backfill.
- **Backfill list**: newest first. Each row shows the date range, dry run or capture, filtered tag, a state chip, scan
  progress and budget spent.
- **Backfill detail**:
  - **State chip**: Queued, Scanning, Capturing, Done, Stopped (budget) or Cancelled.
  - **Scan progress bar**: the scan reads newest first, from the range end back to `scannedTo`, so the bar shows
    "read back to 24 Oct, 12:00".
  - **Budget meter**: requests spent against the budget, split into discovery and items.
  - **Counts**: actions scanned, buys found, buys passing the filter, items created, skipped by the token list, and
    without a block time.
  - **Items**: a buy-state bar.
  - **Dry-run result**: a bar chart of buys per day (one hue, with values in a table view), distinct mints and the
    capture estimate. A **Capture this range** button pre-fills the form with dry run off and a budget of the estimate,
    rounded up to at least 1,222.
  - **Cancel**, with a confirmation: items not yet finished become Abandoned.
  - While the backfill is active, the detail refreshes every 15 s.

### 5 · Sweep runs

No API change. The night picker becomes a **session** picker, labelled by day, start time and live or backfill.

## Behaviour changes, and why

- Nights become days, and session-level scoring is gone, because the backend no longer scores sessions.
- The Best status filter on the session list goes away; the Days tab replaces it.
- Run now and backfill create are the first actions on this page that start provider work. Run now starts discovery
  only. A capturing backfill spends real requests within its budget, so the form defaults to a dry run and the capture
  button reads "Start capture backfill (spends up to N requests)".

## Files

- **Edited**:
  - `api/automation.ts`: types, the new endpoints, hooks;
  - `features/automation/*`: overview, scoreboard, grid, trend and model;
  - the Night pages become Day pages, plus sweep runs and the redirect;
  - `paths.ts`, `routes.tsx`;
  - the mocks (`automationFixtures.ts`, `automationHandlers.ts`): day-keyed evaluations, days and backfills, plus a
    `?mockState=automation-phase0` state where every day is pending.
- **New**: `BackfillsPage.tsx`, `BackfillDetailPage.tsx`, `BackfillForm.tsx`, `DayPage.tsx`, `DaysPage.tsx`, and tests.
- No new dependencies. Dates use the native date input, because `@mantine/dates` is not installed.

## How I will check it

1. Unit tests for:
   - the day helpers (the noon boundary, a 62-day cap);
   - Pending sorting and labels;
   - backfill progress (scan fraction, filtered detection, budget defaults and minimums);
   - mint-list parsing.
2. Page tests with MSW:
   - Run now: 202, already queued, and 409;
   - the day list's filters and date range;
   - day detail with pending rows, and closing a session;
   - backfill create: dry run, then capture from the estimate; each 409 code; field errors; idempotent retry;
   - backfill progress and cancel;
   - the old night and item links redirecting.
3. `npm test`, `npm run lint` and `npm run build`.
4. The browser pane with mocks, at 1440 px and 375 px, in dark and light. Screenshots go to
   `frontend/qa/20261103-automation-days/`.
5. A check against the real local backend only if you start it with automation on. Otherwise mocks only, and I will say
   so.

## Decisions for you

1. Rename Nights to **Days**, with the old links redirecting (recommended).
2. Default the backfill form to a **dry run** (recommended).
3. Filter the Days tab by status and health **on the page**, since the API returns at most 62 rows (recommended), or
   ask the backend for server-side filters.
