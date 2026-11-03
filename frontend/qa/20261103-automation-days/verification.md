# Automation: trading days, run now and backfill — verification (2026-11-03)

Plan: [docs/plans/20261103-automation-days-backfill-ui.md](../../../docs/plans/20261103-automation-days-backfill-ui.md),
approved as proposed. Backend contract: commit `555f046`.

## Commands (from `frontend/`)

| Command | Result |
|---|---|
| `npm test` | 59 files, 475 tests passed (automation: 22 model tests, 25 page tests) |
| `npm run lint` | 0 errors, 0 warnings |
| `npm run build` | `tsc -b` and Vite build succeeded |

## Browser (built-in pane, `frontend-mocks` on port 5174)

All data is the deterministic mock in `src/mocks/automationFixtures.ts`, scored with the backend's day rules
(7-day window, PENDING while a day of it is incomplete). No backend, provider or Solana call.

| Screenshot | What it checks |
|---|---|
| `overview-1440-dark.jpg` | Status strip: mode (always on), Run now, coverage, today, the running backfill; scoreboard of the newest day with a verdict |
| `overview-grid-trend-1440-dark.jpg` | Status by day: ◷ pending cells for days whose window reaches before live coverage and for the running days; trend with hollow pending points |
| `days-1440-dark.jpg` | Days list: coverage, completeness, buys, best variation or Pending with the day it waits for |
| `day-2-nov-1440-dark.jpg` | Day detail: coverage line with why it is not complete, sessions with Close session now, buys |
| `backfills-active-refusal-1440-dark.jpg` | Backfill form (dry run by default) and the BACKFILL_ACTIVE refusal linking to the running backfill; backfill list |
| `backfill-dry-run-1440-dark.jpg` | Dry-run result: counts, estimate, buys per day chart, Capture this range |
| `overview-strip-375-dark.jpg`, `backfill-form-375-dark.jpg`, `day-375-dark.jpg` | Phone width; `scrollWidth` 375 on every page checked (no sideways page scroll) |
| `overview-phase0-1440-light.jpg`, `grid-phase0-1440-light.jpg` | Light scheme, `?mockState=automation-phase0`: discovery-only and every-day-pending explanations |

Fixed during the check: the backfill cell in the status strip truncated its chip; the Pending chip truncated in a
narrow KPI cell; today's row read "Not fully covered" instead of "In progress"; the budget input overflowed the card
at 375 px; the budget description was set in monospace.

## Not covered

- Not run against a real backend: automation is off by default locally. Run now, backfill create and cancel are
  covered by tests against mocks that follow the backend's validation and 409 codes.
- A capture backfill's full lifecycle (scanning → capturing → done) is only simulated by the mock.
