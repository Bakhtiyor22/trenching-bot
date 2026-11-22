# Editable variations, variation runs and a 4,000-trade market cap

> Variations become stored, editable records whose history is kept; each variation shows its runs with date filters and the
> token address; automation's market trade cap rises from 2,000 to 4,000.

Status: Delivered (2026-11-22; approved the same day as written, every Open Question settled as recommended)

## Context

"Variation" is the console's word for a **sweep entry**: one `SimulationConfig` replayed on every captured buy.

- The variations are code constants today: `SweepPlans.DECU_SWEEP_1` (`console/automation/domain/SweepPlans.kt`) holds `V00`
  (the agreed configuration) and `V01`–`V09`, one changed factor each. `SweepPlan`'s own comment and ADR
  [20261102-automated-capture-and-sweep-evaluation](../../adr/20261102-automated-capture-and-sweep-evaluation.md) Decision 6 say
  "a changed list is a new plan id, never an edit". Nothing can be edited without a code change and a deploy.
- A sweep run is one row of `automation_sweep_runs`, identified by `sha256(item|plan|label)` and unique on
  `(item_id, plan_id, label)`. It records the run id, configuration hash and engine version. The run itself
  (`simulation_runs`) holds the full configuration snapshot.
- `AutomationSweepService` runs every entry of `properties.activePlan()` an item has no row for; the item is `SIMULATED` once
  every entry has a row. `AutomationDayService` builds one `DayScoring` from the same plan and emits one verdict row per entry
  and trading day, tallied by label over the 7-day window.
- `GET /api/automation/sweep-runs` pages sweep runs newest buy first with `sessionId`, `mint`, `label` and `outcome` filters.
  It has no date filter; the console's "range" control only narrows its session picker. Each row already carries the buy's
  `mint`, `signature` and `blockTime`.
- The console hard-codes what each label means (`PLAN_CHANGES` in `frontend/src/features/automation/automationModel.ts`), and
  clicking a variation only moves the trend chart.
- `trenching.automation.market-max-transactions` is `2000`. Startup requires it to be at most
  `recovery.requests × max-step-attempts`, which is `1200 × 2 = 2400` today, so `4000` alone fails startup.

## Scope

### In scope

1. Market trade cap `2000 → 4000`, with the request allowance that makes it valid.
2. Variations stored in the database, seeded from the code plan, listed and read through the API.
3. Editing a variation: any number of settings in one request. The edit archives the old variation and creates a new one
   under a new label, in one transaction.
4. Sweep and day scoring read the stored variations.
5. A date filter on the sweep-run list, and a console page per variation with its runs (paged, date filters, token address).
6. The console reads variation names, settings and state from the API instead of its hard-coded table.

### Out of scope

- Replaying a new variation on buys that were already simulated (Open Question 2).
- Adding a variation that replaces nothing, archiving without a replacement, restoring an archived variation.
- A second sweep plan, or switching plans from the console. `trenching.automation.sweep-plan` stays a property.
- Re-capturing buys that already failed `OVER_CAP` under the old cap.
- Any change to `strategy`, `papertrading`, `portfolio` or `replay`.

## Key Decisions

1. **A variation's configuration is immutable; an edit is "archive + create".** The edited variation becomes `ARCHIVED`
   (kept, clearly marked, never swept again) and a new `ACTIVE` variation is created with the next label (`V10`, `V11`, …).
   Labels are never reused. This is both of the requested options at once, and it needs no change to sweep-run identity:
   `(item, plan, label)` keeps meaning one fixed configuration forever. Rejected: versions under one label
   (`V03 r2`) — it would add a revision to the sweep-run key, the unique constraint, the item events and every scoring query.
2. **The plan id stays `decu-sweep-1`.** Variations are rows under a plan; the property names the plan and its code entries
   are only the **seed**. This supersedes ADR Decision 6's "a changed list is a new plan id" → new ADR.
3. **The stored variations are the authority once seeded.** On startup, a plan with no stored variation gets the code plan's
   entries; a plan that has rows is left alone, so a later code change to `SweepPlans` never rewrites history.
4. **An edit applies to buys swept after it.** A sweep tick reads the active variations once, at its start. Items already
   `SIMULATED` are not touched (Open Question 2).
