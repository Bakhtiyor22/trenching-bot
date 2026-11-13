# Retry failed automation buys

> A person can send a failed automation buy back through capture (or the sweep) from the console, one buy at a time or every
> failed buy of a trading day, without touching the database.

Status: Delivered (2026-11-09; units 1–4. Approved 2026-11-07 as written, open questions settled as recommended, amended 2026-11-09)

## Context

Automation drives each discovered buy (an *item*, `automation_items`) through the capture steps and the sweep
(`console.automation`, [AutomationPipelineService](../../src/main/kotlin/com/trenching/bot/console/automation/application/AutomationPipelineService.kt),
[AutomationSweepService](../../src/main/kotlin/com/trenching/bot/console/automation/application/AutomationSweepService.kt)). Each
step gets `max-step-attempts` (2) attempts; after that the item is `FAILED`, a terminal state
([AutomationItemTransitions](../../src/main/kotlin/com/trenching/bot/console/automation/domain/AutomationItemTransitions.kt)
allows nothing out of it). A failed item is unscored and its trading day can be scored only with it missing.

On 2026-11-07 several buys failed for reasons that were fixed afterwards: the self-cancel bug, an unreachable
`api.binance.com` (rates step `INCOMPLETE`), and a market step that ran out of its request allowance (`REQUESTS`). Their
saved evidence (wallet capture, market trades) is kept, so running the failed step again costs little. Today the only way
back is editing `automation_items` by hand, which was done once for five items.

The capture console already resumes an operation's attempt from its saved evidence when a new operation is started on the
same attempt; the automation only has to start a new operation under a request id it has not used before.

## Scope

### In scope

- Backend: retry one failed item; retry every retryable failed item of one trading day; the item response says whether an
  item can be retried and why not.
- The HTTP contract for the frontend, which a separate frontend session implements (Retry on a failed buy, Retry failed
  buys on a day).
- README (Automation section) and this plan's archive note.

### Out of scope

- Retrying `ABANDONED` items (a person closed the session on purpose) or `SKIPPED_NOT_FIRST_BUY` items (the verdict stands).
- Automatic retries beyond `max-step-attempts`, or a retry schedule.
- ~~Retrying items of a backfill that is no longer capturing~~ — now in scope (Domain Rules 6 and 16, user decision of
  2026-11-09): a new capturing backfill cannot overlap a `DONE` one, so retry is the only way back for its failed items.
- Keeping a history of every earlier failure; the log line and the retry count are the audit.
- Any change to capture, replay or scoring semantics.

## Key Decisions

1. **Retry continues the item where it failed.** A capture-step failure goes back to that step; a sweep failure goes back to
   the sweep; a capture deleted from the console (`CAPTURE_NOT_FOUND`) goes back to `CREATE_SESSION`, since nothing is left
   to resume. Rationale: the evidence of the earlier steps is valid and already paid for.
2. **A retry gives the step a fresh round of `max-step-attempts`**, counted from attempt 1. The item records how many times
   it was retried (`retries`). Rejected: raising the attempt cap, which would change every item's behaviour.
3. **Request ids of a retried item carry the retry count**: `auto-<step>-<item24>-r<retries>-<attempt>` (and the cancel id
   likewise) once `retries > 0`; an item never retried keeps today's `auto-<step>-<item24>-<attempt>`. Rationale: the
   capture console returns the old operation for a reused request id, so a retry must use new ids, and items in flight
   when this ships must keep theirs. The longest id (`auto-create_session-<24>-r999-99`) is 52 characters, within the
   80-character request id rule.
4. **The session's state does not change.** An item of a `CLOSED` session is captured like the phase 0 backlog already is
   (precedent: `AutomationPipelineServiceTest`, "an item of a closed session ... its session stays closed"). The day turns
   `PENDING` again by itself, since [DayFacts](../../src/main/kotlin/com/trenching/bot/console/automation/domain/DayScoring.kt)
   requires every item terminal.
5. **Retry bypasses nothing.** The request cap, the market delay, one operation at a time and the live-first priority all
   apply as for any item (plan 20261102 Rules 15–19, 40).
6. **Idempotent by request id and by state.** The request carries a `requestId`; a repeat of the last retry's request id
   returns the item as it is. A different request id on an item that is not `FAILED` is refused.
7. **The endpoints stay open to remote console users** like the other automation controls (decision of 2026-11-03 on
   Tailscale access: others may also run automation); `LocalAccessFilter` is unchanged.

## Module Contracts

All inside `console.automation`; no other module's contract changes. No contract break.

