# Variation versions, create and archive, a variation filter on Runs, and finishing the 22 Nov plan

> An edit of V03 is shown as V03.1, then V03.2; a new variation takes the next base number; archived variations stay in
> view and can be archived on their own; the Runs list filters by variation; and the Telegram and backup-RPC work is
> merged into `develop`.

Status: Delivered (2026-11-26)

## Context

**The 22 Nov plan** ([`20261122-telegram-alerts-and-backup-rpc-account.md`](20261122-telegram-alerts-and-backup-rpc-account.md),
on its branch) is mostly built but not merged:

- B1, B2, C1 and C2 (the `notification` outbox and send-only Telegram channel, the buy and day messages, RPC failover,
  the account directory, Helius balances and `GET /api/solana/accounts`) are committed as `f53a122` on
  `feature/telegram-alerts-backup-rpc`. That branch was cut from `219fc93`, and `develop` has 8 commits since.
- D1 (the console part) is built in the worktree `.claude/worktrees/telegram-alerts-backup-rpc` but not committed:
  - 25 changed files and 9 new ones;
  - the Solana page's accounts section, the `RPC_CREDITS` hold, and the new codes and usage labels;
  - 25 screenshots in `frontend/qa/20261122-rpc-accounts/`.
- A1 is done. A2 and A3 are the user's server steps. Open Questions 1–4 need the real services.
- A dry-run merge of `f53a122` into `develop` conflicts only in `README.md`. Six of the uncommitted D1 files were also
  changed on `develop`:
  - `api/queries.ts`, `api/types.ts`, `features/automation/AutomationShared.tsx`;
  - `lib/format.test.ts`, `mocks/handlers.ts`, `mocks/handlers.test.ts`.
- The untracked copy of that plan in the main checkout is an older version: the branch copy contains every one of its
  lines. Git will refuse the merge while that copy is there.

**Variations today** are owned by `console.automation`:

- **Labels.** A variation's `label` is its key: `V` plus its sequence number (`VariationEditRules.labelOf`).
- **Edits.** An edit archives the variation and stores a replacement under the next number
  (`AutomationVariationTransaction.edit`), with `replacesLabel` and `replacedByLabel` linking the two. So V03 → V10 → V12.
- **Where the label is stored:**
  - `automation_variations`: `label`, `replaces_label`, `replaced_by_label`, and the id, which is the SHA-256 of
    `plan|label`;
  - `automation_sweep_runs`: `label`, and an id that is the SHA-256 of `item|plan|label`;
  - the JSON `detail` of each `automation_item_events` sweep entry;
  - every API response and console URL;
  - the Telegram messages on the branch.
- **Nothing is ever deleted.** An archived variation keeps its row, its sweep runs and its days.
- **What makes them look gone:**
  - the Variations page lists only the active ones until "Show archived" is on;
  - the run chips on the Runs list don't say a variation is archived.
- **No create or archive action.** There is no way to create a new variation, or to archive one without editing it.

**The real data**, read on 2026-11-26 from the server's console API through the tunnel (read-only):

- Plan `decu-sweep-1` has 16 variations. V00–V09 are seeded.
- Six edits made today between 11:30 and 11:50 UTC created V10–V15:

  | Edit chain | Name under this plan |
  |---|---|
  | V03 → V10 → V12 | V03.1, V03.2 |
  | V04 → V11 | V04.1 |
  | V09 → V13 | V09.1 |
  | V01 → V14 | V01.1 |
  | V07 → V15 | V07.1 |

- V10–V15 had no sweep runs yet when I read them. Sweeping continues, so by deploy time they will have some.

**Runs and variations:**

- A run is identified by (engine version, dataset, configuration hash), so the same settings on the same buy are one run.
- The only link from a run to a variation is `automation_sweep_runs`.
- `ReplayRunner.configurationHash` depends on the settings and the engine's rules only, never on a dataset. All 100 of
  V03's sweep runs that I sampled share one hash.

**URLs with a dot.** `ConsoleResourceResolver` refuses the page fallback for any path that contains a dot. So reloading
`/console/automation/variations/V03.1` would answer 404 today.