5. **A variation has verdict rows only for the trading days of its life** — from the day it was created through the day it
   was archived. Tallies keep ADR Rules 22–24 unchanged: within a window, a simulated buy with no row for the label counts
   `unscored`. No decided verdict changes because of an edit (Open Question 4).
6. **The edit request carries the complete configuration**, in the existing `ConsoleConfigRequest` shape (bps or percent).
   The form is prefilled and posts every field, as New run does. A missing field is a field error, never a silent `mvp()`
   default — a default would flip, for example, `POST_TRADE` to `TRADE_AVERAGE` unnoticed.
7. **The token address comes from the run row itself** (`mint`, joined server-side from the buy), not from a client-side
   join against `/api/datasets` (Open Question 3).
8. **The cap rises with the per-operation allowance, not with more step attempts** (Open Question 1).

## Module Contracts

Owner: **`console.automation`** (a sub-package of `console`). All HTTP changes are additive; no existing field or parameter
changes meaning. One touch outside the sub-package, still inside `console`: `ConsoleConfigValidator` gains a mode that
requires every non-optional field. No other module's contract changes.

**Not a contract break**, but a **superseded decision**: ADR Decision 6 (plan fixed in code). `GET /evaluations` may now return
rows for archived labels on past days and none for them afterwards.

### Domain (`console.automation.domain`)

- `VariationState { ACTIVE, ARCHIVED }`
- `Variation(planId, label, seq, state, config: SimulationConfig, activeFrom: Instant?, archivedAt: Instant?, replacesLabel: String?, replacedByLabel: String?, createdAt: Instant)`
  — `activeFrom == null` means "since the plan began" (seeded).
- `VariationEdit(requestId: String, config: SimulationConfig)`
- `VariationEditResult(variation: Variation, created: Boolean)`
- `VariationLife` — pure: `covers(day: TradingDay, calendar: TradingCalendar): Boolean`.
- `SweepPlan` stays the value the sweep and scoring take; it is now built from the active (sweep) or living (scoring)
  variations instead of from `SweepPlans`.
- `AutomationIds.variation(planId, label): String` — `sha256(plan|label)`.
- Exceptions (extend `AutomationException`): `VariationNotFoundException`, `VariationArchivedException`,
  `VariationUnchangedException`, `VariationDuplicateException(label)`, `VariationBaselineException`,
  `InvalidVariationConfigException(fields, code)`.

### Application (`console.automation.application`)

- `AutomationVariationService` (`@ConditionalOnAutomationConsole`)
  - `fun list(state: String?): List<Variation>` — at most `AutomationLimits.MAX_VARIATIONS_PER_PLAN` (500), ordered by `seq`.
  - `fun find(label: String): Variation`
  - `fun activePlan(): SweepPlan` — the active variations, by `seq`.
  - `fun edit(label: String, edit: VariationEdit): VariationEditResult`
  - `fun seed()` — idempotent.
- `AutomationVariationTransaction` — the edit's single short transaction.
- `AutomationSweepService.sweep()` takes `variations.activePlan()` in place of `properties.activePlan()`.
- `AutomationDayService` builds its `DayScoring` per request from the plan's variations and their lives.
- `AutomationQueryService.sweepRuns(sessionId, mint, planId, label, outcome, from, to, page, size)`.

### HTTP (`/api/automation`, existing error body and paging conventions)

| Endpoint | Behaviour |
|---|---|
| `GET /variations?state=` | `{ planId, items: [AutomationVariationResponse] }`, by `seq`. `state` is `ACTIVE` or `ARCHIVED`; anything else is 400 `INVALID_REQUEST` |
| `GET /variations/{label}` | One variation; 404 `AUTOMATION_VARIATION_NOT_FOUND` |
| `POST /variations/{label}/edit` | Body `{ requestId, config }`. 201 with the **new** variation; 200 with the same body on a repeat of the same `requestId` (`ConsoleResponses.createdOrExisting`) |
| `GET /sweep-runs` | Gains `planId`, `from` (inclusive) and `to` (exclusive), both `Instant`, on the buy's block time |

`AutomationVariationResponse`: `planId`, `label`, `state`, `config: ConfigResponse` (the existing DTO, bps and percent),
`differsFromAgreed: List<String>` and `changedFromReplaced: List<String>` (configuration property names, in `ConfigResponse`
order), `replacesLabel`, `replacedByLabel`, `activeFrom`, `archivedAt`, `createdAt`.

