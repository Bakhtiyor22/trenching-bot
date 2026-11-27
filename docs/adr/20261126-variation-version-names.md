# Variation version names

Accepted 2026-11-26 with the approved [variation versions plan](../plans/archive/20261126-variation-versions-archive-and-run-filter.md) (Key Decisions 1 to 3 and Domain Rules 1 to 5), every open question answered as recommended. It supersedes the naming in Decision 2 of [stored, editable sweep variations](20261122-stored-editable-variations.md) ("the next label, `V10`, `V11`, ..., never reused"); the rest of that decision stands.

## Context

An edit archives a variation and stores a replacement under the next sequence number, so editing `V03` gave `V10`, and editing `V10` gave `V12`. The console showed `V10` and `V12` as unrelated variations, and the lineage was only visible in `replacesLabel`. The user wants an edit of `V03` to read as a version of it: `V03.1`, then `V03.2`.

A label is also a key. `automation_variations` ids are `sha256(plan|label)`, `automation_sweep_runs` ids are `sha256(item|plan|label)`, each sweep entry's history events carry the label in their JSON detail, and every API response and console URL uses it. On the server, six edits already exist: `V10` of `V03`, `V11` of `V04`, `V12` of `V10`, `V13` of `V09`, `V14` of `V01` and `V15` of `V07`.

## Decision

1. **A name is derived, never stored.** A variation's revision is 0 when `replacesLabel` is null, else the replaced variation's revision plus one. Its line is its own label at revision 0, else the replaced variation's line. Its name is the line at revision 0, else `line.revision`. All of a plan's rows (at most 500) are read together, so deriving a name costs no query per row. `replaces_label` is written once and never updated, so a name never changes.
2. **From now on an edit's label is its name.** An edit of a variation stores the label `line.(revision + 1)`; an edit of `V12` (named `V03.2`) stores `V03.3`. `seq` stays the maximum plus one, which keeps the creation order and the unique `(plan_id, seq)`. Only the active head of a line can be edited and the edit archives it under the plan's lock, so a line never forks; `uq_automation_variations_label` backs it, and a collision answers `AUTOMATION_CONFLICT`.
3. **Legacy labels stay as keys.** The six existing edits keep `V10` to `V15` as labels and are named `V03.1`, `V03.2`, `V04.1`, `V09.1`, `V01.1` and `V07.1`. `V00` to `V09` are named by their labels. The API keeps `label` as the key and gains `name`.
4. **A new variation takes the next base number**: `V` plus one more than the highest number any label of the plan starts with (the digits after `V`, up to the dot), padded to two digits. With `V00` to `V15` that is `V16`.
5. **A broken line fails the read.** A `replacesLabel` that names no row of the plan, or a replaces chain that loops, throws with a message naming the label. Both rows of an edit are written in one transaction, so the API cannot produce either; a name is never invented.
6. **Order is by name**: the line's number, then the revision (`V00`, `V01`, `V01.1`, `V02`, `V03`, `V03.1`, `V03.2`, `V16`). `GET /api/automation/variations` and the run chips use it. The sweep, scoring and `GET /api/automation/evaluations` keep the sequence order.
7. **A dot in a console path is a page route.** The page fallback treated any path with a dot as a file, so reloading `/console/automation/variations/V03.1` answered 404. A path is a file only when its last segment ends in a dot, a letter and then letters or digits (`.js`, `.css`, `.woff2`).
8. **The Runs filter matches by configuration hash.** `GET /api/runs?variation=V03` lists the runs whose configuration hash is one that `V03`'s sweep runs carry (`automation_sweep_runs.configuration_hash`, at most 100 distinct hashes, more fails loudly instead of filtering on a subset). A run is a replay of a configuration, so a manual run, or another variation, with identical settings has the same hash and is listed too. The hash set goes to the replay search as a bounded `IN` list; replay never reads automation's tables.

## Alternatives

- **Renaming `V10` to `V15` in place by hand-applied SQL.** Rejected: their ids, their sweep runs (and those ids) and their history events would all be rewritten, with automation stopped so no sweep writes an old label between the SQL and the deploy. It would also be a destructive change with no test that can prove it on the server's data.
- **Keeping `V` plus the sequence number as a hidden key with a separate stored name.** Rejected: every edit would use up a number, so new base numbers would keep jumping, and a second stored identifier would have to be kept in step with the lineage.
- **Storing the name in a column.** Rejected: it is a function of the replaces links, and a column could drift from them.
- **Filtering the Runs list by a list of run ids.** Rejected: a variation's runs grow with every swept buy, so the list is unbounded and would not fit a request or a query.
- **Joining `automation_sweep_runs` inside the replay search.** Rejected: it couples replay to automation's tables and breaks the module boundary; the hashes cross the boundary as plain values instead.

## Consequences

- No schema change and no data change. The names of the existing rows are read, not written.
- The next new variation after `V00` to `V15` is `V16`, not `V10`: a gap in the base numbers that happens once. Edits no longer use up base numbers, so later new variations are `V17`, `V18` and so on.
- For the six legacy edits the label and the name differ, so a client must link by `label` and show `name`.
- `AutomationVariationViews` reads the plan's rows once per call and derives every name from them; a read fails if the lineage is broken.
- The Telegram messages print names, and order by name.

## Revisit when

A plan needs more than 500 variations, a line needs to fork (an edit of an archived variation), or the six legacy labels become a nuisance for a client; then renaming them by SQL, with automation stopped, can be planned on its own.
