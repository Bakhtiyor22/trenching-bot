# Console redesign "quiet ledger" — verification (2026-11-03)

Plan: [docs/plans/20261103-frontend-redesign.md](../../../docs/plans/20261103-frontend-redesign.md), approved with
direction A and every recommended decision. Design: canvas artboards 13–18
(https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4). Frontend only; no backend, provider or Solana call.

## Commands (from `frontend/`)

| Command | Result |
|---|---|
| `npm test` | 68 files, 534 tests passed (was 60 files, 483 tests before the redesign) |
| `npm run lint` | 0 errors, 0 warnings |
| `npm run build` | `tsc -b` and Vite build succeeded |

New tests cover: `formatSignedDecimal`, `QueryView` (loader, error only without data, data kept with a refresh
notice), a failed background refresh keeping the page on Dashboard, Runs, Run detail, New run (edits kept), Compare
(per-card error), Captures list, Capture detail (half-typed rename kept), Datasets, Automation overview and an open
Close-session dialog, Backfill detail and its open cancel dialog, API usage and Solana; URL state for runs filters,
run cycle and records tab, datasets page and pair, capture detail tab, backfills page, automation default day and
buy; URL validation (`2026-02-31`, `page=abc`, bogus `status`, invalid mint, invalid backfill pre-fill); dialogs
locked while sending and reset on reopen; "Not scanned yet"; API usage chart buckets; Solana and record labels.

## Browser (built-in pane, `frontend-mocks`, MSW data)

Every route was loaded at 1440 px dark. At 375 px all 20 routes were measured for sideways scroll
(`documentElement.scrollWidth` ≤ 375 on every one: dashboard, runs, run detail, run chart, new run, compare,
captures, new capture, capture detail, datasets, dataset detail, automation overview, days, day, backfills, backfill
detail, sweep runs, API usage, Solana, not found). Light scheme checked at 1440 px.

| Screenshot | What it shows |
|---|---|
| `dashboard-1440-dark.jpg`, `dashboard-375-dark.jpg`, `dashboard-1440-light.jpg` | Health line in words, KPI strip with the signed hero P&L (trimmed, exact on hover), cumulative chart red below zero with end label, recent runs, one primary button |
| `runs-1440-dark.jpg` | Runs list in a Section, signed P&L, sticky Run column |
| `run-detail-1440-dark.jpg`, `run-detail-375-dark.jpg`, `run-detail-1440-light.jpg` | Header with breadcrumbs, meta and actions; hero P&L; cycle list with outcome chips; blue paper-fill diamonds |
| `run-chart-1440-dark.jpg` | Full chart inside the shell with the icon rail; KPI strip instead of the fact grid |
| `new-run-1440-dark.jpg`, `new-run-375-dark.jpg` | Form; on a phone, Start run in a sticky bottom bar |
| `compare-1440-dark.jpg` | A/B/C identity in blue and orange beside the letter; verdict as a KPI strip; "differs" rows |
| `captures-1440-dark.jpg`, `captures-375-dark.jpg`, `captures-1440-light.jpg` | Status filter as a segmented control, status chips, secondary row actions, one primary |
| `capture-detail-1440-dark.jpg` | One header with status chip and actions; the "Now" card as the page's hero |
| `new-capture-1440-dark.jpg` | Breadcrumbs, quiet suggestion notes and secondary suggestion buttons |
| `datasets-1440-dark.jpg`, `dataset-detail-1440-dark.jpg` | Datasets list; detail with one breadcrumb line, capture link in the meta, KPI strip |
| `automation-overview-1440-dark.jpg`, `automation-overview-375-dark.jpg`, `automation-overview-1440-light.jpg` | One header with mode chip, UTC+5 note, Run discovery now and underline tabs; status strip (two columns on a phone); scoreboard |
| `automation-grid-trend-1440-dark.jpg` | Status by day grid and the trend chart from the chart tokens |
| `automation-days-1440-dark.jpg`, `automation-day-1440-dark.jpg`, `automation-day-375-dark.jpg` | Days list; day detail with breadcrumbs and prev/next, no second header |
| `automation-backfills-1440-dark.jpg`, `automation-backfill-detail-1440-dark.jpg`, `automation-sweep-runs-1440-dark.jpg` | Backfill form and list, detail with meters and the dry-run chart, sweep runs |
| `usage-1440-dark.jpg`, `usage-375-dark.jpg` | Full bucket range on the x-axis, provider colours from the tokens |
| `solana-1440-dark.jpg`, `not-found-1440-dark.jpg` | Status chips and cluster words; not-found with next steps |

Fixed during the check:
- The API usage bars never appeared: the page rebuilt the option on every render, restarting the grow-in
  animation. The option is memoised and `<Chart>` now defaults to `animation: false`.
- Dataset detail showed two breadcrumb lines.
- Destructive buttons were filled red; they are red outline now.
- Suggestion Apply/Undo buttons were amber; they are secondary.
- Word values such as "Always on" were set in mono; `Kpi text` sets them in the sans.
- A zero meter drew a 2 px stub.

## Not covered

- Not run against a real backend; automation is off locally. Everything above uses the MSW mocks.
- Screen-reader pass not done beyond the role/label assertions in the tests.