Edit refusals:

| Case | Status | Code |
|---|---|---|
| Unknown label | 404 | `AUTOMATION_VARIATION_NOT_FOUND` |
| Missing/blank `requestId`, or longer than 64 | 400 | `INVALID_REQUEST`, field `requestId` |
| Bad, missing or unknown configuration field | 400 | `CONFIG_INVALID` / `INVALID_PERCENT` / `CONFLICTING_FIELDS`, with `fields` |
| `minSignalSol` absent, or below `capture-min-signal-sol` | 400 | `CONFIG_INVALID`, field `minSignalSol` |
| `quoteMint` not wrapped SOL | 400 | `CONFIG_INVALID`, field `quoteMint` |
| Variation is archived | 409 | `VARIATION_ARCHIVED` |
| Configuration equals the variation's own | 409 | `VARIATION_UNCHANGED` |
| Configuration equals another active variation's | 409 | `VARIATION_DUPLICATE`, field `label` |
| Label is the baseline `V00` (Open Question 5) | 409 | `VARIATION_BASELINE` |
| Concurrent edit lost | 409 | `AUTOMATION_CONFLICT` (existing) |

## Domain Rules

1. A variation's configuration columns are written once, at insert. Only `state`, `archivedAt` and `replacedByLabel` change,
   and only `ACTIVE → ARCHIVED`. `ARCHIVED → ACTIVE` does not exist.
2. An edit, in one transaction: archives variation `L`, inserts variation `L'` with `seq = max(seq) + 1` and label
   `"V" + seq` zero-padded to two digits, `replacesLabel = L`, `activeFrom = archivedAt(L) = now` from the injected
   `SimulationClock`. Either both happen or neither.
3. Example: editing `V04` (take profit 80%) to take profit 80% and stop loss 50% is one request with
   `takeProfitPercent "80"`, `stopLossPercent "50"` → `8000` and `5000` bps, exact, through the existing `PercentConversion`.
   `changedFromReplaced = ["stopLossBps"]`.
4. Edits never change the number of active variations, so a plan never has zero. A sweep tick that finds no active variation
   sweeps nothing and marks no item `SIMULATED`.
5. A sweep tick uses the variations active when it starts. An item is `SIMULATED` when each of them has a row for it. Rows of
   archived labels on that item stay as they are; no row is ever written for an archived label.
6. An item part-way through its sweep when an edit lands: its next attempt runs the then-active variations it has no row
   for. It may hold a row for the archived label and for its replacement.
7. No existing `automation_sweep_runs` row, run, run id or configuration hash is modified or deleted by an edit.
8. Every active variation has a `minSignalSol`, at or above `capture-min-signal-sol` (existing plan Rule 6). Checked on edit,
   and at startup against the stored active variations: a violation fails startup naming the label.
9. Day `D` has a verdict row for variation `V` iff `V`'s life covers `D`: `tradingDay(activeFrom) ≤ D` (always, when
   `activeFrom` is null) and (`archivedAt` is null or `D ≤ tradingDay(archivedAt)`). The tally and the status rule are
   unchanged. A day's best row is chosen among that day's rows.
10. A verdict that was decided before an edit reads the same after it: its window's items were all terminal, so all had rows
    under the label, and Rule 7 keeps them.
11. Idempotency: the same `requestId` on the same plan returns the variation it created, changing nothing — even when the
    edited label is by then archived. Enforced by the service and by `uq_automation_variations_request`.
12. Two different edits of the same variation at once: one wins; the other gets 409 `VARIATION_ARCHIVED` or
    `AUTOMATION_CONFLICT`. The edit transaction locks the plan's active rows (`PESSIMISTIC_WRITE`), which also serialises
    `seq` allocation; `uq_automation_variations_seq` is the database backstop.
13. Determinism: no `Instant.now()`; timestamps from `SimulationClock`, the id from `AutomationIds.variation`, the label
    from `seq`. Variations are always read `order by seq`.
14. Sweep-run date filter: `from ≤ blockTime < to`. `from` not before `to` is 400 on `from`. A buy with no block time is
    excluded whenever either bound is given. Other filters, the order (newest buy first) and paging are unchanged.