## Scope

### In scope

- **A.** Finish the 22 Nov plan:
  - check and commit D1 on its branch;
  - merge the branch into `develop` locally;
  - update and archive that plan.
- **B. Version names.** Every variation has a name derived from its edit line, and new edits are stored under it.
- **C.** A create action (a new base number) and an archive action (no replacement).
- **D.** A `variation` filter on `GET /api/runs`.
- **E. Console:**
  - names everywhere a label is shown;
  - archived variations shown and marked;
  - the New variation and Archive actions;
  - the Variation filter on the Runs page.
- **F.** Documents.

### Out of scope

- Renaming any stored label, sweep run, id or history event.
- Un-archiving a variation.
- Replaying a new variation on buys already simulated (22 Nov variations plan, Open Question 2).
- The order of `GET /api/automation/evaluations`, which stays by sequence.
- The 22 Nov plan's server steps (A2, A3) and its real-service checks (Open Questions 1–4). They stay the user's.
- Pushing. The user pushes `develop`.

## Key Decisions

1. **A name is derived from the edit line, never stored.**
   - A variation that replaces nothing is named by its own label (`V03`).
   - A variation that replaces another is named by its line's first label, a dot, and the number of edits since that
     first variation (`V03.1`, `V03.2`).
   - `replaces_label` is written once (`updatable = false`), so a name never changes.
   - All of a plan's rows (at most 500) are always read together, so the name costs no extra query.
   - The six existing edits get their names with no write: V10 is V03.1, V12 is V03.2, and so on, as in the table above.
2. **From now on a new variation's label is its name.**
   - An edit of V03.2 (stored as V12) is stored as `V03.3`. So the label equals the name for every row except the six
     existing edits.
   - Those six keep V10–V15 as their permanent keys, because ids, sweep runs, item history and links already hold them.
   - The API keeps `label` as the key and adds `name`. The console shows the name and links by the label.
   - *Rejected: renaming the six in place.* Their SHA-256 ids, their sweep runs (and those ids) and their item history
     would all have to be rewritten by hand-applied SQL, with automation stopped so no sweep writes an old label
     between the SQL and the deploy.
   - *Rejected: keeping `V` plus a sequence number as a hidden key with a separate name.* Every edit would use up a
     number, so new base numbers would keep jumping.
3. **A new variation takes the next free base number.** The number is one more than the highest number any label of
   the plan starts with, padded to two digits.
   - With today's data that is **V16**, because 10–15 are the existing edits' labels.
   - This gap happens once. Edits no longer use up base numbers, so later new variations are V17, V18, and so on.
4. **Create works like an edit without a target**:
   - `POST /api/automation/variations` with `{requestId, config}`;
   - the same complete-configuration validation, duplicate check against the active variations, 500-row limit and
     request-id repeat;
   - one short transaction under the plan's lock;
   - active from now.
5. **Archive is a state change, never a delete.**
   - `POST /api/automation/variations/{label}/archive` sets `ARCHIVED` and `archivedAt` and leaves `replacedByLabel`
     null.
   - Its sweep runs, days and runs stay as they are.
   - V00 is refused, as for an edit. Since V00 stays active, the plan always has at least one active variation and
     startup's non-empty check holds.
   - It needs no request id: archiving an archived variation changes nothing and answers with it.
6. **The Runs filter matches the configuration hashes the variation ran with.**
   - `GET /api/runs?variation=V03`.
   - The console reads the distinct `configuration_hash` values of that variation's sweep runs. There are a handful,
     one per engine rule set.
   - It passes them to the run search as an exact `configuration_hash in (…)` condition.
   - This is bounded, and it keeps `replay` ignorant of automation's tables.
   - *Rejected: passing run ids.* That is unbounded: V00 gains about 24 runs a day forever.
   - *Rejected: a join on `automation_sweep_runs` inside replay's SQL.* That couples replay to automation's tables.
   - The cost: a run with exactly the same settings, from a manual run or from another variation with identical
     settings, is listed too. Its chips still say which variations swept it (Open Question 2).
