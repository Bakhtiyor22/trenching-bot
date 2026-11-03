# Automation console UX

> An Automation section in the console for the nightly Decu capture and strategy sweep: status, a scoreboard that
> compares the 10 variations, a by-night status grid, nights, buys and sweep runs. Plus the small contract catch-up
> that `bf91a0a` needs on existing pages (minimum signal size, new outcomes, dataset-referenced runs).

Status: Implemented (2026-11-03). Approved with trend views A + B; the other recommendations stand. Frontend only; no backend change. Verification: `frontend/qa/20261103-automation/verification.md`.

Design options: the "Console capture, run and results redesign" canvas, row 4 "Automation: nightly sweep and
scoreboard" (https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4), artboards 8–11. The canvas is in the console's dark
palette. The values in the artboards are samples, not real results.

## Context

`bf91a0a` delivered backend units 1–6 of
[20261102-decu-capture-sweep-automation.md](archive/20261102-decu-capture-sweep-automation.md). Unit 7, the frontend
Automation page, is still open. The console has no automation views yet. It also does not know three contract changes
from the same commit:

| Change | What the console does today | Effect |
|---|---|---|
| `minSignalSol` on config, defaults and limits (engine `dip-paper-v6`, `agreed()` at 1.9 SOL) | Never sends or shows it | A run started from the form with the agreed preset applies **no** minimum signal size, so it silently differs from `agreed()` and from the automation's V00 |
| Outcomes `SIGNAL_BELOW_MIN_SIZE`, `SIGNAL_SIZE_UNAVAILABLE`; refusal `SIGNAL_SIZE_NEEDS_TRADES` | Shows the raw code, or a generic error | The user cannot tell why there was no entry |
| `RunSummary.storage` (`COPY` / `DATASET_REFERENCE`) and 409 `DATASET_IN_USE` on dataset delete | Ignores `storage`; the delete dialog shows the server message | No hint that a sweep run reads the catalog dataset, or why a dataset cannot be deleted |

Dashboard decisions already made by the user: three-state status, sorted Success › No success › No result, then by
worst-case P&L descending; filters for night/date, variation, outcome and capture health; no notifications. Time zone
UTC+5.

## Contract notes

- All endpoints exist only when `trenching.automation.enabled` and the console are on. Otherwise `/api/automation/*`
  returns 404. The page treats a 404 from `/status` as "Automation is off" and explains which properties turn it on.
  It never reads or shows any secret value.
- `/sweep-runs` filters by `sessionId`, `mint`, `label` and `outcome`. It has **no** date or health filter; health is
  a `/sessions` filter. A date filter on sweep runs works by choosing nights first (see Sweep runs tab). No backend
  change is proposed; say if you want `from`/`to` on `/sweep-runs` and I will hand it to a backend session.
- `/evaluations` has no `sessionId` filter. The scoreboard for one night uses `/sessions/{id}` (its `evaluations`);
  the by-night grid uses `/evaluations?planId=…&from=…&to=…&size=100` (page size is capped at 100, enough for 10
  variations × 10 nights per page; 14 or 30 nights read 2–3 pages).
- Money stays exact decimal strings in tables and labels, as elsewhere in the console. Only chart geometry converts to
  numbers.

## Proposed UI

### Navigation and routes

One new sidebar item, **Automation**, between Captures and API usage, with three URL-driven tabs:

| Route | View |
|---|---|
| `/automation` | Overview (default tab) |
| `/automation/nights` | Nights list |
| `/automation/nights/:id` | Night detail, with a selected buy (`?item=`) |
| `/automation/sweep-runs` | Sweep runs |
| `/automation/items/:id` | Redirects to its night with `?item=` (deep link from anywhere) |

"Night" is the user-facing word for a session. A night is labelled by the evening it started in UTC+5 (a buy at
01:30 belongs to the previous evening). Every automation time shows in the zone from `/status` with a "UTC+5" suffix,
not the browser's zone, because the polling window and sessions are defined there.

### 1 · Overview (artboard 8; phone width artboard 11)