15. Market cap: a market window listing more than 4,000 trades ends `OVER_CAP` before any is fetched, as today. A capture
    that already listed keeps the bound it listed under. Startup still refuses a cap above
    `recovery.requests × max-step-attempts`.
16. Number types: no new calculated value. Levels are `Int` basis points; `notional`, `minMarketCapUsd`,
    `transactionCostSol` and `minSignalSol` are `BigDecimal` at `DecimalPolicy.SCALE` (12), already normalised by
    `SimulationConfig.of`. "Equal configuration" is `SimulationConfig` equality on those normalised values. No `Double`.

Not applicable here: fills, prices, exits, slots, `u64` amounts, provider calls — the edit touches none of them, and a new
variation runs through the unchanged replay.

## Data Model

**New table `automation_variations`** (entity `AutomationVariationEntity`, `Persistable<String>` with an assigned id, as
`AutomationSweepRunEntity`; `@Version version bigint`).

| Column | Type | Null |
|---|---|---|
| `id` | `varchar(64)` — `sha256(plan\|label)` | no |
| `plan_id` | `varchar(32)` | no |
| `label` | `varchar(16)` | no |
| `seq` | `integer` | no |
| `state` | `varchar(8)`, `EnumType.STRING` | no |
| `request_id` | `varchar(64)` — null for seeded rows | yes |
| `replaces_label`, `replaced_by_label` | `varchar(16)` | yes |
| `active_from`, `archived_at` | `timestamptz` | yes |
| `created_at` | `timestamptz` | no |
| `strategy_name` / `strategy_version` | `varchar(64)` / `varchar(32)` | no |
| `notional`, `min_market_cap_usd`, `transaction_cost_sol`, `min_signal_sol` | `numeric(38,12)` | no |
| `max_age_slots`, `horizon_slots`, `latency_slots` | `bigint` | no |
| `dip_bps`, `take_profit_bps`, `stop_loss_bps` | `integer` | no |
| `quote_mint` | `varchar(44)` | no |
| `dip_window_ms`, `max_hold_ms`, `hard_exit_start_ms` | `bigint` | yes |
| `price_measure` | `varchar(16)`, `EnumType.STRING` | no |
| `follow_wallet_sells` | `boolean` | no |
| `version` | `bigint` | no |

- Unique: `uq_automation_variations_label (plan_id, label)`, `uq_automation_variations_seq (plan_id, seq)`,
  `uq_automation_variations_request (plan_id, request_id)` (Postgres allows many nulls).
- Index: `idx_automation_variations_state (plan_id, state, seq)`.

**Changed table `automation_sweep_runs`**: new index `idx_automation_sweep_runs_label (plan_id, label)` for the per-variation
list. No column change. The join to `automation_items` for the date filter uses the items' primary key.

**Additive only.** `ddl-auto: update` creates the table, the unique keys and both indexes in `local`, `test` and `prod`; no
hand-applied SQL, no reset, nothing destructive. Rollback: redeploy the previous build — it ignores the new table and index
and falls back to the code plan; runs stored under labels created by edits stay in the database, unlisted by the old build's
scoreboard but intact.

## Transactions & Integration

- The edit is one `@Transactional` method on `AutomationVariationTransaction`: lock the plan's active rows, check the rules,
  archive, insert. No remote call, no replay inside it.
- Seeding is one transaction per plan; a concurrent second seed fails on `uq_automation_variations_label` and is treated as
  "already seeded".
- Reads are `@Transactional(readOnly = true)`; the list is bounded by `MAX_VARIATIONS_PER_PLAN`. A sweep tick and a days
  read each load the plan's variations once — no query per item or per day.
- No provider, no credential, no live network: fixtures suffice for everything.
- Configuration (`AutomationProperties` defaults and `application.yml`; all validated at startup as today):

| Property | Now | Proposed |
|---|---|---|
| `market-max-transactions` | `2000` | `4000` |
| `recovery.requests` | `1200` | `2400` |
| `operation-budget.requests` | `400` | `800` |
| `backfill-reserve-requests` | `1400` | `2600` (the minimum becomes `2400 + 5 × 22 = 2510`) |

  `2400 × 2` step attempts `= 4800 ≥ 4000`, the same 20% headroom as today. No new property. `application-prod.yml` overrides
  only `max-requests-per-24h` (100,000), so the server takes these defaults unless its environment sets them.