7. **A dot no longer blocks the page fallback.** A path is a file only when its last segment ends in a dot followed by
   a letter-led extension (`.js`, `.css`, `.ico`, `.woff2`). So `V03.12` is a page route, and a missing asset still
   answers 404.
8. **The branch is merged, not rebuilt.**
   - D1 is checked against its plan and committed on `feature/telegram-alerts-backup-rpc`.
   - The older untracked plan copy is removed from the main checkout.
   - The branch is merged into `develop` locally, keeping both sides in the README and the six frontend files.
   - Nothing is pushed. Approving this plan approves these git steps.

## Module Contracts

**`console.automation` (HTTP, additive except where marked):**

- **`AutomationVariationResponse`** gains `name: String`.
- **List order (changed).** `GET /api/automation/variations` lists by name: by line number, then revision (V00, V01,
  V01.1, V02, V03, V03.1, V03.2, …). It used to list by sequence. This is an **order change**, which the console
  follows.
- **Create.** `POST /api/automation/variations`, body `AutomationVariationCreateRequest(requestId: String?, config:
  ConfigRequest?)` in the edit request's shape:
  - 201 with the new variation;
  - 200 for a repeat of the same `requestId`;
  - the edit's 400 codes;
  - 409 `VARIATION_DUPLICATE`, `VARIATION_LIMIT_REACHED` or `AUTOMATION_CONFLICT`.
- **Archive.** `POST /api/automation/variations/{label}/archive`, no body:
  - 200 with the variation, archived (also when it already was);
  - 404 `AUTOMATION_VARIATION_NOT_FOUND`;
  - 409 `VARIATION_BASELINE`.
- **Edit.** `POST /api/automation/variations/{label}/edit` is unchanged, except that the new variation's `label` is
  dotted (`V03.3`).
- **Dotted labels in paths.** Every `{label}` path accepts one.

**`console.automation.domain`:**

- **`VariationNames`** (pure):
  - `of(variations: List<Variation>): Map<String, VariationName>`;
  - `VariationName(line: String, revision: Int)` with `text` and a `ORDER` comparator;
  - `editLabel(target: VariationName): String`;
  - `nextBaseLabel(labels: Collection<String>): String`.
- **`VariationView`** gains `name: VariationName`.
- **Removed.** `VariationEditRules.seqOf` goes, since its only use was ordering. `labelOf` stays for seeding.

**`console.domain`:**

- **`RunVariationLookup`** (implemented by `AutomationRunVariationLookup`, `ABSENT` without the automation console):
  - It stops being a `fun interface`.
  - It keeps `labels(runIds)`, which now orders each run's labels by name.
  - It gains `configurationHashes(label: String): Set<String>?`: null when the plan has no such variation, empty when it
    has not run yet.

**`replay` (public contract, additive):**

- **`RunSearchQuery`** gains `configurationHashes: Set<String>?`: null means any, and an empty set matches no run.
  Existing callers pass null.

**`console` HTTP:**

- **`GET /api/runs`** gains `variation` (a label). An unknown label, or no automation console, is 400 `INVALID_REQUEST`
  with field `variation`.

**`notification` / automation messages (branch code, after the merge):** `BuyResultMessage` and `DayVerdictMessage` print
names.

No contract is broken for callers outside this repository. The list-order change and the dotted labels are the two
behaviour changes the console has to follow, and it ships in the same push.

## Domain Rules

1. **Names.**
   - A variation's revision is 0 when `replacesLabel` is null; otherwise it is the replaced variation's revision plus
     one.
   - Its line is its own label at revision 0; otherwise it is the replaced variation's line.
   - Its name is the line at revision 0; otherwise the line, `.` and the revision.
2. **The six existing edits:**

   | Label | Name |
   |---|---|
   | V10 | V03.1 |
   | V12 | V03.2 |
   | V11 | V04.1 |
   | V13 | V09.1 |
   | V14 | V01.1 |
   | V15 | V07.1 |

   V00–V09 are named by their own labels.