1. **Status strip** (the console's KPI strip): Mode (Off / Discovery only / Capturing), polling window and whether
   it is in it now, last and next check, requests in the last 24 h against the cap as a meter, and tonight's open
   night with its buy counts. Alerts above it for `discoveryProblem`, `rpcConfigured=false`, and a discovery backlog
   (`watermark.resumeBefore`). Discovery-only mode gets an explanatory note: buys are counted, nothing is captured or
   scored. Refreshes every 30 s while the tab is visible, like API usage.
2. **One filter row** above everything it scopes: *Scored up to* (default: latest scored night), *Plan* (default: the
   active plan from `/status`), *Status* (All / Success / No success / No result).
3. **Scoreboard**, a ranked table, one row per variation in the backend's order (status rank, then worst-case P&L
   descending). Columns: variation label plus a plain description of what it changes ("take profit +35%"), status
   chip with icon and word (✓ Success, ✕ No success, – No result), a diverging bar of net P&L centred on 0 SOL (bar =
   worst case, tick = realized), exact worst-case and realized values, signals as a meter against the 10 needed, and
   unscored buys. V00, the default, is shaded as the baseline. A No result bar is drawn faded, so a high P&L from 7
   signals is not over-read. Row click opens Trend option B for that variation.
   - *Why a table with inline bars, not a separate bar chart:* the reader compares 10 rows on four measures; a bar
     chart would repeat the labels and split status from P&L. The diverging bar gives the sign at a glance and the
     exact numbers stay beside it.
4. **Status by night**, a grid: rows V00–V09, columns the last 14 nights (7 / 14 / 30 toggle), each cell the status of
   the 7-night window ending that night, with glyph and colour; tonight's open night is a dashed empty cell. Hover or
   focus shows worst-case P&L, signals and unscored; click opens that night. Row labels stay sticky when it scrolls
   sideways on a phone. A *Table view* button shows the same data as a plain table.
   - *Why a grid, not a 10-line chart:* the question is "which variation succeeds consistently". Ten overlapping lines
     need ten categorical colours (past the readable limit) and hide the status rule; a grid shows consistency as a
     run of ✓ across a row.
5. **Recent nights**: the last 5 nights with a stacked bar of buy states (replayed, in progress, failed or abandoned,
   not a first buy), best variation, capture health and requests. Link to the Nights tab.

**Trend option B** (artboard 9), recommended as a second view rather than a replacement: one variation against the
default. A line chart of the rolling worst-case P&L by night, the chosen variation in amber, V00 in grey, the other
eight as faint context lines, a labelled zero line, hollow points where signals < 10. A separate small bar chart below
shows signals per window against the 10 line (two charts, never a second y-axis). It opens from a scoreboard or grid
row and has its own variation picker.

*Your choice:* (A) grid only, (B) line chart only, or (A + B) grid on the overview and the line chart one click away.
I recommend **A + B**.

### 2 · Nights tab

A filter row: date range (presets Last 7 nights / Last 30 nights / All, plus From–To dates in UTC+5), Best status, Capture
health (Any / Issues / OK), Sort (Best result, the default, which is the backend's `status` sort / Newest first).
Filters live in the URL like the Captures page, so a filtered view can be bookmarked. A paginated table with the same
columns as Recent nights, plus first–last buy time and buys below the filter.

### 3 · Night detail (artboard 10)

- Header: night label, span, close reason, wallet (short, with copy), state chip and a capture-issues chip.
- **Close night now** button, only on an open night. It opens a confirmation: "Unfinished buys become Abandoned and are
  not scored." It sends `POST /sessions/{id}/close` with a request id kept per night in session storage (the pattern
  the capture pages use), so a retry is idempotent. Disabled with a reason otherwise.
- KPI strip: buys captured with the breakdown, below the filter, best variation, unscored, requests.
- **Buys** table, oldest first: buy time, token (copyable, with the existing market links), curve payment in SOL
  (fees excluded, labelled), an 8-step progress strip (session, wallet, market, rates, export, qualify, register,
  sweep; failed step marked), state chip, and a note: failure code/message, next attempt time, `BLOCKED_BUDGET`
  explained as "waiting for the 24 h request cap", "not a first buy" explained.
- **Selected buy** panel (from `?item=`; the first replayed buy by default): provenance (signature, slot, block time,
  dataset, capture, requests) with links to the capture, dataset and run pages, and its 10 sweep runs: variation,
  outcome in plain words, whether it counts as a signal, realized and worst-case P&L, run link. A
  *Compare* link opens the existing Compare page with up to 3 of its runs.
- This night's **scoreboard** (the same component as the overview, read from `evaluations`), or "Not scored yet:
  scoring starts when this night and every earlier night are closed".

### 4 · Sweep runs tab

Filters: Night (a picker over the nights list, filtered by a date range; this is how date filtering works here),
Variation (V00–V09), Outcome (grouped: exits, no-entry reasons, refusals; a "Not a signal / unscored" shortcut), token
mint. A paginated table: night, buy time, token, variation, outcome, signal, realized, worst case, run link. Outcome
codes get plain-language labels shared with the run pages.

### 5 · Existing pages: contract catch-up