## What To Implement

1. **Market cap 4,000.** Property defaults, `application.yml` (value and comment), README rows. Independent of everything
   else; can ship first.
2. **Stored variations drive the sweep and the scoreboard.** Entity, repository, seeding at startup, `VariationLife`, sweep
   and day scoring reading the store. With only seeded rows, every existing response is byte-for-byte what it is today.
3. **Variations can be read and edited over HTTP.** The three endpoints, the complete-configuration validation, every
   refusal in the table above, request-id idempotency. Depends on 2.
4. **Sweep runs can be filtered by date and plan.** Repository filter, service validation, controller parameters, the new
   index. Independent of 2 and 3 — different files.
5. **Console: variations page, edit form, variation runs** — in its own frontend session, with `trading-console-ui`. It can
   start against MSW as soon as this plan's contract is approved; it touches only `frontend/`.
   - `api/automation.ts`: variation types and hooks; `SweepRunQuery` gains `planId`, `from`, `to`.
   - `automationModel.ts`: `PLAN_CHANGES`, `planLabels` and `BASELINE_LABEL` give way to API data; a variation's short text
     ("take profit 80%, stop loss 50%") is derived from `differsFromAgreed` and its config.
   - Routes `automation/variations` and `automation/variations/:label`, plus an entry in the automation sub-nav.
     The list shows active variations, with archived ones behind a toggle and an "Archived" badge, the archive date and
     "replaced by V10". The detail shows the settings, the lineage, **Edit**, and the runs.
   - Runs table: extracted from `AutomationSweepRunsPage` and shared with it. Filters in the URL: trading-day `from`/`to`
     (`TextInput type="date"`, converted with `dayStartInstant`, as the sessions filter does), outcome, token; one-based
     `?page`, size 50, `PagingFooter`. Token column: `<Mint>` plus a Solscan link (`solscanTokenHref`).
   - The sweep-runs page drops its session-picker "range" workaround for the same real date filter.
   - Edit form: `runForm.ts` pieces (`formValuesFrom`, `validateRunForm`, percent fields, `placeFieldErrors`), prefilled.
     Before saving it lists each change ("Take profit 55% → 80%") and states the effect: the variation is archived, a new
     one replaces it from the next captured buy, existing runs stay where they are. One `requestId` per submit, reused when
     that submit is retried.
   - Scoreboard, status grid and trend keep their click-to-select; each gains a link to the variation's page.
   - MSW: `automationHandlers.ts` and `automationFixtures.ts` for the three endpoints and the new sweep-run parameters.
6. **ADR and docs.** See Documentation Sync.

Units 1, 2 and 4 can run in parallel; 3 follows 2; 5 needs only the contract.

## Verification

Test-first. New or extended classes and what each must prove:

- `VariationLifeTest` — covers a day inside its life; not the day before creation; the archive day yes, the day after no;
  a seeded variation covers every day.
- `AutomationVariationServiceTest` (Testcontainers, as `AutomationSweepServiceTest`)
  - seeding an empty plan stores `V00`–`V09` equal to `SweepPlans.DECU_SWEEP_1`; a second seed changes nothing;
  - an edit of two settings archives the old row and creates `V10` with both values, `replacesLabel` and
    `changedFromReplaced`;
  - the same `requestId` again returns `V10` and writes nothing; a new `requestId` with the same config is
    `VARIATION_DUPLICATE`;
  - unchanged config, archived label, unknown label, baseline label, missing `minSignalSol`, `minSignalSol` below the
    capture minimum, a missing field, `80.555` percent, percent and bps that disagree, an unknown property — each refused
    with its code and field, and nothing stored;
  - two concurrent edits of one label leave exactly one new variation.
- `AutomationSweepServiceTest` — after an edit a captured item gets rows for the active labels only and becomes `SIMULATED`;
  an item swept before the edit keeps its rows and gains none; no active variation → nothing swept, no state change.
- `AutomationDayServiceTest` — seeded only: unchanged output; after an edit on day `D`: the archived label has rows through
  `D` and none after, the new label from `D` on; a verdict decided before the edit is identical after it; buys before the
  new label's creation count `unscored` under it.