3. **A broken line fails the read.** If a `replacesLabel` names no row of the plan, or the replaces chain loops, the read
   fails with a message naming the label. The API cannot produce either, since both rows are written in one
   transaction, so this is never papered over with a made-up name.
4. **An edit's label.** An edit of target T stores the label `line(T).(revision(T)+1)`. Only the active head of a line
   can be edited, and the edit archives it under the plan's lock, so a line never forks. The database guards this with
   `uq_automation_variations_label`, and a collision answers 409 `AUTOMATION_CONFLICT` through the existing path.
5. **A new variation's label** is `V` plus (1 + the highest number any label of the plan starts with), padded to 2
   digits.
   - The number is the digits after `V`, up to the dot.
   - For V00–V15 the result is V16. For V00–V09 alone it is V10.
6. **Create refuses:**
   - a blank or over-64-character request id, with 400;
   - an invalid configuration, with the edit's codes;
   - the configuration of an active variation, with 409 `VARIATION_DUPLICATE` naming that variation;
   - a 501st row, with 409 `VARIATION_LIMIT_REACHED`.

   A repeat of a stored request id answers that variation with 200 and writes nothing. The new variation is `ACTIVE`,
   `activeFrom = now`, and its `replacesLabel` is null.
7. **Archive.**
   - V00 is refused with 409 `VARIATION_BASELINE`.
   - An archived variation is answered unchanged with 200.
   - Otherwise the variation becomes `ARCHIVED` with `archivedAt = now`, and no row is deleted.
   - A sweep tick that already read the active list finishes its runs, as after an edit (22 Nov variations plan, Key
     Decision 4).
   - The variation's days end on the day of `archivedAt` (`VariationLife`).
8. **Times.** `now` for create and archive is the injected `SimulationClock` truncated to microseconds, as for an edit.
   No other clock or random id is used.
9. **The Runs filter.**
   - `variation=L` returns the runs whose `configuration_hash` is one of the distinct hashes of L's sweep runs, under
     every other filter. Its `statusCounts` and `totals` use the same set.
   - A variation that has not run yet gives an empty list with zero counts.
   - An archived variation filters like an active one.
10. **Chips.** The `variations` of a run summary are ordered by name, archived ones included.
11. **Paths.** A request path whose last segment ends in `.` plus a letter and then letters or digits is a file request.
    Every other path falls back to the console page.

Numbers: no new calculated value. P&L sums keep `DecimalPolicy.SCALE` with `RoundingMode.UNNECESSARY`, as today.

## Data Model

**Names, create, archive:** no new table or column.

- Names are derived.
- Create writes an ordinary `automation_variations` row with its `request_id`.
- Archive updates `state` and `archived_at`, which are already updatable.
- Labels of up to 16 characters hold `V999.999`.

**Runs filter:** one new index on `SimulationRunEntity`, `idx_simulation_runs_configuration` (`configuration_hash`,
`created_at`). It is additive, and `update` creates it in every profile.

**Merge:**

- Two new tables from the 22 Nov plan, `notification_outbox` and `rpc_accounts`, both additive.
- New enum values on `api_usage_buckets.provider` and `.task`. The existing startup schema repair widens their checks.

Nothing is dropped, renamed, narrowed or backfilled. No SQL is applied by hand, and no local reset is needed.

## Transactions & Integration

- **Create and archive** are one short transaction each in `AutomationVariationTransaction`. Each takes the plan's
  active-row write lock first and re-checks under it, as an edit does. There is no remote call.
- **Name derivation** happens inside the existing read transactions. `findView(label)` reads the plan's rows once
  instead of the row and its replaced one.
- **Run search.** `RunSearchConsoleService` reads the variation's hashes (one indexed read on `automation_sweep_runs` by
  label and plan), then runs the existing search. Both are read-only, and hashes only ever get added, so reading them
  first never hides a run.
- No provider is called and no configuration property is added. The 22 Nov plan's properties and environment variables
  arrive with the merge and are already documented on its branch.

## What To Implement

