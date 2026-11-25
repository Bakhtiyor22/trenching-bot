# Run totals, exit reasons, and status tabs on variation runs

> Both run lists show the total realized P&L of everything their filters match. The Runs list says *why* a run sold, did not buy or still holds. The variation runs table gets the Runs list's status tabs with counts.

Status: Delivered 2026-11-25 (approved as written; the open question answered as recommended: rows with no position show "—" in the variation table's Realized and Worst case)

## Context

This follows the delivered [runs list plan](20261124-runs-list-clarity.md). After it went live, the user asked for three things on 2026-11-25.

1. **A total profit and loss.** Neither list shows one today.
   - The Runs list (`frontend/src/features/runs/RunsPage.tsx`, `GET /api/runs`) pages through runs and counts them per status. It never sums them.
   - The variation runs table (`frontend/src/features/automation/SweepRunsTable.tsx`, `SweepRunsView`, `GET /api/automation/sweep-runs`) only pages.
   - The user chose: **both pages, summed over every match** (all pages, not only the one shown), in SOL with its USDT value now. The variation table also gets the worst-case total.
2. **"Sold" is ambiguous.** The Trade column says Sold for any closed position. The engine closes a position for one of four reasons, `CloseReason` in `portfolio/domain/Position.kt`:
   - `TAKE_PROFIT`: the price reached the take-profit level.
   - `STOP_LOSS`: the price reached the stop-loss level.
   - `TIME_EXIT`: a time limit, the maximum hold or the hard exit, ran out.
   - `WALLET_SOLD`: the tracked wallet sold while we held, when the run follows the wallet's sells.

   Whether the sale made money is only the P&L's sign. The variation runs table already names these reasons in its "What happened" column ("Take profit", "Stop loss", "Time exit", "Decu sold", "No dip · no entry", …, `OUTCOME_SHORT` in `automationModel.ts`). The Runs list cannot, because a run summary carries only counts.
3. **The same filters on variation runs.** The user chose to **add the Runs list's status tabs with counts** (All, Profit, Loss, Flat, Not bought, Still held) and keep From day, To day, Outcome and Token.

What is stored already. No new data needs to be captured:
- `simulation_runs` holds `quote_mint`, `filled_count`, `open_count` and `realized_pnl` (numeric 38,12).
  - The Runs list status is the SQL `case` in `replay/infrastructure/persistence/RunSearchSql.kt`, held to `RunStatus.of` by a test.
  - `statusCounts()` already aggregates per status under every filter but the status.
- **Exit reasons are stored per run.**
  - `positions` (portfolio) holds `run_id`, `state`, `close_reason` and `exit_unavailable` (why a position stayed open), indexed by `(run_id, target_slot, id)`.
  - `paper_orders` (papertrading) holds `run_id`, `status` and `rejection_reason`, indexed by `(run_id, target_slot, id)`.
  - Both have one implementation behind a public port: `PositionStore` (`PositionStoreService`) and `PaperOrderStore` (`PaperOrderStoreService`). `ConsoleService` already reads both.
- **Sweep rows.** `automation_sweep_runs` holds `position_state`, `outcome`, `realized_pnl` and `worst_case_pnl` (scale 12), and is indexed by `(label, plan_id)`. Its list query is `AutomationSweepRunRepository.page` over `SWEEP_RUN_FILTER`, and the response is `AutomationSweepRunPageResponse(items, page, size, total)`.

## Scope

### In scope

- **Runs list total.** The sum of realized P&L, run count and quote mint over every run the filters match, including the status tab. The frontend shows it in SOL with its USDT value now.
- **Exit reasons on run summaries.** Each run summary carries `outcomes`: what happened to its tokens, by reason, counted. The Trade column shows the reason next to the chip ("Sold · Take profit", "Not bought · No dip").
- **Status tabs and totals on sweep runs.** `GET /api/automation/sweep-runs` gains a `status` filter, `statusCounts` and `totals` (rows, realized and worst case).
  - The variation runs table gets the status tabs and a totals line.
  - So does the all-sweep-runs page, which shares `SweepRunsView`.

### Out of scope

- An outcome filter on the Runs list. The reasons are shown, not filtered.
- Totals in the run page or the compare page.
- Changing the Runs list's filters. The variation runs table gains tabs; the Runs list keeps its bar.
- USDT at each trade's own rate in a total: the same reason as in the [runs list plan](20261124-runs-list-clarity.md), Key Decision 4.

## Key Decisions

1. **Totals come from the queries that already count statuses.**
   - Grouping the existing status-count aggregate also by quote mint, and adding `sum(realized_pnl)` (and, for sweep rows, `sum(worst_case_pnl)`), gives the counts and the sums per status in one query.
   - The total for the selected tab is the sum over the selected statuses, or all of them, in code.
   - No extra query, and the totals can never disagree with the tab counts.
   - Rejected: a separate `sum` query. It costs one more scan and can drift from the counts.
2. **A total covers everything the filters match, the status tab included, on the page's snapshot** (`asOf` on the Runs list).
   - It is *realized* P&L, so a still-held position adds nothing. The label says so.
   - The sweep table adds the worst-case total, where a still-held position counts at minus its entry cost (as in `SweepEntryResult`).
3. **Totals are per quote mint on the Runs list.** A hand-started run may be quoted in another mint, and SOL and USDC must never be added together. The sweep table is always SOL, because variations refuse any other quote.
4. **Exit reasons are read per page from the stored positions and orders, not stored again on the run.**
   - One grouped, indexed query on each store per page (at most 100 runs). No schema change and no backfill. It covers hand-started runs as well as sweep runs.
   - Rejected: a new `simulation_runs` column. It needs a startup backfill of every stored run from its records, and is only worth it once the Runs list filters by outcome.
   - Rejected: the sweep rows' `outcome`. It covers sweep runs only, so a hand-started run would still say just "Sold".
5. **One vocabulary for reasons.** The Runs list uses the variation table's existing words (`OUTCOME_SHORT`). Under a "Not bought" chip it drops their trailing " · no entry", so the two never say the same thing twice.
6. **A sweep row's status is the Runs list's status.**
   - No position → `NO_ENTRY`. This includes rejections, a buy too small, no cycle and refusals.
   - Position open → `OPEN`.
   - Closed → `PROFIT`, `LOSS` or `FLAT` by the sign of `realized_pnl`.

   This is `RunStatus.of` for the one-token run the row points at. It reuses replay's public `RunStatus`, which `console.automation` may import.
7. **The status tabs are one component shared by both pages.** The Runs list's `StatusFilterControl` moves out of `RunsPage.tsx`, so the tabs read and count the same on both.

## Module Contracts

Every change is **additive**: no field is removed or renamed, and no contract breaks.

**replay** (public types, consumed by console)
- `replay.domain.RunQuoteTotal(quoteMint: String, runs: Long, realizedPnl: BigDecimal)`.
- `RunSearchResult` gains `totals: List<RunQuoteTotal>`.
  - The statuses come from the query, or all of them when it names none; the query's other filters apply too.
  - It is ordered by quote mint, and an empty result gives an empty list.

**papertrading** (public port `PaperOrderStore`, adapter `PaperOrderStoreService`)
- `fun rejectionsByRun(runIds: Collection<String>): Map<String, Map<RejectionReason, Int>>`: rejected orders per run and reason. A run with none is absent.

**portfolio** (public port `PositionStore`, adapter `PositionStoreService`)
- `fun outcomesByRun(runIds: Collection<String>): Map<String, PositionOutcomeCounts>`.
  - `portfolio.domain.PositionOutcomeCounts(closed: Map<CloseReason, Int>, held: Map<RejectionReason, Int>)`.
  - `closed` counts closed positions by `close_reason`; `held` counts open positions by `exit_unavailable`.

**console**
- `console.domain.RunOutcome(code: String, stage: RunOutcomeStage, count: Int)` and `enum RunOutcomeStage { SOLD, HELD, NOT_BOUGHT }`.
- `console.application.RunOutcomes`. `fun of(runIds): Map<String, List<RunOutcome>>` makes one call to each store and no call for an empty list. Every id gets an entry.
- It is used by the same three places as `RunVariationLabels`: list, detail and start. The labels and the outcomes travel together in the console carriers.
- API:
  - `RunSummaryResponse.outcomes: List<RunOutcomeResponse(code, stage, count)>`, never null.
  - `RunPageResponse.totals: List<RunTotalResponse(quoteMint, runs, realizedPnl)>`.

**console.automation**
- `GET /api/automation/sweep-runs` gains a repeatable `status` (`PROFIT`, `LOSS`, `FLAT`, `NO_ENTRY`, `OPEN`; anything else is 400 `INVALID_REQUEST`).
- `AutomationSweepRunPageResponse` gains:
  - `statusCounts: Map<String, Long>`: every status, under every filter but `status`;
  - `totals: AutomationSweepRunTotalsResponse(rows: Long, realizedPnl: String, worstCasePnl: String)`: every filter, `status` included.

**Frontend types**
- `RunSummary.outcomes?: RunOutcome[]` and `RunPage.totals?: RunTotal[]`.
- `AutomationSweepRunPage.statusCounts?` and `.totals?`.
- All of them are optional, so an older backend reads as "not known", never as zero.

## Domain Rules

**Totals**

1. **The Runs list total.** It sums `realized_pnl` over the runs matching every filter of the request (status tab included), created at or before its `asOf`, separately for each `quote_mint`. `runs` is how many runs are in each sum.
   - Its `runs` add up to the page's `total`.
   - With no status filter, its sums equal the sum of all statuses' sums.
2. **The sweep runs total.** It sums `realized_pnl` and `worst_case_pnl` over the rows matching every filter, `status` included. `rows` equals the page's `total`.
3. **Exact arithmetic.** Sums are `BigDecimal` from the database `numeric` sum at scale 12. They are read with `setScale(12, RoundingMode.UNNECESSARY)`, which throws rather than rounding if the scale ever differs, and sent with `toPlainString`.
   - No match gives `"0.000000000000"` with `rows: 0`, and an empty list on the Runs list.
4. **The total's USDT value** is the SOL total × the live rate, through `multiplyDecimals`, exactly as for a row (runs list plan, Rules 15 and 16).
   - There is no USDT value for a non-SOL total, or without a rate.
   - It is labelled "now".

**Exit reasons**

5. **A run's `outcomes`.** One entry per distinct reason among its tokens, with how many tokens had it:
   - `SOLD`, with a `CloseReason` code, for a closed position;
   - `HELD`, with the `exit_unavailable` code, for an open position;
   - `NOT_BOUGHT`, with the `rejection_reason` code, for a rejected order.

   They are ordered SOLD, HELD, NOT_BOUGHT, then by count from high to low, then by code.
6. **Missing reasons and the counts.** A position or rejected order stored without a reason is left out; no code is invented.
   - So per stage the counts are at most `closedCount`, `openCount` and `rejectedCount`. Those summary counts stay the authority.
   - The UI shows a reason only when it is known.
7. **No run is skipped.** A run with no positions and no orders (no cycle) has `outcomes: []`. A run id with nothing stored is never an error.
8. **One query on each store per call**, bounded by the run ids given (at most 100 per page). Both use the existing `run_id`-prefixed indexes.

**Sweep status**

9. **Status per row** is as in Key Decision 6. In the query it is one `case` expression, held by a test to `RunStatus.of(filledCount = position ? 1 : 0, openCount = OPEN ? 1 : 0, realizedPnl)`.
10. **Status counts.**
    - `statusCounts` always has all five statuses, zero included, under every filter except `status`.
    - With `status` set, `total` and `totals` cover only those statuses, while `statusCounts` keeps all five.
11. **Status and Outcome combine with AND.** For example "Loss" with Outcome "Take profit" can match nothing, and then the empty state says which filters are in effect.

**UI**

12. **The Runs list's Trade column** for a one-token run is the chip plus the reason in the same words as the variation table:
    - "Sold · Take profit", "Sold · Stop loss", "Sold · Time exit", "Sold · Decu sold";
    - "Still held · Still open at the end";
    - "Not bought · No dip", "Not bought · Under min market cap".

    The reason's tooltip is its existing explanation (`outcomeExplanation`). With no known reason, the chip stands alone.
13. **A multi-token run** keeps its counts line ("2 of 3 tokens bought · 1 still held"). Its tooltip lists each reason with its count.
14. **The Runs list total** sits above the table as a small KPI strip: "Runs matching", "Total realized P&L" (SOL, signed, exact on hover) and "≈ USDT now". Any other quote gets its own line under it. The hint says "Realized only: still-held positions are not in it."
15. **The variation runs table.**
    - It shows the status tabs with counts above its filters. The tab lives in the URL as `status`, so links keep it, and changing it returns to page 1.
    - Under the tabs: "N rows · realized +X SOL ≈ Y USDT now · worst case −Z SOL".
16. **With `totals` absent** (an older backend), no total is shown. A dash or zero would claim a result.

## Data Model

No schema change. Every value is read from existing columns, through existing indexes:
- `simulation_runs`: the status aggregate it already runs;
- `positions` and `paper_orders`: `run_id`-prefixed;
- `automation_sweep_runs`: `(label, plan_id)`, `run_id` and the session index.

Nothing to apply on the server.

## Transactions & Integration

- All reads; no write transaction and no provider call.
- The Runs list search keeps its query count, since the totals ride the status aggregate. The run outcomes add two indexed queries per list, detail or start call.
- The sweep runs list adds one aggregate query per page request, bounded by the rows the filters match (a label's rows when called from a variation page).
- The live USDT rate is the header's own query (runs list plan, Key Decision 4).
- No configuration property is added.

## What To Implement

**A. Backend** (this session, `backend-developer`). A1, A2 and A3 touch disjoint files and can run in parallel.

1. **Runs list totals.** `RunSearchSql`'s status aggregate also groups by quote mint and sums realized P&L, and `RunSearchResult.totals` is built from it. `RunPageResponse.totals`.
2. **Exit reasons on run summaries.**
   - `rejectionsByRun` on `PaperOrderStore`, and `outcomesByRun` on `PositionStore` with `PositionOutcomeCounts`.
   - `RunOutcomes`, filled on list, detail and start next to the variations.
   - `RunSummaryResponse.outcomes`.
3. **Sweep runs status and totals.**
   - The status `case` in the `SWEEP_RUN_FILTER` queries.
   - The `status` parameter, validated in `AutomationQueryService.sweepRuns`.
   - The grouped aggregate giving `statusCounts` and `totals`.
   - `AutomationSweepRunPageResponse` extended.
4. The README rows for `GET /api/runs` and `GET /api/automation/sweep-runs`.

**B. Frontend** (the console session). It depends only on the fields above, and can start against MSW fixtures.

1. Move the status tabs out of `RunsPage.tsx` into a shared component, with counts.
2. **Runs list:**
   - the totals KPI strip, per quote, with USDT for SOL;
   - the Trade column's reason and its tooltip;
   - a `stageReason(code)` that drops " · no entry".
3. **`SweepRunsView`:**
   - the status tabs and the `status` URL parameter;
   - the totals line;
   - empty states naming the tab;
   - "—" in Realized and Worst case for a row with no position (Open Question 1).
4. Fixtures, tests, and the browser check at 1440 and 375 px. Screenshots go in `frontend/qa/20261125-run-totals-exit-reasons/`.

## Verification

**Backend** (test first). Run `export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10"`, then `./gradlew test`.
- **`SimulationRunSearchIntegrationTest`**:
  - totals per quote for the request's statuses;
  - no status set sums every status;
  - the `asOf` snapshot excludes later runs;
  - no match gives an empty list;
  - the sums are exact at scale 12.
- **`RunSearchSql` test**: the grouped aggregate still matches `RunStatus.of`.
- **`PaperOrderStoreServiceTest` / `PositionStoreServiceTest`** (Testcontainers):
  - counts per run and reason;
  - only the given runs;
  - an empty id list makes no query;
  - rows without a reason are left out.
- **`RunOutcomesTest`**:
  - stage order and count order;
  - every id gets an entry;
  - one call per store.
- **`RunSearchControllerTest` / `RunVariationsControllerTest`**: `outcomes` and `totals` in the JSON.
- **`AutomationSweepRunsTest`** (or the existing sweep-runs query test):
  - each status filter;
  - `statusCounts` has five keys, zeros included, and ignores `status`;
  - `totals` follow `status`;
  - Outcome and status combine with AND;
  - an unknown status gives 400;
  - the status `case` agrees with `RunStatus.of` for open, closed (+, −, 0) and no position.

**Frontend.** Run `npm test`, `npm run lint`, `npm run format:check` and `npm run build`.
- **`runList.test.ts`**: the reason text per stage, and " · no entry" dropped.
- **`RunsPage.test.tsx`**:
  - the totals strip with USDT;
  - a non-SOL total on its own line;
  - no total from an old backend;
  - the Trade reason, with its tooltip;
  - a multi-token tooltip.
- **`AutomationPages.test.tsx`**:
  - the tabs with counts on the variation page;
  - the `status` URL param;
  - the totals line;
  - tab + Outcome.

## Documentation Sync

- **README `GET /api/runs` row:** `totals` (per quote, realized only, every filter, the status included) and `outcomes` on every run summary.
- **README `GET /api/automation/sweep-runs` row:** `status`, `statusCounts` and `totals`.
- **No ADR.** Nothing here changes simulation fidelity or reproducibility.
- After delivery, this plan moves to `docs/plans/archive/` with a Delivered note.

## Acceptance Criteria

**Backend**
- With the Profit tab, `GET /api/runs?status=PROFIT` returns `totals: [{quoteMint: <wrapped SOL>, runs: N, realizedPnl: "<sum>"}]`, where `N` equals `total`.
- A one-token run that took profit has `outcomes: [{code: "TAKE_PROFIT", stage: "SOLD", count: 1}]`.
- `GET /api/automation/sweep-runs?label=V00&status=LOSS` returns only closed losing rows. Its `statusCounts` keep all five statuses. Its `totals.realizedPnl` is the exact sum of those rows.

**Runs list**
- The Runs list shows "Sold · Take profit" for that run, and its Profit tab shows the total in SOL and "≈ … USDT now".

**Variation page**
- The Runs of V00 table shows All / Profit / Loss / Flat / Not bought / Still held with counts.
- Clicking Loss shows only losing rows. The totals line reads the losses' sum and the URL carries `status=LOSS`.

## Open Questions

Answered on approval, as recommended.

1. **Should the variation table show "—" for rows that bought nothing?** Today its Realized and Worst case columns show "0.0000" for them, as the Runs list did before the last change.
   - Recommended: **yes, as on the Runs list**. A row with no position shows "—" in both columns, with the reason in the tooltip.
   - A still-held row keeps its worst case, which is a real figure.

## Delivered

2026-11-25. Both parts are built as planned; nothing is committed yet.

**Backend.**
- `RunSearchResult.totals` comes from the grouped status aggregate (status and quote mint), with no extra query.
- `outcomes` on every run summary, from `PaperOrderStore.rejectionsByRun` and `PositionStore.outcomesByRun` through `RunOutcomes`.
- On `GET /api/automation/sweep-runs`: `status`, `statusCounts` and `totals`.
- No schema change.

**Frontend.**
- The totals strip on the Runs list.
- The reason under each Trade chip.
- Shared `StatusTabs` and a `UsdtNow` component.
- On `SweepRunsView`: the status tabs, the totals line, and "—" for rows with no position.

**Differences from the plan.**
- **One snapshot for each sweep page** (from the review).
  - The page `total` is now `totals.rows`, from the same aggregate as `statusCounts`, and the row query has no count query.
  - `sweepRuns` runs at REPEATABLE READ, so the rows, the aggregate and the item reads share one snapshot.
  - Before this, a sweep row committed mid-request could make `total`, `totals.rows` and the tab counts disagree by one.
- **Counts are per entry, not per token.** Each position or rejected order counts once, so a token with several entry offsets counts more than once. That is consistent with Rule 6, and the README says "per entry".
- **`RunStatusAggregate`** lives in `replay/infrastructure/persistence`, not in replay's public domain. Both sums use `DecimalPolicy.SCALE`.
- **Frontend.**
  - A sold run's tooltip has its own sentence ("Sold when the price reached the take-profit level."), because the outcome explanations of the four sale reasons were not written for a sale.
  - The reason sits under the chip so Realized P&L stays in view at 1440 px.
  - The totals line drops "≈ 0.00 USDT" when the realized total is exactly zero.

- **Code review fixes (2026-11-25).**
  - `GET /api/runs?size=0` is count only: no page query and no variation or outcome lookups. The list's 30-second new-runs check uses it. Only this endpoint accepts 0.
  - On the variation table and day page, a closed `WALLET_SOLD` row is explained as a sale.
  - The Runs totals fade while a new tab loads.
  - The totals' USDT tooltip no longer points at "the run's page".
  - The new tooltips can be reached by keyboard.
  - The variation totals give the rate time on the automation clock.

**Verification.**
- `./gradlew test`: 2,419 tests, 0 failures, after the review fixes.
- Frontend: 743 tests pass, and `npm run lint`, `npm run format:check` and `npm run build` are clean. The review fixes were checked with tests, not in the browser.
- Browser check at 1440 and 375 px against the mocks; screenshots in `frontend/qa/20261125-run-totals-exit-reasons/`.

**Docs.** The README rows for `GET /api/runs` (`totals`, `outcomes`) and `GET /api/automation/sweep-runs` (`status`, `statusCounts`, `totals`).