- `AutomationRepositoryIntegrationTest` — the three unique keys refuse duplicates; the sweep-run page filters by `planId`,
  `from`, `to`, excludes null block times under a date bound, and keeps its order and count.
- `AutomationControllerTest` (`console/automation/api`) — status codes and bodies of the three endpoints, 201 then 200 on a
  repeat, 400 for `from` not before `to` and for a bad `state`.
- `ConsoleConfigRequestTest` — the complete-configuration mode reports each missing field; the default mode is unchanged.
- `AutomationPropertiesTest`, `AutomationStartupLogTest` — the new defaults; `4000` with `recovery.requests=1200` fails
  startup naming `market-max-transactions`; a reserve of `2509` fails.
- Frontend: `AutomationPages.test.tsx`, `automationModel.test.ts`, `handlers.test.ts`, and a test for the edit form (prefill,
  change summary, server field errors placed on their inputs, `requestId` reuse).

Commands: `./gradlew test` (with `JAVA_HOME` set to the IntelliJ JDK); in `frontend/`: `npm test`, `npm run lint`,
`npm run format:check`, `npm run build`, then the pages checked in the browser against MSW.

## Documentation Sync

- **New ADR** `docs/adr/20261122-stored-editable-variations.md`: variations stored and immutable, edit = archive + create,
  labels never reused, forward-only, life-bounded verdict rows. Supersedes Decision 6's "fixed in code; a changed list is a
  new plan id" of the 2026-11-02 ADR, which gets a pointer.
- **README**: the automation property table (`market-max-transactions`, `recovery.requests`, `operation-budget.requests`,
  `backfill-reserve-requests`, and `sweep-plan` now naming the seed), the automation API list, and the console page list.
- `docs/CLAUDE_CODE_GUIDE.md`: no change.

## Acceptance Criteria

- With the new defaults the application starts and logs `marketMaxTransactions=4000`; a market window of 4,000 trades is
  fetched, one of 4,001 ends `OVER_CAP` with `maxTransactions=4000`.
- After the first start, `automation_variations` holds ten `ACTIVE` rows for `decu-sweep-1`, and `/days`, `/evaluations` and
  `/sweep-runs` answer exactly as before.
- `POST /api/automation/variations/V04/edit` with take profit 80% and stop loss 50% answers 201 with label `V10`,
  `takeProfitBps 8000`, `stopLossBps 5000`, `replacesLabel "V04"`; `GET /variations/V04` shows `state "ARCHIVED"`,
  `archivedAt` set and `replacedByLabel "V10"`.
- The count and content of `automation_sweep_runs` rows with `label = 'V04'` are the same before and after that edit.
- The next captured buy gets a `V10` row and no `V04` row, and becomes `SIMULATED`.
- Repeating the same request answers 200 with `V10`; `automation_variations` still has eleven rows.
- `GET /api/automation/sweep-runs?label=V04&from=…&to=…&page=0&size=50` returns only `V04` runs of buys in the range, each
  with its `mint`, and the right `total`.
- In the console, opening a variation shows its settings, an Archived badge where it applies, and its runs with working
  date filters, paging and a token address that can be copied and opened on Solscan.
- No text in the console about what a label means comes from a hard-coded table.

## Open Questions

1. **Is "the transaction limit" `trenching.automation.market-max-transactions`, and may the request allowance double with
   it?** It is the only 2,000 "transactions" limit in the code (the other 2,000s are chart buckets and markers). Raising it
   needs `recovery.requests × max-step-attempts ≥ 4000`. *Recommendation:* the table above (`recovery.requests 2400`,
   `operation-budget.requests 800`, `backfill-reserve-requests 2600`). A busy buy may then cost about 4,800 provider requests
   and roughly 150 MB instead of 2,400 and 75 MB, and an operation starts only with 2,400 requests free under
   `max-requests-per-24h` (5,000 by default locally). The alternative, `max-step-attempts 4` with nothing else changed, also
   doubles the retries of genuinely failing wallet, market and sweep steps. Please also check, by name only, whether the
   server's environment or the untracked `config/application-local.yml` sets any of these four properties.