1. **The 22 Nov plan merged.**
   - Run D1's frontend test, lint and build in the worktree and check D1 against that plan's D1 text; fix anything
     missing.
   - Commit D1 on the branch.
   - Remove the older untracked plan copy here, merge the branch into `develop` and resolve the README and the six
     frontend files, keeping both sides.
   - `./gradlew build` and the frontend checks pass on the merge.
   - Update that plan's Delivery status: D1 done and merged; A2, A3 and Open Questions 1–4 left to the user with the
     environment variable names. Then move it to `archive/`.
2. **Version names (backend).**
   - `VariationNames` and the `name` on views and responses.
   - The list ordered by name, the edit's dotted label, and the chips ordered by name.
   - The resolver's dot rule.
   - Names in the two Telegram messages.
   - Depends on 1.
3. **Create and archive (backend).** The two endpoints, request and response DTOs, refusals and transactions. Depends
   on 2, since it uses the naming rule and the same transaction class.
4. **The Runs variation filter (backend).**
   - `RunSearchQuery.configurationHashes` and its SQL.
   - The index.
   - `RunVariationLookup.configurationHashes`.
   - The `variation` parameter.
   - Depends on 2, which edits `AutomationRunVariationLookup` too.
5. **Console**, after 2–4 (in its own frontend session as the project's frontend rule says):
   - **Names.** The variation book exposes `name` and state. Every place that prints a label shows the name: chips,
     sweep runs, days, scoreboard, trace and the command palette.
   - **Variations page.** Tabs for All, Active and Archived, with counts and All by default. Archived rows are dimmed
     with the Archived chip.
   - **New variation.** A form that reuses the edit form, prefilled with the agreed settings, with a "copy from"
     option.
   - **Archive.** An Archive button on an active variation's page, with a confirmation that says its runs stay.
   - **Edit form copy.** It says "saves as V03.3".
   - **Runs page.** A Variation select listing every variation by name, archived ones marked "(archived)", kept in the
     URL like the other filters. The chips show names and an archived mark.
   - **QA.** Screenshots at 1440 and 375 px.
6. **Documents.** See Documentation Sync.

## Verification

**Backend (test first):**

- **`VariationNamesTest`:**
  - every seeded name;
  - the server's six chains give the names in Rule 2;
  - an edit of V12 gives `V03.3`;
  - the next base for V00–V15 is V16, and for V00–V09 it is V10;
  - a missing replaced row and a loop each fail naming the label;
  - `ORDER` sorts V00, V01, V01.1, V02, V03, V03.1, V03.2, V16.
- **`AutomationVariationEditDatabaseTest`** (Testcontainers):
  - an edit stores the dotted label;
  - an edit of a legacy-labelled row stores `V03.3`;
  - a repeat request id answers 200;
  - the list comes in name order and carries `name`;
  - `GET /variations/V03.1` answers 200.
- **`AutomationVariationCreateArchiveDatabaseTest`:**
  - create gives 201, then 200 on a repeat, 409 for a duplicate, 409 at the limit and 400 for invalid settings;
  - the label is V16 after V00–V15;
  - archive gives 200, the same again, 409 for V00 and 404 for an unknown label;
  - the archived row is still listed, and its sweep runs and item events are unchanged in count and content.
- **`ConsoleResourceResolverTest`:**
  - `automation/variations/V03.1` resolves to `index.html`;
  - a missing `assets/x.js` and `favicon.ico` resolve to nothing.
- **`RunSearchSqlTest` and the run search integration test:**
  - a hash set restricts the page, `statusCounts` and `totals`;
  - an empty set gives no run;
  - it combines with status and mint.
- **`RunSearchConsoleServiceTest`:** `variation` resolves through the port; an unknown label is 400 `variation`;
  `ABSENT` is 400.
- **`AutomationRunVariationLookupTest`:** distinct hashes, null for an unknown label, labels ordered by name.
- **Telegram message tests** expect names.

**Frontend:** Vitest for the book's names, the Variations tabs, the create form, the archive confirmation, the Runs
`variation` parameter round trip, and chips with names and the archived mark. Then lint and build, and a browser check
on mocks.

**Commands:**

```
export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10" && ./gradlew build
```

with Docker running, and `npm test`, `npm run lint` and `npm run build` in `frontend/`.

No live provider is called. The only server read was the variations listing above, through the console API.

## Documentation Sync

- **`README.md`:**
  - the Variations paragraph: names, dotted labels, the next base, create, archive;
  - the rows for the variation endpoints, plus the two new ones;
  - the `GET /api/runs` row: `variation`, chips ordered by name;
  - the schema paragraph: the new index;
  - and everything the merge brings.
- **New ADR `docs/adr/20261126-variation-version-names.md`** covering Key Decisions 1–3 and 6. It supersedes the "next
  sequence number, never reused" naming of `20261122-stored-editable-variations.md`, which gets a pointer to it.
- **`frontend/README.md`:** the new actions and the filter.
- **This plan:** a Delivered note, then `archive/`. The 22 Nov plan as in unit 1.

## Acceptance Criteria

1. **Names.** `GET /api/automation/variations` on the server data lists `V03.1` for label V10 and `V03.2` for V12, with
   no row written to produce them.
2. **Edits.** Editing V12 stores a row labelled `V03.3` with `replacesLabel` V12, and V12 reads `replacedByLabel` V03.3.
3. **Create.** On that data, `POST /api/automation/variations` stores `V16`, which is `ACTIVE` with a null
   `replacesLabel`.
4. **Archive.** `POST /api/automation/variations/V05/archive` leaves V05's row with state `ARCHIVED`. Its sweep-run
   count is unchanged, and it is still listed and filterable.
5. **Runs filter.** `GET /api/runs?variation=V03` lists only runs with V03's configuration hash, and its `totals` add up
   only those runs.
6. **Reloading** `/console/automation/variations/V03.1` shows the page; `/console/assets/missing.js` is 404.
7. **Telegram.** A buy message names variations by name (`V03.2 …`).
8. **Merge.** `develop` holds the merged branch plus D1; `./gradlew build` and the frontend checks pass. No SQL is needed
   before the push.

## Open Questions

1. **The one-time gap: the next new variation is V16, not V10.** Recommendation: accept it. Avoiding it means renaming
   V10–V15 in place by hand-applied SQL, with automation stopped, rewriting their ids, sweep runs and history.
2. **The Runs filter lists any run with the same settings.** This includes a manual run with identical settings, or
   another variation with identical settings (possible after an edit back to archived settings). Recommendation: accept
   it, and say "runs with this variation's settings" in the filter's hint. Matching exactly needs either unbounded run
   ids or replay's SQL reading automation's table.
3. **A standalone Archive action** is added because today the only way to stop a variation is to edit it.
   Recommendation: keep it, with V00 refused.
4. **The Variations page shows all variations by default**, archived ones dimmed. Recommendation: yes. Hiding them is
   what makes them look deleted.
5. **Archiving the 22 Nov plan** while its server steps are still open. Recommendation: archive it with those steps
   listed in its Delivered note, so `docs/plans/` holds only work in progress.

## Delivered

**2026-11-26.** Every part of the plan was built. Unit 1 is committed. Units 2–6 are in the working tree, uncommitted.

### What shipped

1. **The 22 Nov plan merged.**
   - D1 was checked (tests, lint, build, and against its plan text) and committed as `3e4e66f`.
   - The older untracked plan copy was removed.
   - `feature/telegram-alerts-backup-rpc` was merged into `develop` as `98faacb`. The README was resolved word by word, keeping both sides; `AutomationShared.tsx` kept both imports; and `AutomationNotificationsTest` passes the `seq` that `develop` added to `DayVerdict`.
   - That plan is archived with its Delivered note and the user's remaining server steps.
2. **Version names.**
   - `VariationNames` derives the names. `name` is on views and responses, and the list is ordered by name.
   - Edits store dotted labels through `VariationNames.editLabel`.
   - Run chips are ordered by name, `seqOf` is removed, and the two Telegram messages print names.
   - `ConsoleResourceResolver` treats a path as a file only when it ends in a letter-led extension.
3. **Create and archive.**
   - `POST /api/automation/variations` stores the next base label (V16 on the server data).
   - `POST /api/automation/variations/{label}/archive` changes the state only.
   - Both run under the plan's lock.
4. **The Runs variation filter.**
   - `RunSearchQuery.configurationHashes`, where an empty set matches no run.
   - The `idx_simulation_runs_configuration` index.
   - `RunVariationLookup.configurationHashes`, and `GET /api/runs?variation=`.
5. **Console**, built by the console session:
   - names everywhere a label was shown;
   - All, Active and Archived tabs on the Variations page;
   - a New variation page with "Copy settings from";
   - Archive, with a confirmation;
   - "Saves as" on the edit form;
   - the Runs Variation filter;
   - screenshots in `frontend/qa/20261126-variation-versions/`.
6. **Documents.**
   - `README.md`: the Variations paragraph, five variation endpoint rows, the `GET /api/runs` row, the message bullets and the schema paragraph.
   - The new ADR `docs/adr/20261126-variation-version-names.md`, with a pointer from `20261122-stored-editable-variations.md`.
   - `frontend/README.md`.
   - `docs/IMPLEMENTATION_STATUS.md`, for notifications.

### Where it differs from the plan

- **A request id belongs to one operation** (from the review). A repeat is answered only for the same create, or the same edit of the same label. Reusing another operation's id is 409 `VARIATION_REQUEST_REUSED` with `fields.requestId`.
- **Request type.** The edit request type was renamed `AutomationVariationConfigRequest` and is shared by create and edit.
- **Archive result.** Archive answers through its own `VariationArchiveResult`.
- **A sweep row with no name.** If a sweep row's label names no variation of the plan, the buy message is skipped with an `ERROR` log instead of inventing a name. The API cannot produce such a row.
- **Hash bound.** A variation's hashes are read with a bound of 100 (`AutomationLimits.MAX_HASHES_PER_VARIATION`). Above it, the lookup fails loudly instead of filtering on a subset.
- **Fewer reads on the Runs page.** Run chips read the plan's names only when the page has sweep rows, and the filter checks the label with a single-row read.
- **Console URL and layout.**
  - The Variations tabs live in `?show=`, and an old `?archived=1` link opens Archived.
  - "New variation" sits in the page header.
  - The empty Runs state reads "No run is using V03.1's settings".
- **Test classes.** The plan's suggested `AutomationVariationEditDatabaseTest` and `AutomationVariationCreateArchiveDatabaseTest` became:
  - assertions in `AutomationVariationServiceTest` and `AutomationControllerTest`;
  - `AutomationVariationCreateArchiveTest`, which also holds three behind-lock concurrency cases;
  - `RunVariationFilterEndToEndTest`, which goes from sweep rows to the run search with no fake in between.

### Verification

- `./gradlew build --rerun-tasks`: 289 test classes, 2,779 tests, 0 failures, 0 skipped.
- Frontend: 78 files and 831 tests pass, and lint, Prettier, `tsc -b` and `npm run build` are clean.
- In the browser on mocks: the names and label links, the Runs filter (V00 lists 3 runs with totals; the archived legacy V10 shows as "V07.1 (archived)" with an empty state), and archive with its confirmation.
- No live provider was called. The only server read was one variations listing through the console API, while planning.

**Not verified:**

- A reload of a dotted path in the console the backend serves. Only the resolver's unit test and Vite on mocks cover it.
- The real server data. Only a fixture mirroring the six chains was tested.
- How long the new index takes to build on the server.

### Schema

- **One additive index**, `idx_simulation_runs_configuration` (`configuration_hash`, `created_at`). `update` creates it at the first start; building it briefly blocks writes to `simulation_runs`.
- **From the merge:** the tables `notification_outbox` and `rpc_accounts`, plus the widened `api_usage_buckets` checks.
- Nothing is dropped, renamed, narrowed or backfilled. No SQL is applied by hand and no local reset is needed.

### Deploy

Push the backend and console together. The console now relies on `name`, the create and archive endpoints and the `variation` filter.