- `POST /api/automation/items/{id}/retry`, body `AutomationRetryRequest(requestId: String?)` →
  `200 AutomationItemDetailResponse`.
- `POST /api/automation/days/{date}/retry`, body `AutomationRetryRequest` → `200 AutomationDayRetryResponse(day: LocalDate,
  retried: List<String>, unchanged: List<String>, refused: Map<String, String>)` (`unchanged` added 2026-11-09 after review; item id → refusal code), `date` an ISO trading-day date.
- `AutomationItemResponse` gains `retries: Int` and `retryRefusal: String?` (null when the item can be retried now; else
  one of the codes below). Additive.
- `AutomationRequests.capture(step, itemId, attempt, retries)` and `cancel(...)` gain `retries`; the pipeline and the
  cancellation pass the item's count.
- `AutomationItemProgress.retried(now): AutomationItemProgress` — the progress after a retry (rules below), and
  `AutomationItemProgress.retryRefusal(...)` / a small `AutomationRetryRule` deciding refusals from the item, its backfill's
  state and `capture-enabled`.
- `AutomationItemTransitions`: `FAILED → CAPTURING` and `FAILED → CAPTURED` become allowed **only** through the retry path
  (`require` stays the single check; the pipeline never produces them).

Refusal codes (409 `CONFLICT`, body `{code, message, fields}` as the other automation errors):