2. **Should a new variation be replayed on buys already simulated?** The datasets are kept and replay costs no provider
   request, so it is possible. It needs its own rules (how far back, what a failed catch-up means for an item already
   `SIMULATED`, whether verdicts wait for it). *Recommendation:* not in this plan — forward only now, with the console
   saying so on a new variation; a follow-up plan adds the catch-up without changing anything decided here.
3. **Token address: the run row's own `mint`, rather than `/api/datasets`?** Sweep-run rows and `GET /api/runs` (`mints`)
   already carry it, and the lists show it shortened with a copy button. The datasets endpoint is paged by 20 and cannot be
   asked for a list of ids, so a client join would miss rows. *Recommendation:* use the row's `mint`, and add the Solscan
   link. If a page shows a run without its token today, tell me which one and it joins this plan.
4. **Scoring across an edit.** *Recommendation:* Key Decision 5. The cost: for its first six days a new variation's window
   includes earlier buys as `unscored`, so it usually reads `NO_RESULT` until it has ten signals of its own. The alternative
   clips a variation's window to its life, which changes Rule 24's window definition.
5. **May `V00`, the agreed configuration, be edited?** The day page and the "differs from agreed" text treat it as the
   baseline. *Recommendation:* no — refuse with `VARIATION_BASELINE`; every other variation is editable.

## Delivered

2026-11-22, uncommitted on `develop` until the user commits. `./gradlew build` green (2353 tests). Frontend: lint, format
check and build pass; 691 of 692 tests pass, the one failure being `src/lib/format.test.ts` ("switches to the date after
seven days"), which depends on the machine's locale and is in a file this work did not touch. No new dependency;
`application-local.yml` and `application-prod.yml` untouched.

What shipped, by part:

- **A — cap.** `market-max-transactions` 4000, with `recovery.requests` 2400, `operation-budget.requests` 800 and
  `backfill-reserve-requests` 2600 (Open Question 1); a backfill's minimum request budget is now 2,422.
- **B — stored variations.** Table `automation_variations`, seeded from `sweep-plan` on startup when the plan has no row
  (V00–V09 at `seq` 0–9); sweeps, days and evaluations read the active rows once per tick or request; a day has a row for a
  variation only on the days of its life.
- **C — variation runs.** `GET /api/automation/sweep-runs` takes `planId`, `from` and `to` (400 on `from` not before `to`).
- **D — edits.** `GET /api/automation/variations?state=`, `GET /variations/{label}`, `POST /variations/{label}/edit`
  (201 created, 200 repeat of a `requestId`), archive plus next label in one transaction under the plan's lock.
- **Console.** Variations list and detail pages, the edit form, per-variation runs with date filters and paging, the token
  address with a Solscan link (from the run's own `mint`, Open Question 3).

Deviations from the plan:

- The edit request travels as `VariationEdit` (domain) with the raw `ConsoleConfigRequest`; the service validates it
  (`ConsoleConfigValidator.validateVariation`), the mapper only copies.
- The sweep-run index is `idx_automation_sweep_runs_label_plan (label, plan_id)`, not `idx_automation_sweep_runs_label
  (plan_id, label)`, as the review asked. A local database that got the
  old index keeps it (`update` never drops); it is harmless.
- New refusal 409 `VARIATION_LIMIT_REACHED` when an edit would number a variation at `seq` 500 or more
  (`AutomationLimits.MAX_VARIATIONS_PER_PLAN`); every read of a plan is bounded by the same number.
- Seeding runs from a startup runner (`AutomationVariationStartRunner`, `@Order(0)`), before automation starts; seeded rows
  take `seq` 0–9, so V10 is `seq` 10. A seed entry's label must be `V` plus its two-digit index.
- Edit and seed timestamps are truncated to microseconds, so the 201 answer equals what a later read returns.

Not changed: the day comparator still breaks ties on the label string, so `V100` would sort before `V99`; it matters
only past 100 variations.

Docs updated: `README.md` (variations, endpoints, `sweep-runs` parameters, cap properties, recovery 2,400), ADR
[20261122-stored-editable-variations](../../adr/20261122-stored-editable-variations.md), and a pointer in Decision 6 of
[20261102-automated-capture-and-sweep-evaluation](../../adr/20261102-automated-capture-and-sweep-evaluation.md).
