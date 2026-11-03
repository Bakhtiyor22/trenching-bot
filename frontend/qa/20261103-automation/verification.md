# Automation console: verification (2026-11-03)

Plan: [docs/plans/20261103-automation-console-ux.md](../../../docs/plans/20261103-automation-console-ux.md),
approved with trend views A + B.

## Commands (from `frontend/`)

| Command | Result |
|---|---|
| `npm test` | 59 files, 456 tests passed (28 new automation tests, 4 new run/dataset tests, 3 updated) |
| `npm run lint` | 0 errors, 0 warnings |
| `npm run build` | `tsc -b` and Vite build succeeded |

## Browser (built-in pane, `frontend-mocks` on port 5174, `VITE_USE_MOCKS=true`)

All data is the deterministic mock in `src/mocks/automationFixtures.ts`; no backend, provider or Solana call.

| Screenshot | What it checks |
|---|---|
| `overview-scoreboard-1440-dark.jpg` | Status strip, filter row, scoreboard order and diverging P&L bars |
| `overview-grid-1440-dark.jpg` | Status by night: 14 nights, open night as an empty dashed cell, selected row |
| `overview-trend-1440-dark.jpg` | Trend option B: emphasis line, 0 SOL line, separate signals chart with the 10 line |
| `overview-scoreboard-1440-light.jpg`, `overview-grid-trend-1440-light.jpg` | Light scheme |
| `nights-1440-dark.jpg` | Nights list, filters, buy-state bars, capture-health badge |
| `night-1440-dark.jpg`, `night-sweep-runs-1440-dark.jpg` | Night detail: buys with step strips, failed buy note, selected buy's 10 sweep runs, night scoreboard |
| `sweep-runs-1440-dark.jpg` | Sweep runs filtered by variation (`?variation=V04`) |
| `new-run-min-signal-1440-dark.jpg` | New run: minimum signal size prefilled with the agreed 1.9 SOL |
| `overview-*-375-dark.jpg`, `night-375-dark.jpg` | Phone width: scoreboard as cards, wrapping status filter, grid opening on the newest night, no horizontal page scroll (`scrollWidth` 375) |

Also checked: `?mockState=automation-off` shows "Automation is off" with the two property names (text read with
`get_page_text`; that screenshot was garbled by the hidden pane, so it is not kept). No console errors.

## Not covered

- Not run against a real backend: automation is off by default locally, so every automation view used mocks.
- The automation-off and discovery-only states are covered by tests; only the off state was also opened in the
  browser.
