# Clearer runs list: token links, one trade column, variations and live USDT

> The Runs list links each token out, merges four count columns into one plain "Trade" column, drops the quote mint, shows realized P&L in SOL with its value at the live SOL/USDT rate, and names the variation each run was swept under.

Status: Delivered 2026-11-24 (approved as written, both open questions answered as recommended: pump.fun links with the short address as text; the run page's count KPIs reworded in this change)

## Context

The Runs page is `frontend/src/features/runs/RunsPage.tsx` (`RunsTable`), with its list model in `runList.ts`. Today its columns are: compare, Run, Created, Dataset, Token, Buy, Scenarios, Filled / rejected, Open / closed, Realized P&L and Quote mint.

**What the confusing columns mean.** A run has one scenario per tracked-wallet buy in its dataset, so one per token.
- *Filled* scenarios bought; *rejected* ones never bought, each with a reason.
- *Open* positions were bought and still held when the recorded data ended; *closed* ones were bought and sold.
- Every automation sweep run has exactly one scenario. For those runs the three columns only repeat what the server already derives as `status`, which also drives the filter tabs above the table: `NO_ENTRY` (nothing filled), then `OPEN` (any position open), then `PROFIT`, `LOSS` or `FLAT` by the sign of the realized P&L (README, `GET /api/runs`).

**Token.** The column shows `<Mint>`: a short address and a copy button. `lib/marketLinks.ts` already has `pumpFunTokenHref`, `solscanTokenHref` and `tokenLinks`. The variation runs table (`features/automation/SweepRunsTable.tsx`, `TokenCell`) shows the mint plus "Solscan ↗".

**Quote mint.** Every sweep run is quoted in wrapped SOL, because a variation edit refuses any other quote. A run started by hand may use another quote mint (`POST /api/runs` takes `quoteMint`), and its `realizedPnl` is in that mint's units.

**USDT today.**
- The header's `SolTicker` polls `GET /api/market/sol-usdt` every 30 seconds (`useSolUsdtPrice`). That is Binance's SOLUSDT ticker, cached for 30 seconds by the backend and enabled in the local and prod profiles.
- The run page converts at each trade's own minute (`GET /api/runs/{id}/usdt`, [ADR](../../adr/20261005-sol-usdt-display-conversion.md)). That costs one request per run, and each request may make up to 20 Binance klines calls. It is too heavy for a 20-row list.

**Variation.** A run does not know its variation.
- The link lives in `automation_sweep_runs` (module `console.automation`): each row ties `(plan_id, label)` to a `run_id`, and the index `idx_automation_sweep_runs_run (run_id)` already exists.
- A run's identity is `(engine_version, dataset_checksum, configuration_hash)`, and storage is not part of it. So a run started by hand with a variation's exact configuration on a swept dataset *is* that sweep run.
- Two labels can share one run: an edit refuses `VARIATION_DUPLICATE` only against *active* variations, so a later variation may repeat an archived one's settings.

**Where the runs API lives.** `RunController.page` calls `RunSearchConsoleService`, which calls replay's `SimulationRuns.search`; all of that is the `console` module.
- `console.automation` already imports `console` (`ConsoleAddresses`, the capture DTOs, `ConditionalOnConsoleEnabled`). So `console` must not import `console.automation`, or the two would form a cycle.
- The precedent for this is `console.domain.CaptureSourceLookup`: a port with an `ABSENT` value, implemented in `console.capture` and wired through an `ObjectProvider` in `DatasetSourceConfiguration`.

## Scope

### In scope

- **Token cell.** A link that opens the token outside the console. One shared component, which also replaces `TokenCell` on the variation runs table.
- **Trade column.** One "Trade" column replaces Scenarios, Filled / rejected and Open / closed. The filter tabs use the same words.
- **Quote mint.** The column goes. The unit is printed after each P&L instead.
- **P&L.** Realized P&L in SOL, with a second line showing its value in USDT at the live rate.
- **Variations on runs.** `variations` on every run summary: list, detail and start. A Variation column on the list, and a Variation row on the run page.

### Out of scope

- **Filtering the list by variation.** The variation page already lists its runs, with paging and date filters.
- **USDT at each trade's own rate in the list.** That stays on the run page (the cost is given under Context).
- **Token names or tickers.** No token metadata is stored (README: "Actual token metadata … not implemented"). The pump.fun page shows them.
- The compare page.

The run page's count KPIs ("Tokens · entered / no entry", "Open / closed") were out of scope in the proposal; Open Question 2 moved them in.

## Key Decisions

1. **One "Trade" column, derived from `status`.**
   - Words: `NO_ENTRY` is "Not bought", `OPEN` is "Still held", and `PROFIT`, `LOSS` and `FLAT` are "Sold".
   - Only a run with more than one token gets a second, dimmed line of counts.
   - Rationale: for the sweep runs that fill the list, the three count columns hold one bit each, and that bit is already in `status`. The P&L colour and sign already say profit or loss, so the chip says only what happened to the position.
2. **The filter tabs use the same words.** "No entry" becomes "Not bought" and "Open" becomes "Still held". The API codes do not change.
3. **The Quote mint column goes; the unit follows the number.** Each P&L reads `+0.024477624 SOL`, or ends with the short mint for another quote.
   - `trim` drops trailing zeros. The value is unchanged, and the full stored string stays in the tooltip.
4. **USDT at the live rate, computed in the browser.**
   - It uses the header's own query (same query key, so no extra request). The product is exact; it is shown to 2 places, with the exact value one hover away, and labelled as today's value.
   - The ADR rejected "today's rate for everything" for showing past trades at a value they never had. This line is not a realized USDT P&L. It is the current worth of the SOL result, labelled "now", and the run page keeps the trade-time view.
   - Rejected: per-row `/usdt` requests (20 requests per page, each with up to 20 klines calls).
   - Rejected: a current-rate field from the server. It would put a moving value into a snapshotted page, and disagree with the header between polls.
5. **Variation labels on every run summary**, as `variations: List<String>`. They are filled through a `console` port that `console.automation` implements; elsewhere the port is `ABSENT`.
   - Rejected: the frontend joining a second automation endpoint. That is a second request per page, and the labels would arrive after the rows.
   - Rejected: a label column on `simulation_runs`. A run is identified by its configuration, may belong to zero or several variations, and replay must not know about automation.
6. **One shared `TokenLink`.** The short address is the link text, followed by ↗, and the copy button stays. It opens pump.fun (Open Question 1) and also replaces `TokenCell`, so both run lists behave the same.

## Module Contracts

Owner: `console` for the run summary contract, `console.automation` for the lookup's implementation. Every change is **additive**; no contract breaks.

- **The port.** `console.domain.RunVariationLookup` is a `fun interface`:
  - `fun labels(runIds: Collection<String>): Map<String, List<String>>`
  - `companion object { val ABSENT = RunVariationLookup { emptyMap() } }`, for profiles without the automation console.
- **The adapter.** `console.automation.application.AutomationRunVariationLookup : RunVariationLookup`, annotated `@Service @ConditionalOnAutomationConsole`.
  - It reads `AutomationSweepRunRepository.findRunLabels(runIds, planId)`, with `planId = AutomationProperties.sweepPlan`.
  - It returns each run's labels ordered by the label's number.
- **The repository query.**
  - `findRunLabels`: `select r.runId as runId, r.label as label from AutomationSweepRunEntity r where r.runId in :runIds and r.planId = :planId`.
  - Projection: `AutomationRunLabelRow(runId, label)`, in `infrastructure/persistence`.
- **The console helper.** `console.application.RunVariationLabels`. It is built in `console.config.RunVariationConfiguration` from `ObjectProvider<RunVariationLookup>`, with `getIfAvailable { RunVariationLookup.ABSENT }`, as in `DatasetSourceConfiguration`.
  - `fun of(runIds: Collection<String>): Map<String, List<String>>` deduplicates the ids, makes no call for an empty list, and gives every requested id an entry (an empty list when it has none).
  - `RunSearchConsoleService.search`, `ConsoleService.detailWithSource` and `RunLaunchService.start` use it.
- **Where the labels travel.**
  - Console domain carriers: `RunSearchPage` gains `variations: Map<String, List<String>>`, and `SourcedRunDetail` gains `variations: List<String>`.
  - The start path gets a console-side carrier next to `RunStart`; `RunStart` itself belongs to replay and is not changed.
- **API.** `RunSummaryResponse.variations: List<String>`, never null, on `GET /api/runs`, `GET /api/runs/{id}` (`run.variations`) and `POST /api/runs`.
- **Frontend.** `RunSummary.variations?: string[]`. It is absent from an older backend, which reads as unknown and shows "—".

## Domain Rules

**Variation labels (backend)**

1. A run's `variations` are the distinct labels of the `automation_sweep_runs` rows whose `run_id` is that run and whose `plan_id` is the configured sweep plan. They are ordered by the label's number: `V02` before `V10`, and `V99` before `V100`.
2. A refused entry's row has no `run_id` and never contributes.
3. A row under another `plan_id` never contributes, because the variations page reads only the configured plan and that label would open nothing.
4. A run without a row has `[]`. Examples: a run started by hand, or a run of a dataset that was never swept. The field is never null.
5. An archived variation's label is listed like an active one. A label never changes meaning ([ADR](../../adr/20261122-stored-editable-variations.md), Decision 2).
6. A run under two labels lists both (`["V03", "V12"]`).
7. Without the automation console (the `test` and default profiles), every run has `[]` and no query runs.
8. **One query per call.**
   - A page asks once for its run ids (at most 100, the replay page cap).
   - Detail and start ask once, for one id.
   - An empty page asks nothing.
   - No N+1.
9. The lookup is a read, with no write transaction, and nothing about it is cached across requests: a later sweep row for the same run shows on the next load.

**Trade column (frontend)**

10. The label is `NO_ENTRY` → "Not bought", `OPEN` → "Still held", and `PROFIT` / `LOSS` / `FLAT` → "Sold". It comes from an exhaustive switch over `RunStatus`.
    - Tones: "Still held" gets `attention`; "Sold" and "Not bought" get `neutral`.
11. **The second line**, only when `scenarioCount > 1`, reads `{filled} of {n} tokens bought`. When `openCount > 0` it adds `· {open} still held`, for example "2 of 3 tokens bought · 1 still held".
12. The tabs read All, Profit, Loss, Flat, Not bought, Still held. The `status` query values stay `PROFIT … OPEN`, so saved links keep working. The empty-state sentence reads naturally with the new words (for example "No run is still held.").
13. An old URL with `?sort=scenarios` falls back to newest first (the `parseSort` default) without an error.

**P&L and USDT (frontend)**

14. P&L shows as `<Pnl value trim unit>`, where the unit is `mintLabel(run.quoteMint)` ("SOL" for wrapped SOL). Sorting stays by the exact SOL value (`compareDecimals`); the rate never affects order.
15. **The USDT line** appears only when `quoteMint` is wrapped SOL and the live rate is non-null.
    - **Value.** It is `realizedPnl × rate`, computed exactly with `multiplyDecimals`: integer arithmetic at the sum of both scales, with no rounding.
    - **Display.** It shows `≈ ` plus the value truncated toward zero to 2 places, then `USDT`. It has `+` for a gain and `−` for a loss, and no sign when it shows as `0.00`.
    - **Tooltip.** The exact product without trailing zeros, the rate and its time ("1 SOL = 69.08 USDT, Binance, 20:51:30"), and "The run's page shows USDT at each trade's own rate."
16. With no rate (still loading, or `rate: null`) there is no USDT line.
    - When the backend gives a reason, the Section hint says "USDT unavailable: {codeLabel(reason)}".
    - After a failed poll, the last good rate stays in use with its own time in the hint. Nothing older or interpolated is used.
17. The rate in use is stated visibly in the Section hint, for example "USDT at 1 SOL = 69.08 USDT (now)", not only in a tooltip.
18. A run with another quote mint shows its P&L with the short mint as the unit, and no USDT line.

**Token and variation cells (frontend)**

19. **`TokenLink`.**
    - The link: href `pumpFunTokenHref(mint)`, `target="_blank"`, `rel="noopener noreferrer"`, and `aria-label="Open token {mint} on pump.fun"`.
    - Clicking it does not open the run (`stopPropagation`).
    - The copy button copies the full mint.
    - A value that is not a Solana address (synthetic fixtures) shows as plain text, with no link (`isSolanaAddress`).
    - The "+N" tooltip of a multi-token dataset stays.
20. **The Variation cell.**
    - Each label is a link to `paths.automationVariation(label)`, with `stopPropagation`.
    - With no label it shows "—" and the tooltip "Not run by a sweep variation".
    - With the field absent (older backend) it shows "—" with no tooltip claim.
21. **The run page** shows a "Variation" row in its facts table, with the same links, or "—".

Number types: the backend computes no number here. The frontend keeps every amount a decimal string; the one multiplication is exact, and only its display is shortened.

## Data Model

No schema change: no new table or column. The lookup uses the existing `idx_automation_sweep_runs_run (run_id)` index, and nothing needs to be applied on the server.

## Transactions & Integration

- The lookup is one repository read per request, outside any write transaction, in the repository's existing idiom.
- No provider call is added. The browser reuses `GET /api/market/sol-usdt`, which the header already polls every 30 seconds while the tab is visible, under the same TanStack key, so no new request is made.
- No configuration property is added.

## What To Implement

**A. Backend: variation labels on run summaries** (this session)

1. Labels for a set of runs: `RunVariationLookup` with `ABSENT`, `AutomationRunVariationLookup`, the `findRunLabels` query and its projection, `RunVariationLabels` and `RunVariationConfiguration`.
2. `variations` on every run summary: list, detail and start, through the console carriers and `RunResponseMapper`, plus the README row.

**B. Frontend: the clearer list** (the console session [local_db1fbc42](#local_db1fbc42-e749-4840-9cfc-07a48513d4f8)). It depends only on the field above; it can start in parallel against MSW fixtures that carry `variations`.

1. Exact USDT: `multiplyDecimals` and `truncateDecimal` in `lib/decimal.ts`. `shortSol` delegates to `truncateDecimal`, so the truncation rule lives once.
2. Token links: `TokenLink` next to `Mint` in `components/DisplayValues.tsx`. It replaces `<Mint>` in `RunTokens` and `TokenCell` in `SweepRunsTable`.
3. Trade wording: `tradeLabel(run)` in `runList.ts`, the renamed `STATUS_LABELS`, and the `scenarios` sort key removed.
4. The table:
   - columns compare, Run, Variation, Created, Dataset, Token, Buy, Trade, Realized P&L;
   - the P&L cell with its unit and USDT line;
   - the hint with the rate;
   - `miw` reduced to fit.
5. The run page's Variation row, and its count KPIs reworded to "Bought / not bought" and "Sold / still held" (`RunTotals`).
6. MSW fixtures, tests, and a browser check at 1440 and 375 px, with screenshots in `frontend/qa/20261124-runs-list-clarity/`.

A and B touch disjoint files.

## Verification

**Backend** (test first). Run with `export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10"` and then `./gradlew test`.
- **`AutomationRunVariationLookupTest`** (Testcontainers). It proves:
  - labels per run for the configured plan, ordered by number (`V02`, `V10`, `V100`);
  - two labels on one run;
  - refused rows (null `run_id`) ignored;
  - another plan's rows ignored;
  - a run without rows absent from the map.
- **`RunVariationLabelsTest`**:
  - an empty id list makes no lookup call;
  - every requested id gets an entry;
  - duplicates are collapsed;
  - `ABSENT` gives `[]`.
- **`RunSearchConsoleServiceTest`**: the page carries the labels, and the lookup is called once with exactly the page's run ids.
- **`RunControllerTest`**: `variations` appears in the JSON of the list, the detail (`run.variations`) and the start response; a hand-started run has `[]`.
- **A test-profile context test** (existing): it starts without the automation console and serves `[]`.

**Frontend.** From `frontend/`, run `npm test`, `npm run lint`, `npm run format:check` and `npm run build`.
- **`lib/decimal.test.ts`**:
  - `multiplyDecimals("0.024477624000", "69.08") === "1.69091426592000"`;
  - negative and zero products;
  - `truncateDecimal` toward zero, with no sign at zero.
- **`runList.test.ts`**:
  - `tradeLabel` for all five statuses;
  - the multi-token line, with and without a still-held position;
  - `parseSort("scenarios")` falls back to the default.
- **`RunsPage.test.tsx`**:
  - headers: no Scenarios, Filled / rejected, Open / closed or Quote mint;
  - the Variation link goes to `/automation/variations/V03`, and two labels both show;
  - "—" when there is no label;
  - the token link's href and `target`, and clicking it does not navigate the row;
  - the P&L reads "+0.024477624 SOL" and "≈ +1.69 USDT" at a fixture rate of 69.08;
  - with the rate unavailable there is no USDT line, and the hint gives the reason;
  - another quote shows the short mint unit and no USDT line;
  - the tabs read "Not bought" and "Still held".
- **`RunDetailPage.test.tsx`**: the Variation row.
- **`AutomationPages.test.tsx`**: the Solscan expectation on the variation runs table becomes pump.fun.

## Documentation Sync

- **README**, `GET /api/runs` row. "Every run summary carries `status`, `datasetName`, `mints` and `anchorBlockTime`" gains: "and `variations`, the labels of the configured sweep plan whose sweep produced the run, by number, empty when none did."
- **[ADR SOL/USDT display conversion](../../adr/20261005-sol-usdt-display-conversion.md):** an "Update 2026-11-24" note. The runs list shows each SOL P&L's value at the current rate, labelled as today's value. The trade-time view on the run page is unchanged.
- **No new ADR.** Nothing here affects simulation fidelity or reproducibility.
- After delivery, this plan moves to `docs/plans/archive/` with a Delivered note.

## Acceptance Criteria

**API**
- `GET /api/runs` returns `"variations":["V03"]` for a run swept under V03, and `[]` for a run started by hand.
- In the `test` profile, every run has `"variations":[]`.

**Runs list**
- The table's headers are exactly: Run, Variation, Created, Dataset, Token, Buy, Trade, Realized P&L, plus the compare checkbox.
- A sweep run that bought and sold shows "Sold". One with no entry shows "Not bought", and the tabs read "Not bought" and "Still held".
- A row with `realizedPnl` "0.024477624000" at the rate 69.08 shows "+0.024477624 SOL" and "≈ +1.69 USDT". Hovering shows "1.69091426592" and the rate's time.
- The token cell is a link to `https://pump.fun/coin/<mint>` that opens a new tab. A click elsewhere on the row still opens the run.

**Run page**
- The page shows "Variation" with a link to the variation.

## Open Questions

Both answered on approval, as recommended.

1. **Where should the token link go, and what text should it show?**
   - Recommended: **pump.fun**, with the short address as the link text plus ↗. Every captured token is a pump.fun token, and its page shows the name, ticker and chart. The address keeps rows distinguishable.
   - Alternatives: Solscan (an explorer, which the variation runs table uses today) or DexScreener. Or only "pump.fun ↗" as the text, with no address.
2. **Should the run page's KPIs use the same words?** Its "Tokens · entered / no entry" and "Open / closed" have the same problem.
   - Recommended: **yes, in this change**: "Bought / not bought" and "Sold / still held". It touches one component (`RunTotals`) and its test.

## Delivered

2026-11-24. Both parts are built as planned. The frontend is commit `83eac66`; the backend and docs are committed after it.

**Backend (Part A).** Every run summary on `GET /api/runs`, `GET /api/runs/{id}` and `POST /api/runs` carries `variations`.
- The port `RunVariationLookup` (with `ABSENT`) is implemented by `AutomationRunVariationLookup` through `findRunLabels`.
- `RunVariationLabels` is wired in `RunVariationConfiguration`.
- The carriers are `RunSearchPage.variations`, `SourcedRunDetail.variations` (required, no default) and `LabeledRunStart`.
- No schema change.

**Frontend (Part B).**
- Exact `multiplyDecimals` and `truncateDecimal`.
- A shared `TokenLink` (pump.fun) on the runs list and the variation runs table.
- The Trade column and the renamed tabs.
- P&L with its unit and the live-rate USDT line.
- The Variation column (`RunVariations.tsx`), shared with the run page's Variation row.
- The run page's KPIs reworded.

**Differences from the plan.**
- **`VariationEditRules.seqOf`.** Added as the inverse of `labelOf`, so the adapter's numeric order keeps the label format in one place. A label that is not `V` plus digits sorts last.
- **Controller tests.** The plan's `RunControllerTest` became `RunSearchControllerTest` (list JSON) and `RunVariationsControllerTest` (detail and start JSON).
- **Review tests.** The review added `RunLaunchServiceTest` (the start path with real labels), `RunVariationConfigurationTest` (the `ObjectProvider` fallback) and `VariationEditRulesLabelTest`.
- **Created column.** At 1440 px the Created date, which is long in the ru-RU locale, wraps onto two lines. The table's minimum width is 1040, so the whole row fits.
- **Run page hint.** The open-reasons hint on the run page now starts "Still held: …".
- **Realized P&L with nothing sold** (from the code review, approved by the user). A run with no position closed shows "—" in the list instead of "0 SOL ≈ 0.00 USDT", with the reason: "Not bought, so nothing was realized" or "Still held, nothing sold yet" (`pnlDisplay` in `runList.ts`). A run that closed something at exactly zero keeps "0 SOL" without the USDT line. On the server's data about 4,177 of 4,290 runs were never bought, so the column was mostly zeros.

**Verification.**
- `./gradlew test`: 2,374 tests, 0 failures, before the review fixes. After the fixes, the affected classes pass (they are listed in the review-fix run).
- Frontend: `npm test` passes except the ru-RU locale test in `src/lib/format.test.ts`, which the console session then made locale-independent (`e18373c`). `npm run lint`, `npm run format:check` and `npm run build` are clean.
- Browser check at 1440 and 375 px against the mocks; screenshots in `frontend/qa/20261124-runs-list-clarity/`.

**Docs.**
- README `GET /api/runs` row: `variations`.
- [ADR SOL/USDT display conversion](../../adr/20261005-sol-usdt-display-conversion.md): the 2026-11-24 update note.