These change behaviour, because the backend contract changed:

1. **New run form:** a *Minimum signal size (SOL, fees included)* field, with the backend limits and scale; the
   agreed preset fills 1.9; empty means no minimum. Runs then send `minSignalSol`. *Behaviour change, needed so a
   console "agreed" run matches `agreed()`.*
2. A `SIGNAL_SIZE_NEEDS_TRADES` refusal explains that this dataset does not record trades to size the signal, and
   offers *Run without a minimum signal size* (the same pattern as the existing sell-rule refusal).
3. Run detail and Compare: a *Minimum signal size* row in the settings; a "Reads the catalog dataset (automation
   sweep run)" note when `storage` is `DATASET_REFERENCE`.
4. Outcome labels and the run filters learn `SIGNAL_BELOW_MIN_SIZE` and `SIGNAL_SIZE_UNAVAILABLE`.
5. Dataset delete: a 409 `DATASET_IN_USE` explains that runs reference the dataset and links to those runs.

### Charts, colour and accessibility

- Charts use the existing ECharts wrapper (`components/Chart.tsx`) for Trend B; the scoreboard bars, buy-state bars and
  grid are plain HTML/CSS (no chart library needed, and they stay crisp in tables).
- Status colours are the console's gain/loss/neutral, always paired with an icon and a word, never colour alone.
  Buy-state colours: blue (replayed), amber (in progress), red (failed or abandoned), grey (not a first buy). Their
  dark-mode steps passed the dataviz validator's colour-blind and normal-vision separation checks. In light mode I
  will choose shades that pass the same checks, and each bar keeps its legend and counts in text.
- Light mode is supported (the console has a toggle). The current chart theme only styles dark mode, so the new
  charts set their own light and dark colours.
- Every chart has a table view or exact values beside it. Grid cells are links with `aria-label`s and reachable by
  keyboard. Tooltips add detail but are never the only way to read a value.
- Layout: the console's existing breakpoints. The KPI strips wrap; tables scroll inside their card; the scoreboard
  becomes stacked cards below the `sm` breakpoint (artboard 11); the grid scrolls sideways with sticky labels; filter
  rows wrap; touch targets are at least 44 px.

### States

Loading (the existing skeletons), Automation off (404 on `/status`), discovery only, no nights yet, a night not scored
yet, fewer than 7 closed nights ("The window fills up after 7 nights; until then every variation shows No result"),
backend unreachable, and errors with the server's message.

## Files

- New `src/api/automation.ts` (types, endpoints, query hooks) and `src/features/automation/` (pages, scoreboard, grid,
  trend, buy progress, pure helpers such as night labels, zone formatting, grid matrix, bar scale), routes, paths, nav
  item, MSW fixtures and handlers, with `?mockState=automation-off | automation-discovery | automation-empty`.
- Edited: `api/types.ts`, `features/runs/{runForm,NewRunPage,configLabels,RunDetailPage,watchEnd}`,
  `features/compare/compareRuns.ts`, `lib/codes.ts`, `features/datasets/*` (delete message), `components/AppLayout.tsx`,
  `routes.tsx`, `paths.ts`, the mocks.
- No new dependencies. No `.env` access. Nothing contacts Solana or a provider.

## How I will check it

1. Unit tests for the helpers (night label across midnight in UTC+5, zone formatting, diverging bar scale, grid matrix
   with missing nights, buy-state buckets, outcome labels), and page tests with MSW for every state above, the filters
   writing the URL, the default sort, the close flow (confirm, request id reuse) and the run form sending
   `minSignalSol`.
2. `npm test`, `npm run lint` (zero warnings), `npm run build` in `frontend/`.
3. In the browser pane with mocks (`VITE_USE_MOCKS=true`): Overview, Nights, Night detail and Sweep runs, plus the
   updated New run and Run detail pages, at 1440 px and 375 px, in dark and light. I will check label collisions,
   overflow, keyboard focus on grid cells, and that each filter changes the request. Screenshots and a short
   `verification.md` go in `frontend/qa/20261103-automation/`, like the earlier QA folders.
4. If you configure automation locally, a check against the real backend. Otherwise mocks only, and I will say so.

## Decisions for you

1. Trend view: A, B or **A + B** (recommended).
2. Call sessions "nights" in the UI (recommended), or keep "sessions".
3. Show automation times in UTC+5 from `/status` (recommended) rather than the browser's zone.
4. Accept the contract catch-up on existing pages (section 5), including the run form sending `minSignalSol`.
5. Sweep runs date filter through the night picker (no backend change), or ask a backend session for `from`/`to` on
   `/sweep-runs`.
