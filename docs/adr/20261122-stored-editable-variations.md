# Stored, editable sweep variations

Accepted 2026-11-22 with the approved [editable variations plan](../plans/archive/20261122-editable-variations-and-variation-runs.md) (Key Decisions 1–5, Domain Rules 1, 4–10, 13 and 16), every open question answered as recommended. It supersedes the part of Decision 6 of [automated capture and sweep evaluation](20261102-automated-capture-and-sweep-evaluation.md) that fixes the sweep plan in code ("a changed list is a new plan id, never an edit"); the rest of that decision stands.

## Context

A "variation" is a sweep entry: one `SimulationConfig` replayed on every captured buy. `decu-sweep-1` held `V00` (the agreed configuration) and `V01`–`V09` as the constant `SweepPlans.DECU_SWEEP_1`. Changing one meant a code change and a deploy, and a changed list had to be a new plan id, which starts scoring afresh (Decision 7). The user wants to change a variation's settings from the console and keep what the old settings produced.

What an edit must not disturb:

- A sweep row is identified by `sha256(item|plan|label)` and unique on `(item_id, plan_id, label)`. It points at a run, whose stored snapshot is the configuration. If a label could change meaning, an old row would describe a configuration its label no longer names.
- A verdict is a function of stored rows (Decision 7 and the plan's Rules 33–34). A decided verdict must read the same tomorrow.
- Replay is deterministic and offline, so the same configuration on the same dataset always gives the same run; nothing about an edit may make that depend on the clock.

## Decision

1. **Variations are rows.** Table `automation_variations`, keyed by `sha256(plan|label)` and unique on `(plan_id, label)`, `(plan_id, seq)` and `(plan_id, request_id)`. A row holds the whole configuration, its state (`ACTIVE` or `ARCHIVED`), its lineage (`replaces_label`, `replaced_by_label`), the moment it became active and the moment it was archived. The plan id stays `decu-sweep-1`; the property `trenching.automation.sweep-plan` names the plan, and the code plan only seeds it.
2. **A variation's configuration is immutable; an edit is archive plus create.** The edited variation becomes `ARCHIVED` (kept, never swept again) and a new `ACTIVE` variation takes the next label (`V10`, `V11`, ...) with `seq = max(seq) + 1`. Labels are never reused. Only `state`, `archived_at` and `replaced_by_label` ever change, and only from active to archived. Sweep-run identity `(item, plan, label)` is therefore unchanged and keeps meaning one fixed configuration forever. Seeded rows are numbered from zero (`V00` is `seq` 0) so a label is always `V` plus its `seq`, zero-padded to two digits.
3. **Stored rows are the authority once seeded.** At startup a plan with no row gets the code plan's entries as `ACTIVE` rows with no activation day; a plan with rows is left alone, so a later change to `SweepPlans` never rewrites history. Seeding is one transaction; a concurrent second seed fails on the label key and counts as seeded. Startup then checks that every active variation has a minimum signal size at or above `capture-min-signal-sol` (plan Rule 6) and fails naming the label otherwise.
4. **An edit applies to buys swept after it.** A sweep tick reads the active variations once, at its start; a tick that finds none sweeps nothing and changes no item. An item is `SIMULATED` when each variation active at the start of its tick has a row for it. Items already `SIMULATED` are not touched, and no row is ever written for an archived label.
5. **A variation has verdict rows only for the trading days of its life**: from the day it was created through the day it was archived (a seeded variation, with no creation day, from the start of the plan). Tallies keep Rules 22–24 unchanged: within a window, a simulated buy with no row for the label counts `unscored`. No decided verdict changes because of an edit, since its window's items were all terminal and so all had rows under the label. The price is that a new variation's window includes earlier buys as `unscored`, so for its first days it reads `NO_RESULT`.
6. **The edit is forward-only.** A new variation is not replayed on buys that were already simulated. The datasets are kept and replay costs no provider request, so a catch-up is possible, but it needs rules of its own (how far back, what a failed catch-up means for an `SIMULATED` item, whether verdicts wait for it) and is left to a later plan that changes nothing decided here.
7. **Time and identity are injected.** The archive and creation moments come from the `SimulationClock`-qualified `Clock`, the id from `AutomationIds.variation`, the label from `seq`. Variations are always read ordered by `seq`, so a replay of the same rows gives the same plan in the same order.

## Alternatives

- **Versions under one label (`V03 r2`).** Rejected: it adds a revision to the sweep-run key, its unique constraint, the item events and every scoring query, and every existing reader would have to learn it.
- **A new plan id per edit.** Rejected: it is the old rule, and Decision 7 then scores the new plan from nothing, discarding the nine unchanged variations' windows.
- **Editing a row in place.** Rejected: a stored sweep row would describe a configuration its label no longer names, and a decided verdict could change after the fact.
- **Clipping a variation's tally window to its life** instead of counting earlier buys `unscored`. Rejected: it changes Rule 24's window definition for every label; counting them `unscored` leaves it as it is.
- **Keeping the code plan authoritative and storing only overrides.** Rejected: two sources of truth, and the lineage of an override would still need a table.

## Consequences

- One new table and one new index on `automation_sweep_runs (label, plan_id)`; both are additive under `ddl-auto: update`, so nothing is applied by hand and nothing is destructive. Redeploying the previous build ignores both and falls back to the code plan; runs stored under labels created by edits stay in the database, unlisted by the old build's scoreboard but intact.
- `GET /evaluations` and the day pages may return rows for archived labels on past days and none for them afterwards; the number of rows per day is the number of variations alive on it.
- Every sweep tick and every days read loads the plan's variations once (at most 500 rows), never per item or per day.
- A plan never has zero active variations by edits alone (an edit archives one and creates one); the sweep still guards the case, and sweeps nothing.
- `SweepPlan` no longer refuses an empty list; the check moves to the one place that needs it, the capture-minimum check of the code plan and of the stored active ones.
- Rules 1 and 2 are enforced by the entity (configuration columns are not updatable) and, for the edit itself, by the edit transaction, which locks the plan's active rows so label allocation is serialised; `uq_automation_variations_seq` is the database backstop.

## Revisit when

- Catching up a new variation on already-simulated buys is wanted (decision 6).
- A second plan, restoring an archived variation, or archiving without a replacement is wanted: each breaks "an edit never changes the number of active variations" and Rule 1's one-way state.
- A plan nears 500 variations, the read bound.
