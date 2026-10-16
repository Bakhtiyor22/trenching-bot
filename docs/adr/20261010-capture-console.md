# Capture workflow in the local console

Accepted 2026-10-10 with the user-approved [capture console plan](../plans/archive/20261010-capture-console.md), revision 2.

## Context

The bounded wallet, market and reference-rate capture commands already collect verified evidence for schema-v3 replay datasets. The console can import datasets and launch simulations, but acquisition requires terminal commands. Market acquisition can outlast an HTTP request and needs explicit resumption after budget, deadline or provider interruptions.

Capture evidence is already file-backed, hashed and locked by directory. A completed acquisition is not necessarily usable for replay: missing details, incomplete windows and decoding exclusions have distinct meanings. The existing capture budgets apply to each invocation; manifests separately retain cumulative usage.

## Decision

The console owns local HTTP transport, sessions, operation state and the React workflow. A public replay CaptureWorkflow contract composes the existing wallet/market/rate capture and inspection ports and the existing export/calibration services. Console code does not import their implementation packages. Public checkpoint callbacks publish bounded acquisition summaries after existing durable checkpoints, preserving CLI behavior and evidence hashes.

Each acquisition action is explicit. One worker accepts an operation and returns an operation id; competing operations are rejected while it is busy. Status reads only persisted metadata and never starts acquisition. The existing application-wide SOL ticker retains its separate behavior. Default builds, automated tests and agent verification use fixtures, with no live provider calls.

Sessions, capture attempts and operation records live below a configured evidence root, defaulting to build/captures. They are written atomically with exclusive identity and worker locks. Operation records are authoritative; cached session summaries are reconciled after a restart. Interrupted acquisition requires an explicit resume. No new database table is needed. Dataset registration continues through the existing checksum-keyed catalog and its database uniqueness constraint.

A capture attempt identifies one directory and its frozen source identity. A resume is a new operation on that same attempt. A new market attempt allocates a fresh directory, retains wallet evidence and previous results, and invalidates current downstream readiness. A new rates attempt retains its verified market dependency and invalidates current export readiness; calibration remains valid because it does not use rates. Every generated result records its input attempts.

The UI distinguishes the budget for the current operation from total usage for the capture. Wallet discovery defaults to a one-reference Pump.fun user-accumulator sample. The market preset allows up to 50000 transactions and 50 pages per window while retaining bounded per-operation defaults of 400 requests and 100 MiB. Existing CLI defaults and capture limits remain authoritative and unchanged. OVER_CAP cannot be fixed by repeating Resume; the UI offers a fresh market attempt. No automatic budget increase or chained acquisition is introduced.

Rate capture is enabled only after all selected market windows pass the same matching, completeness, nonempty-window and exclusion checks required by export. This prevents recording a minute set that changes when incomplete market coverage is later extended. Existing captures may be attached and inspected stage by stage, including valid incomplete wallet or market evidence, without moving or copying it.

The capture API requires the local profile, the existing console gate and local-access checks. Credentials remain in backend environment configuration. Browser requests identify sessions and attempts; they never supply a provider URL or arbitrary output path. Explicit attachment paths are contained within the evidence root and validated before use. Downloads expose only successful generated dataset/calibration artifacts.

The frontend reuses the existing Mantine theme, page headers, bordered panels, compact tables, status views and exact decimal presentation. Backend readiness and reason codes determine enabled actions, avoiding duplicated eligibility rules in the browser.

## Alternatives

Synchronous capture endpoints would hold requests open across paced provider acquisition and lose reliable status after navigation. Spawning Gradle processes would duplicate application lifecycle and introduce command/output parsing. A database job queue or distributed worker is unnecessary for the single local user and would add schema and deployment work. A single mutable market directory cannot recover from a frozen over-cap identity while preserving evidence.

## Consequences

The browser can manage the same bounded acquisition and offline replay workflow as the CLI. Directory locks continue to coordinate CLI and console writers. File-backed operation recovery and attempt lineage need explicit tests for crashes, duplicate requests and stage replacement. Evidence under build is disposable when the user runs clean; retained research should use a configured ignored root outside build or be backed up by the user.

No replay schema, simulation rule, execution cost, production schema or provider choice changes. Production remains outside this local console feature. Calibration uses the same formulas and deterministic serialization and does not alter execution defaults.

## Revisit when

Multiple users or processes need concurrent scheduling, operations need coordinated cancellation, durable server-side retention becomes necessary, or authenticated remote capture access is requested.

## Addendum 2026-10-16: names, summary and order

Accepted with the [console capture, run and results plan](../plans/20261016-console-capture-run-ux.md).

- **Unique names.** Names are compared by key: trimmed, whitespace collapsed, lower case. Two sessions created or renamed after this change never share a key; the check runs inside the file store's lock, since captures are local files in one process and have no database to hold a unique constraint. A blank name is generated as `{label} · {mint} · {d MMM HH:mm}` and numbered ` (2)`, ` (3)` on a collision; a typed name that collides returns 409 `NAME_TAKEN` with the first free suggestion. Legacy duplicates are never renamed on disk: their `displayName` is numbered on read by creation time, then id.
- **Derived summary.** One status, reason, step, progress and next action per session is computed by `CaptureStatusRules` from the session and its case records on every read. Nothing new is stored beyond an optional `nameKey`, so files written earlier stay unchanged. Progress is shown only once the total is known.
- **Order.** Lists are newest first by default (ties by id), or oldest first, by name, or by attention. Counts cover every session, not the page.
- **Source links.** Run and dataset detail name the capture, and the case, that registered their dataset by scanning the capture files; the earliest capture wins.