| Code | When |
|---|---|
| `ITEM_NOT_FAILED` | The item is not `FAILED` (and the request id is not its last retry's). |
| `RETRY_NOT_ALLOWED` | Failed with `BACKFILL_BUDGET` or `MISSING_BLOCK_TIME`. `fields.failureCode` names which. |
| `BACKFILL_NOT_CAPTURING` | A backfill's item whose backfill is still `QUEUED` or `SCANNING` (amended 2026-11-09). |
| `CAPTURE_DISABLED` | `trenching.automation.capture-enabled` is false. |

`404 AUTOMATION_ITEM_NOT_FOUND` for an unknown item, `400` for a missing or malformed `requestId` or date.

## Domain Rules

1. Only a `FAILED` item can be retried. `ABANDONED`, `SKIPPED_NOT_FIRST_BUY`, `SIMULATED` and every non-terminal state are
   refused with `ITEM_NOT_FAILED`.
2. A failure in a capture step (`WALLET` … `REGISTER`, any code other than those in rule 5) retries that step:
   state `CAPTURING`, same `step`, `step_attempt` 1, `operation_id` null, `next_action_at` = now, failure fields cleared,
   `retries` + 1. The capture session and dataset fields are kept.
3. `CAPTURE_NOT_FOUND` retries from `CREATE_SESSION`: as rule 2, with `step` `CREATE_SESSION` and `capture_session_id`
   cleared, so a new capture session is created under the retried request id.
4. `SWEEP_FAILED` retries the sweep: state `CAPTURED`, step `SWEEP`, `step_attempt` 1, `next_action_at` = now, failure
   fields cleared, `retries` + 1. Sweep rows already stored stay; only entries without a row run (existing sweep rule).
5. `BACKFILL_BUDGET` and `MISSING_BLOCK_TIME` are refused with `RETRY_NOT_ALLOWED`.
6. (amended 2026-11-09, user decision) A backfill's item can be retried whether its backfill is `CAPTURING`,
   `STOPPED_BUDGET`, `DONE` or `CANCELLED`; only a backfill still `QUEUED` or `SCANNING` refuses with
   `BACKFILL_NOT_CAPTURING` (none of its items has started capture yet). The backfill's state does not change: a retry never
   reopens an ended backfill, so its coverage and the live floor (`covered_from`) stay as they are.
7. (amended 2026-11-09 after review) With `capture-enabled` false, an item that would otherwise be retryable is refused with
   `CAPTURE_DISABLED`; the permanent refusals (`ITEM_NOT_FAILED`, `RETRY_NOT_ALLOWED`) and `BACKFILL_NOT_CAPTURING` win, in the
   order of the table above.
8. A retried market step still starts no earlier than block time + `market-delay`, and every acquisition step still waits
   for room under the request cap (`BLOCKED_BUDGET`).
9. After a retry, attempts run 1..`max-step-attempts` again; when they are spent the item is `FAILED` again and can be
   retried again (each retry is a deliberate action).
10. Request ids: an item with `retries` = 0 uses `auto-<step>-<item24>-<attempt>`; with `retries` = n > 0,
    `auto-<step>-<item24>-r<n>-<attempt>`. The same item, step, retries and attempt always give the same id, so a restart
    during a retried step finds the same operation.
11. Idempotency: the retry stores its `requestId` on the item (`retry_request_id`). A request whose id equals the stored one
    returns the item unchanged (200), whatever its state now. The write checks the item's `@Version`; a concurrent change
    (pipeline, manual close) makes the retry fail with `ITEM_NOT_FAILED` after re-reading, never a lost update.
    A retry that lost twice is answered from a fresh read (unchanged for its own request id, else a refusal), never a conflict code.
    A backfill's item reads its backfill with a forced version increment, so a backfill that ends (`settle`) between the read and
    the commit fails one of the two, and the retry is read again (amended 2026-11-09 after review).
12. Day retry: retries, in one transaction per item and oldest buy first, every `FAILED` item of the trading day that rule
    1–7 allow; the others are listed in `refused` with their code. Its per-item request id is
    `<requestId>-<first 24 hex of the item id>`; when that passes 80 characters, the first 32 hex of the SHA-256 of `requestId` takes
    the place of `requestId` (amended 2026-11-09 after review), so repeating the day request is idempotent per item. An item whose
    last retry already has that id is reported in `unchanged`, not `retried`. A day with no failed item returns empty lists.
13. Each retry logs, after its transaction committed (amended 2026-11-09 after review), `automation item retried itemId=… step=… previousFailure=… retries=… requestId=…` at INFO. No secret,
    URL or PII is logged.
14. Times stored by the retry are truncated to microseconds, like the pipeline's tick time (fix of 2026-11-07).
15. No number is calculated; no rounding applies.
16. (added 2026-11-09, user decision) The pipeline and the sweep take a retried item (`retries` > 0) of an ended backfill
    (`DONE` or `CANCELLED`) like the items of a backfill still capturing: in the backfill tier, after live items and the
    phase 0 backlog, oldest buy first. A retried backfill item, of any backfill, is not limited by its backfill's
    `requestBudget` (the person chose the spend; the budget belonged to the run that ended), so it never fails with
    `BACKFILL_BUDGET`. It still needs room under the cap less `backfill-reserve-requests`, as every backfill item does, and its
    `requests_used` still counts in its backfill's spend. An ended backfill is never settled again.

## Data Model

`automation_items` gains two additive columns, declared on `AutomationItemEntity`:

| Column | Type | Rule |
|---|---|---|
| `retries` | `integer not null default 0` | Times a person retried the item. |
| `retry_request_id` | `varchar(80)` null | The request id of the last retry. |

`ddl-auto: update` adds them locally; the `default 0` must be part of the column definition so existing rows get 0.
Production, before deploying:

```sql
alter table automation_items add column retries integer not null default 0;
alter table automation_items add column retry_request_id varchar(80);
```

Saved as `docs/schema/V20261107120000__automation_item_retry.sql`. Nothing destructive; rollback is dropping the two
columns. No new index: the day retry reads items through the existing `idx_automation_items_session` per session of the day
(at most `MAX_ITEMS_PER_SESSION`).

## Transactions & Integration

- One short transaction per item retry (read item, its backfill's state, check, write). No provider call; the retry only
  changes the item, and the next pipeline or sweep tick does the work.
- The day retry reads the day's sessions and failed items read-only, then retries each item in its own transaction, so one
  refused or contended item does not undo the others.
- No new configuration property.

## What To Implement

1. **Retry rules and request ids** (domain): `retries` on the progress and the entity, `retried(now)`, the refusal rule, the
   two allowed transitions, request ids with the retry count; pipeline and cancellation pass `retries`. Schema SQL file.
2. **Retry service and endpoints**: `AutomationRetryService` (+ its transaction), the item and day endpoints, error
   mapping, `retries` and `retryRefusal` in the item response. Depends on 1.
3. **Docs**: README Automation section (retry, refusals, idempotency), `IMPLEMENTATION_STATUS.md` entry. With 2.
4. **Frontend (separate session, after 2)**: a Retry button on a failed buy (Days page buy table and the selected-buy panel)
   shown when `retryRefusal` is null, disabled with the refusal in plain words otherwise; "Retry failed buys" on a day with
   failed buys; a fresh `requestId` per click; on success refresh the day, session and item queries.

1 and 2 touch the same files in places (`AutomationRequests`, the item mapper), so they run in order.

## Verification

Test first.

- `AutomationItemProgressTest`: rules 2, 3, 4 (fields after `retried`), rule 9 (attempts start at 1 again).
- `AutomationRequestsTest` (new or existing): rule 10, both id forms, 80-character limit with the longest step name.
- `AutomationRetryServiceTest` (integration, `AutomationDatabaseTest`): rules 1, 5, 6, 7 refusals; rule 11 repeat returns
  unchanged and a different id on a capturing item is refused; a retried `RATES` failure starts a new operation under
  `auto-rates-<id>-r1-1` on the same rates attempt and the item ends `CAPTURED`; `CAPTURE_NOT_FOUND` creates a new capture
  session; a retried market step waits for the cap when it has no room; a retried item of a `CLOSED` session is captured
  and the session stays `CLOSED`; day retry retries the allowed items and lists the refused ones.
- `AutomationSweepServiceTest`: a retried `SWEEP_FAILED` item runs only the entries without a row and becomes `SIMULATED`.
- `DayVerdictTest` / `AutomationDayServiceTest`: a complete day turns `PENDING` while a retried item is in capture.
- `AutomationRepositoryIntegrationTest`: an existing row gets `retries` 0 after the column is added.
- `AutomationControllerTest`: 200, 400, 404 and each 409 code; response carries `retries` and `retryRefusal`.
- `AutomationPipelineServiceTest`: unchanged ids for items never retried (regression).

```bash
./gradlew test --tests "com.trenching.bot.console.automation.*"
./gradlew build
```

The five Windows-only failures known before this work are reported as such, not as regressions.

## Documentation Sync

- README → Automation: how to retry, what cannot be retried and why, that retries spend requests under the same cap.
- `docs/schema/V20261107120000__automation_item_retry.sql`.
- No ADR: no simulation fidelity or reproducibility decision changes.

## Acceptance Criteria

1. `POST /api/automation/items/{id}/retry` on an item `FAILED` at `RATES` returns 200 with `state` `CAPTURING`,
   `step` `RATES`, `stepAttempt` 1, `retries` 1, `failureCode` null; the next ticks start an operation with request id
   `auto-rates-<item24>-r1-1` and the item reaches `SIMULATED` without a new market capture.
2. Repeating that request with the same `requestId` returns 200 and changes nothing; another `requestId` returns 409
   `ITEM_NOT_FAILED` while the item is in capture.
3. An item failed with `BACKFILL_BUDGET` returns 409 `RETRY_NOT_ALLOWED`; an `ABANDONED` item returns 409
   `ITEM_NOT_FAILED`; with capture off, 409 `CAPTURE_DISABLED`.
4. `GET /api/automation/items/{id}` shows `retryRefusal` null for a retryable failed item and the code otherwise.
5. `POST /api/automation/days/2026-11-07/retry` retries every retryable failed item of that day and lists the rest by code.
6. The day of a retried item reads `PENDING` until the item is terminal again; its session state is unchanged.
7. The log has one `automation item retried` line per retried item.
8. Existing items in flight keep their request ids after the change is deployed.

## Open Questions

1. **Retry a whole day as well as single buys?** *Recommendation:* yes, as planned. A day with several failures from one
   cause (like the Binance outage) is the common case, and the rules are the same per item.
2. **Allow retrying items of a manually closed session?** Their `FAILED` items failed before the close; the close only
   abandoned the rest. *Recommendation:* yes; the person asks for it explicitly, and the session state does not change.
3. **Keep the previous failure for display after a retry?** *Recommendation:* no for now; the log line and `retries` show
   that it happened. Add a `last_failure_code` column only if the console needs to show it.

## Delivered

2026-11-09, units 1–4.

- **Backend (units 1–3):** `POST /api/automation/items/{id}/retry` and `POST /api/automation/days/{date}/retry`, the
  `retries` and `retry_request_id` columns, `retryRefusal` on every item response, and request ids carrying `r<retries>`.
  Production applies `docs/schema/V20261107120000__automation_item_retry.sql` by hand.
- **Frontend (unit 4):** a Retry button per failed buy on the Automation day page, with the refusal reason when a buy
  cannot be retried, and a confirmed "Retry failed buys" for the whole day that reports what was retried, unchanged and
  refused. Screenshots in `frontend/qa/20261109-automation-retry/`.
- **Differences from the first approved version:** amended 2026-11-09 by the user's decision to allow retrying a backfill's
  failed buys once the backfill is `CAPTURING`, `STOPPED_BUDGET`, `DONE` or `CANCELLED` (rules 6 and 16); review
  fixes tightened rules 7, 11, 12 and 13. The open questions were settled as recommended (no `last_failure_code` column).
- **Docs:** README Automation section (Retry bullet and endpoint rows), `docs/IMPLEMENTATION_STATUS.md`.
- **Verified:** 402 automation backend tests; frontend tests, lint and build; the day page checked against mocks at
  1440 and 375 px. Not yet tried against the running backend.
