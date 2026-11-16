# Phase 1 frontend: verification (2026-11-16)

Plan: `docs/plans/20261116-automation-trace-stop-console-restructure.md`, Phase 1 units 2, 4, 5 and 6, built against
"Phase 1 HTTP contract (pinned 2026-11-16)". Checked against MSW mocks only (`frontend-mocks` launch config, port 5174);
no real backend or database was used.

Screenshots are taken from the browser pane, so the 1440 px ones are scaled to 800 px wide.

## Unit 2: Runs page on the server contract

| Check | Result | Screenshot |
|---|---|---|
| First page pins `asOf` in the URL; status counts come from `statusCounts` (All 24 · Profit 9 · Loss 1 · Flat 5 · No entry 4 · Open 5) | OK | `runs-1440.jpg` |
| Loss finds the one LOSS run, which is on page 2 of the unfiltered list | OK | `runs-loss-filter-1440.jpg` |
| Token, trading day and status together match nothing: the empty state names each filter and offers "Clear filters"; the request carried `anchorFrom=2026-11-01T07:00:00Z`, `anchorTo=2026-11-02T07:00:00Z` | OK | `runs-empty-filters-1440.jpg` |
| `?mockState=runs-arriving`: after the 30 s poll, "1 new run since this list was loaded" with Show; Show drops `asOf` and the list re-pins at 26 runs | OK | `runs-new-runs-notice-1440.jpg` |
| Dataset combobox searches the server (`GET /api/datasets?q=edge`) | OK | `runs-dataset-search-1440.jpg` |
| Rows show `datasetName`, token and the buy time in UTC+5 with "From buy · 2 Nov" to the day page | OK | `runs-1440.jpg` |
| 375 px: no page-level horizontal scroll (scrollWidth = clientWidth = 375), status control scrolls in its own row, table scrolls in its card | OK | `runs-filters-375.jpg`, `runs-table-375.jpg` |
| Console errors | none | |

Regression test: `RunsPage.test.tsx` › "finds a loss that is only on page 2…".

## Unit 4: Trace timeline on the selected buy

| Check | Result | Screenshot |
|---|---|---|
| Replayed buy: events in `seq` order, UTC+5 time, `+gap`, step, attempt, requests, "took" per operation, sweep entries with run links | OK | `day-selected-buy-1440.jpg` |
| Capture details expand from the capture API (state, step, run time, requests and bytes against budget, records) with a link to the capture | OK | `day-trace-operation-1440.jpg` |
| Failed buy: both failed market attempts with the error text and code, then Capturing → Failed | OK | `day-trace-failed-1440.jpg` |
| Buy found before history: "History recorded from 25 Oct, 12:00 UTC+5; earlier steps were not recorded." | OK | `day-trace-history-started-1440.jpg` |
| 375 px: no horizontal page scroll | OK | `day-trace-375.jpg` |

Unknown event kinds and the empty/404 states are covered by `TraceTimeline.test.tsx`.

## Unit 5: Pending wording

| Check | Result | Screenshot |
|---|---|---|
| Days, `?mockState=automation-phase0`, 20 Oct–2 Nov, Best status = Success: "14 days pending: discovery only, so no buy is captured or replayed", with "Show pending days" | OK | `days-all-pending-1440.jpg` |
| Days, capture on, 20–22 Oct, Success: "3 days pending: waiting for earlier days of their 7-day window" | OK (page text) | |
| Overview scoreboard status filter | Covered by `AutomationPages.test.tsx`. In the browser the mock clock runs past 2 Nov, where mock days read "No result", so not every day is pending there. | |

## Unit 6: Small fixes

| Check | Result | Screenshot |
|---|---|---|
| Palette: a pasted mint lists "Automation buy of CSUQ…pump" and "Runs of CSUQ…pump · 10 runs"; the buy opens `/automation/days/2026-11-02?item=…` with that buy selected | OK | `palette-mint-search-1440.jpg` |
| Palette entry reads "Automation: sweep scoreboard" (no "nightly") | OK (test) | |
| `CLOSING` session shows "Closing: waits for 2 buys" and no "Close session now" | OK | `day-closing-session-next-try-1440.jpg` |
| Waiting buy from 8 Oct: "next try overdue since 8 Oct, 23:55" | OK | same |

## Notes

- About 40 s after one page load, the browser pane's MSW service worker stopped intercepting requests. Requests then went to
  Vite's proxy and got connection-refused, so no request reached a real backend. Reloading the page restored the mocks.
  This came from the environment, not the app.
- Command gate: `npm run lint`, `npm test`, `npm run build`, `npm run format:check`; results are in the session report.
