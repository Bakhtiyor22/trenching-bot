# Capture workflow in the console

> Bring the existing bounded capture, offline inspection, replay export and calibration workflow into the current React console.

Status: Delivered (approved 2026-10-10; user: "ok then implement it")
Date: 2026-10-10
Revision: 2 - reviewed, optimized and approved for implementation.

## Context

Capture exists as standalone Gradle commands, with no capture REST API or frontend. WalletHistoryCapture and RecordedWalletHistory own wallet acquisition/decoding; MarketHistoryCapture and RecordedMarketHistory own market acquisition/extraction; ReferenceRateCapture and RecordedReferenceRates own rate evidence. ReplayMarketCaptureService plans the market windows, RecordedReplayExportService exports canonical v3 datasets, and ExecutionCalibrationService measures execution offline. These services already enforce bounds, artifact verification, resume identity and completeness.

The console currently manages datasets and runs and offers on-demand Solana diagnostics. Its UI uses Mantine, near-black surfaces, bordered panels, amber actions, compact tables, exact monospace values and shared PageHeader/Section/StateViews components. Reuse that design and existing React Query/MSW patterns.

Current decisions: [capture](../adr/20261003-bounded-wallet-history-capture.md), [discovery](../adr/20261006-pumpfun-user-accumulator-discovery.md), [dip entry](../adr/20261008-dip-entry-strategy.md), [recorded reference rates](../adr/20261008-reference-rates-in-dataset.md), [curve execution](../adr/20261009-bonding-curve-execution.md), [console](archive/20261002-console-and-monitoring.md).

## Scope

### In scope

- Captures navigation item and command-palette action; list/new/detail routes at /captures and /captures/:id.
- New sessions: name, wallet, explicit discovery scope, sample size and bounded acquisition settings.
- Attach existing CLI wallet/market/rate directories by paths relative to the configured capture root, with offline verification before use. This supports the captures already collected; no raw-evidence upload is needed.
- Separate user actions for wallet capture/resume, market capture/resume, rate capture/resume and offline inspect, export and calibration. Each step displays the prerequisites in the stage matrix below and its latest result.
- New market/rate attempts in fresh managed directories when immutable capture identity or an over-cap result prevents resume. Preserve earlier attempts and the existing wallet evidence.
- Background operations, checkpoint status, reload/restart recovery, controlled retries, honest incomplete states and sanitized errors.
- Export schema-v3 JSON, download it, and register it in the existing dataset catalog with a separate Import dataset action. Link to the dataset and New run after registration.
- Calibration summary, bounded per-buy table and deterministic JSON download, using existing exact arithmetic.
- Source/budget/coverage summaries and recognizable route links for verified public chain identifiers.

### Out of scope

Continuous wallet tracking, new routes/providers, automatic chained acquisition, trading, strategy/default changes, production capture access, capture deletion, moving existing evidence, raw transaction viewers, authentication, distributed workers, and new dependencies. Cancellation is deferred: operations retain the existing finite budgets/deadlines and can be resumed explicitly when incomplete.

## Key Decisions

1. Console owns HTTP, session/job lifecycle, filesystem metadata and UI; replay owns a new narrow public CaptureWorkflow facade over the existing services. Console never imports replay/application, capture infrastructure or another module's entities.
2. Each acquisition step requires an explicit user click. Capture GET endpoints, capture status polling, attachment, inspection, export, calibration and import make zero provider calls. Completing a step does not start another one. This guarantee applies to the capture workflow; the existing shared SOL ticker keeps its current Binance-backed polling behavior. Opening a capture page never starts acquisition.
3. Long operations are asynchronous. Return 202 with an operation id; one bounded worker executes one operation at a time across console sessions. A different start while busy returns 409; there is no waiting queue. Same request id and command return the existing operation, even after completion or restart. Persist the operation before dispatch. Existing per-directory locks also protect against concurrent CLI writers. Session/attempt changes are rejected while that session has an active operation.
4. Persist sessions and operation snapshots atomically under build/captures/console/<sessionId> by default. A session has one wallet directory and separate market/rate attempt directories; exports and calibration reports belong to their exact input attempts. Use filesystem identity and exclusive creation/locks; no new database tables. The evidence root defaults to build/captures, so existing CLI directories can be attached. An optional validated trenching.console.capture.root-directory changes that root. Clean removes build output; document retention and the option to use an ignored directory outside build.
5. Existing captures are attached by explicit root-relative paths, never by unrestricted file browsing or recursive artifact discovery. Wallet is required; market and rates are optional. Offline inspection accepts structurally valid incomplete captures for inspection/resume, imports immutable identity from their manifests, and distinguishes missing, incomplete, usable and mismatching stages. Full matching/completeness checks gate downstream actions. Attaching does not copy, overwrite or move evidence. A later explicit resume may update its existing manifests under existing acquisition rules; an unusable rates attachment can be replaced by a fresh rates attempt.
6. Live capture reuses SOLANA_RPC_URL in the backend process and the existing capture transport limits/timeouts; the browser receives only a configured boolean. No credential entry field or endpoint override. Spring capture endpoint properties currently differ from the Solana probe binding, so add explicit console wiring to use the same existing backend RPC property without changing local/prod YAML or CLI configuration.
7. Use the existing reference-rate property/source for user-triggered rate acquisition; when disabled report NOT_CONFIGURED. Offline readers remain independent of that setting. No new provider, fallback, retry algorithm or timeout policy.
8. New UI defaults to pumpfun-user-buys with a one-transaction sample, making the first experiment small; wallet-references remains selectable with its true address-reference meaning. Scope/limit/pages become immutable after acquisition begins. This changes UI defaults only, not CLI defaults or decoder semantics.
9. Wallet settings preserve existing bounds: limit 1-100; scanPages 1-50; requestBudget 1-1000; byteBudget at most 500 MiB. Market settings preserve existing bounds: scanPages 1-500; maxTransactions 1-50000; requestBudget 1-60000; byteBudget at most 4 GiB. Wallet UI defaults: 1 transaction, 20 pages, 400 requests and 100 MiB per operation. Recommended market UI preset: 50 pages per window, capacity 50000 transactions in total, 400 requests and 100 MiB per operation. The larger capacity avoids the known 300-transaction dead end; the per-operation budgets still bound work. Show effective settings before Start, preserve CLI defaults, and never increase a running/resumed budget automatically. Display the existing 4500-slot window. A capacity is a ceiling, not a target or a cost estimate.
10. Display Budget for this run and Total used for this capture as separate figures. Preserve the backend semantics: each explicit capture/resume receives a fresh request/byte allowance, while the manifest records cumulative usage. Checkpoints expose both current-operation and cumulative counters, current phase, elapsed time and last checkpoint time. Do not invent a total scan percentage or ETA. Checkpoint counts are acquisition progress, not proof of replay readiness; only full offline verification establishes readiness.
11. After export, Import dataset calls ReplayDatasetCatalog.register on the verified generated JSON, preserving checksum identity and existing database uniqueness. Export remains recoverable if database registration fails, and retries do not re-acquire chain data.
12. Keep the existing theme unchanged. Reuse PageHeader, Section, KpiStrip/Stat, StateViews, existing table/paging patterns, notifications and theme tokens. Put the essential wallet/scope/sample fields first; keep budgets in an Advanced section with the effective settings visible before acquisition. Detail shows a compact stage summary and the selected stage panel, rather than a new standalone design. Responsive layout stacks panels on narrow screens; dark/light and keyboard/accessibility behavior follows current components.
13. A new market attempt keeps the wallet capture and allocates a fresh market directory with its own immutable capacity/settings. Selecting it invalidates current rate readiness, export readiness, calibration and the current dataset link. A new rates attempt keeps the selected complete market attempt and invalidates current export readiness/dataset link; calibration remains valid because it does not use rates. Old evidence, generated files and imported datasets remain associated with their original input attempts. Creating an attempt performs no provider call and does not start capture.

## Module Contracts

Additive public contracts; no replay-format or simulation-contract break. Approval includes these additions.

- replay/CaptureWorkflow: execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult; inspect(paths: CapturePaths): CaptureInspection.
- replay public value types: CapturePaths (required wallet; optional market/rates for inspection; managed export/report paths supplied only to the relevant command), CaptureStepCommand (sealed wallet/markets/rates/inspect/export/calibrate commands with required paths for that step), CaptureStepResult (typed results), CaptureInspection (verified prerequisite/coverage/decoder/extractor summaries). Stage eligibility is computed once by the backend; frontend consumes canStart/canResume/requiresNewAttempt flags and reason codes, rather than reproducing the rules. Filesystem paths stay server-side. Console commands reference session/attempt ids; the service resolves paths and fixes the exact upstream attempts before dispatch.
- chaincapture/CaptureCheckpointObserver and CaptureCheckpoint: vendor-neutral immutable progress callback with phase, current-operation and cumulative request/byte usage, and processed/selected counts where known. Default no-op in existing CLI paths. Publish after existing atomic checkpoints; do not change checkpoint frequency or hashes. Missing counters are null, never fabricated. Observer failures must be surfaced, never swallowed; a failed progress update cannot roll back already-persisted evidence.
- Wallet/market factory overloads or optional observer parameters create per-operation acquisition adapters; existing WalletHistoryCapture.capture and MarketHistoryCapture.capture signatures remain unchanged. Replay composes only walletactivity/marketdata public contracts. Reference-rate progress may be phase-only until its existing atomic result is available.
- Console CaptureSessionService/CaptureOperationService and a file-backed store own lifecycle outside any DB transaction. Inject Clock and an id generator. Thin controllers map through MapStruct; separate DTO files.

HTTP, all under the existing console gate and local profile:

- GET /api/captures/config: configured flags, defaults and existing bounds; offline.
- GET /api/captures?page=0&size=20: bounded session summaries; offline.
- POST /api/captures: create a session or attach explicit existing root-relative directories; no provider call. Include requestId for creation idempotency. Attachment returns an offline verification operation; raw path references are not usable for capture until validated.
- GET /api/captures/{id}/attempts?page=0&size=20: bounded attempt history with input lineage and safe summaries; offline.
- POST /api/captures/{id}/attempts: requestId and stage MARKET or RATES; allocate/select a fresh managed attempt and apply downstream invalidation. Return its id/settings; no provider call.
- GET /api/captures/{id}: session/step state and last verified summaries; offline.
- POST /api/captures/{id}/operations: requestId, step, selected attemptId and validated step settings; returns 202/operation snapshot, or 409 if busy/conflicting. The command records its exact upstream input attempts. All acquisition settings are shown before the click. The frontend retains the requestId across an uncertain network response and reconciles against the server before issuing a new action.
- GET /api/captures/{id}/operations/{operationId}: persisted progress/result; offline.
- POST /api/captures/{id}/dataset: idempotent catalog registration of the successful export; returns existing DatasetRegistrationResponse.
- GET /api/captures/{id}/downloads/{dataset|calibration}: allowlisted successful generated artifact only; never accepts a file path.

Errors retain the console code/message/fields envelope. 400 invalid fields/artifacts; 404 missing ids; 409 active/conflicting operation, stale attempt, unmet stage prerequisite or lock; unavailable integration reported explicitly. Background results contain safe category codes and summaries, never raw exception text/provider payloads. Creation/attempt/operation request ids are bound to their full normalized command: reusing one with different settings is a conflict. Operation states: RUNNING, SUCCEEDED, INCOMPLETE, FAILED, INTERRUPTED. Stage states: NOT_STARTED, INCOMPLETE, READY, UNUSABLE, INVALID. UNUSABLE covers valid completed evidence with no supported buy/usable window; INVALID covers unsafe, tampered or mismatching evidence. Stage readiness is independent of operation status; verified acquisition completion alone never implies export readiness.

## Stage Prerequisites and Recovery

Both backend validation and frontend controls use this matrix. Disabled actions show the concrete unmet prerequisite. Cached readiness helps display; every mutating step revalidates its actual inputs offline before a provider call or generated write.

| Action | Required inputs | Result/recovery |
| --- | --- | --- |
| Inspect or attach | Root-contained wallet path; optional market/rates paths | Inspect each existing stage independently. Missing later stages and structurally valid incomplete acquisition are reportable, not an all-or-nothing failure. Unsafe/tampered artifacts are INVALID and never acquired from automatically. |
| Capture/resume wallet | Valid wallet/scope/frozen identity, per-operation budget, configured RPC | Incomplete acquisition can resume with the same identity. A complete buy-free capture is inspectable, but cannot unlock market capture; use a new wallet session/sample. |
| Capture/resume market | Complete verified wallet with accepted buys and the existing planned windows; selected market attempt; configured RPC | Budget/deadline/provider stops can resume. COVERAGE_INCOMPLETE can extend scanPages. OVER_CAP requires a new market attempt with an appropriate capacity; repeating Resume cannot resolve it. Irrecoverable detail gaps remain explicit. |
| Capture/resume rates | Selected market attempt passes the same matching-window, complete-coverage, detail-gap, nonempty-window and exclusion checks as export; rate source enabled | Rates are fixed to that verified observation-minute set. Resume fetches only missing rates. A mismatching existing rates capture requires a new rates attempt. |
| Calibrate | Wallet and market pass the existing calibration/export matching checks | Rates are unnecessary. Preserve math/latency/cost results and exact deterministic JSON. |
| Export dataset | Verified matching wallet/market plus every required recorded rate minute | Write canonical v3 output for these attempts. Missing/excluded evidence remains an explicit refusal. |
| Import dataset | Successful generated export still passes the existing loader/checksum validation | Catalog registration is idempotent by checksum. A failed registration can retry independently of acquisition/export. |

A capture attempt owns one directory and its frozen identity; multiple operations can resume that same attempt. A new market/rates attempt is distinct from a resume: it gets a new directory, may choose a new immutable capacity/identity, and retains the old attempt. Stage replacement, path changes and source identity changes are never smuggled into Resume. Changing wallet/scope/limit/frozen scan pages creates a new session. New-attempt controls are disabled during active work.

## Domain Rules

1. The capture workflow makes provider calls only for a validated explicit acquisition POST. Missing configuration, invalid fields, missing prerequisites and directory-lock conflicts consume no RPC requests. The shared ticker is outside the capture workflow guarantee.
2. Wallet capture retains confirmed mainnet, frozen selection, failed-reference counts, scope identity, existing supported routes and explicit gaps. Acquisition completion does not mean a supported buy exists.
3. Market acquisition uses the existing first-buy-per-mint windows and requires a verified complete wallet capture with accepted buys. Coverage incomplete, over cap, detail gaps and exclusions stay visible and prohibit export under existing rules.
4. Rates acquisition is gated on the fully verified complete market attempt, not merely acquisitionComplete or the presence of some decoded observations. Persist that market attempt as the rates dependency. Missing/rate-limited/region-blocked data is explicit; resume fetches only unresolved minutes under the existing rate implementation. A changed minute set invalidates rates readiness and needs a fresh rates attempt.
5. Preserve existing resume identity: wallet/scope/limit/pages cannot change; market maxTransactions and planned windows cannot change, scanPages can increase but not decrease. Request/byte limits apply per invocation; saved usage is cumulative audit metadata and is not subtracted from a new allowance. Every Resume starts a new explicit operation on the same capture attempt/directory, with its shown allowance and deadline. Deadline exhaustion may require another explicit resume even with budget remaining. No automatic resume or budget increase. Explain why requestBudget=3 can yield total requestsUsed=5 after a successful resume, as the existing test demonstrates.
6. SUCCEEDED means the requested operation completed; replayReady becomes true only after the existing export service verifies all matching evidence and writes canonical v3 JSON. No fallback, filtering incomplete windows, invented observations or changed horizons.
7. GET reads cached session/job snapshots only. Inspection and any full hash/decoder verification are explicit offline operations, not expensive recurring GET work.
8. Session/request/operation identity uses exclusive filesystem creation and locking, not a check-then-write race. Duplicate request ids cannot start duplicate work. Imported datasets keep the existing checksum primary key in replay_datasets.
9. Persist operation identity/command before dispatch and its terminal result before publishing the session summary. Operation records are authoritative; session summaries are derived caches. Recovery reconciles results and current-attempt lineage after taking the process worker lock, marks orphaned RUNNING operations INTERRUPTED, and never starts acquisition. Retain evidence/counters and offer explicit resume after prerequisite/identity checks. A crash after writing an export but before recording success is recovered by an explicit offline retry through the existing deterministic write-once service. Process-wide worker shutdown interrupts politely and releases held locks.
10. Resolve all directory references against the configured evidence root; reject absolute paths, traversal, aliases through symlinks/junctions/reparse points and overlapping input/output directories. Resolve and verify containment before reads/writes. Allow only expected manifest/generated artifact names; never inspect environment or key files. No arbitrary shell commands, Gradle spawning, log streaming or generic file endpoint.
11. Capture HTTP is available only in local profile with console enabled. Reject remote peers and foreign browser origins for capture routes using a dedicated local-access adapter rule, including the existing local Vite origin. GETs never start acquisition. Do not change access policy for other console routes.
12. UTC Instant metadata comes from injected Clock. Acquisition timestamps/operation ids do not affect canonical dataset identity. Preserve exact money/raw amount JSON strings and all scale/rounding decisions in the curve-execution ADR: raw values BigInteger, calibration errors scale 2 HALF_EVEN, exact decimal medians, negative residuals retained. Frontend does not calculate model results.
13. Inspect/export/calibrate/import work with offline fixture adapters and no configured provider. Calibration needs matching wallet/market captures, but no rates. Its summary/per-buy results and JSON must match the CLI, and cannot silently change execution defaults.
14. Provider categories are allowlisted and safe. Unexpected failures retain diagnostic stack context only after ensuring secret-bearing values cannot be logged; frontend receives a generic safe message. Known partial acquisition reports only reasons actually available from current results, never infers a timeout or rate limit from missing data.

## Data Model

No entity, table or production SQL change. Existing ReplayDatasetEntity and checksum primary-key uniqueness remain the catalog authority. No database reset is required by this feature.

File metadata has a format version. Sessions store id/name/createdAt, public wallet/scope, immutable wallet acquisition identity, current market/rates attempt ids and cached verified summaries. Attempt records store stage/id, managed or attached root-relative directory, immutable acquisition settings, exact upstream attempt identity and bounded summary references. Export/calibration/registration results record the exact wallet/market/rates attempt lineage they used. Operation records store id/requestId/command fingerprint, step/input attempt ids, effective non-secret settings, UTC start/update/end, state, current-operation/cumulative counters and safe result/error codes.

Use <session>/wallet, <session>/markets/<attemptId>, <session>/rates/<attemptId> and attempt-specific generated directories; attached paths remain references. Serialize session mutations under a session lock and worker dispatch under the process lock. Use exclusive request-id records and atomic file replacement. Operation records are authoritative and session/attempt summary caches are reconciled on recovery; an atomic rename of one file is not treated as a transaction across multiple files. A restarted process does not report a stale RUNNING session when its operation is terminal. Never rewrite raw evidence outside existing acquisition services. Console metadata is excluded from source evidence hashes.

List only immediate managed session directories and dedicated metadata/attempt entries, with bounded pages; never recursively enumerate transaction artifacts. Detail returns current summaries and selected results, not unbounded historical arrays. Per-buy tables are bounded by the existing 100-buy wallet limit and paged in the frontend. Do not deserialize whole raw evidence on every status request.

## Transactions & Integration

- Existing read-only transport: mainnet identity check, one request at a time, 500 ms pacing, bounded retries, request/byte/deadline limits, and unsafe-text/credential-echo protections. Preserve existing 5 s connect, 15 s read and 10-minute operation defaults; do not interpret a long collection as one unlimited operation.
- Database registration runs after successful offline export through the existing catalog transaction. No external call or background acquisition inside a DB transaction.
- Application properties may add validated non-secret capture root configuration; existing console/RPC/rate configuration is reused. No new mandatory production variable; local/prod YAML and Compose remain unchanged.
- Tests inject fake ports/checkpoints and temporary evidence directories. Do not use user captures, paid providers, live Binance, a real database or existing ports for manual verification.

## What To Implement

1. Public replay workflow and checkpoint seam: reuse acquisition/inspection/export/calibration services and prove parity with existing CLI outputs and completeness rules.
2. Console session/attempt/operation lifecycle: root containment, atomic metadata and locking, duplicate-request handling, one worker, authoritative recovery and gated HTTP/DTO/mapping/download/import contracts. Implement the stage matrix and lineage invalidation centrally, so frontend/backend do not invent different eligibility rules.
3. Captures UI: list/new/attach/detail, settings/prerequisites, explicit acquisition/resume/new-attempt controls, offline inspection tables, separate per-operation budgets/cumulative usage, checkpoint polling, errors and restart states; existing design system throughout. Poll at 2-second intervals only while capture operations are RUNNING; stop at terminal state and pause while the page is hidden.
4. Dataset/calibration handoff: export download, import and run links, calibration summary/table/download, MSW development fixtures and navigation integration.
5. Independent review, appropriate verification and documentation; archive this plan with a Delivered note after implementation.

Units 1-2 provide the backend contracts for 3-4. Frontend may develop against agreed MSW contract fixtures while backend is implemented. No two concurrent Gradle builds.

## Verification

- CaptureWorkflowTest: existing service parity; incomplete/buy-free/tampered inputs; wrong market windows; missing rates; calibration without rates; no provider call for offline operations; observer does not change evidence or canonical output. A market with acquisitionComplete=true but incomplete coverage must refuse rate acquisition before calling its source. Test that a 3-request allowance can produce 5 cumulative requests across resumes without changing the backend budget semantics.
- CaptureSessionServiceTest/CapturePathPolicyTest: idempotent creation/attachment, wallet-only and incomplete-stage attachment, invalid wallet/bounds, path traversal/absolute/junction/overlap rejection, no evidence mutation during attachment, version handling and bounded paging. New market attempt preserves wallet/old evidence and invalidates downstream results; new rates attempt preserves calibration but invalidates export. Mismatching attached rates cannot enable export.
- CaptureOperationServiceTest: asynchronous response, same-id replay, changed-payload/stale-attempt conflict, cross-session busy rejection, lock contention before RPC, progress, partial acquisition, safe error handling, shutdown/restart interruption and explicit resume only. Simulate crashes between authoritative operation persistence and summary publication; terminal operations reconcile correctly, duplicate request ids never dispatch twice, and deterministic export retries recover already-written output. OVER_CAP cannot be resolved by Resume; a fresh market attempt can use a different capacity and performs no reads until Start.
- CaptureControllerTest: console/local gates and remote/origin checks; validated API envelope; downloads limited to successful allowlisted generated artifacts; GET/poll never calls providers.
- Console integration with Testcontainers: importing the same exported dataset twice returns the same catalog id and creates one dataset row. Export survives a registration failure.
- CapturesPage/CaptureDetailPage tests with MSW: create/attach, scope/defaults/bounds, invalid fields, start/resume/new-attempt request shapes, stable request id after uncertain responses, active-state controls, checkpoint polling ending at terminal state, incomplete coverage/gaps, unavailable configuration, restart recovery, full wallet-to-dataset flow, calibration without rates, exact decimals and mobile/accessibility semantics. Assert Budget for this run versus Total used for this capture, OVER_CAP new-attempt guidance, disabled rate acquisition before complete market verification, downstream invalidation and current-dataset links. Keep shared ticker requests mocked separately; capture GET endpoints and poll handlers never call acquisition adapters.
- Existing capture/export/calibration CLI tests continue passing. Full backend build, frontend tests/lint/build once integrated. Set JAVA_HOME=C:/Users/user/.jdks/ms-21.0.10; no concurrent Gradle.
- Visual check using an isolated mock-only frontend instance: current dark/light styles and desktop/narrow widths. Do not browse the real running app to exercise provider controls or restart user services.

## Documentation Sync

README console workflow/configuration; REAL_DATA_CAPTURE browser equivalent, backend-process environment distinction, per-invocation budget semantics and fresh-attempt recovery; IMPLEMENTATION_STATUS and HANDOFF; new ADR for local asynchronous capture/file recovery and public workflow seam. Correct the existing capture documentation that incorrectly describes request/byte allowances as lifetime totals. Fix DatasetUpload's stale Schema v2 label to v3 when integrating exported datasets.

## Acceptance Criteria

- A user creates or attaches a capture session, sees its verified prerequisites and starts/resumes each existing acquisition step from the UI.
- Reloading retains the current operation; backend restart shows interruption and makes no acquisition request until resume is clicked.
- Capture status polls consume zero provider calls; each acquisition stays within its shown per-operation bounds/deadline and preserves manifests/resume behavior. Total usage accurately accumulates across explicit resumes; the shared ticker retains its existing behavior.
- An OVER_CAP capture has a working fresh-market-attempt path without recapturing wallet evidence. Rates cannot start until all selected market windows pass full verification; replacing an attempt cannot leave a stale current export/dataset/calibration result.
- Incomplete evidence remains visible and cannot be exported as a valid dataset.
- Matching complete captures produce byte-identical CLI/UI v3 exports and calibration JSON. Import creates one dataset by checksum and links into the current run workflow.
- All new pages match the existing Mantine theme, panels, typography and actions across dark/light and narrow layouts.
- No new schema/dependency, production-profile change, real provider verification or Git state mutation.

## Open Questions

The user approved this revised feature plan and its additive public contracts on 2026-10-10. Recommendations above cover scope, filesystem persistence, local-only access, manual per-stage starts, fresh-attempt recovery and existing backend limits. No unresolved simulation or provider decision is introduced. No secret values or live-provider authorization are needed to implement/test. Actual capture remains user-triggered after delivery.

## Review Resolution

- Budget semantics: fresh allowances per explicit invocation; cumulative usage shown separately, with regression coverage and correction of stale documentation.
- Over-cap dead end: useful market capacity preset plus an explicit new-attempt action retaining wallet and previous evidence.
- Rates identity mismatch: full-market-verification prerequisite, exact dependency lineage and fresh rates attempts for mismatching attachments.
- Provider-call promise: scoped to capture operations/endpoints; the existing global ticker is preserved and mocked independently.
- Recovery precision: operation records are authoritative, cached summaries reconcile after interrupted multi-file updates, and input attempts are fixed before dispatch.

## Delivered

2026-10-10: all five units shipped. The public replay CaptureWorkflow and checkpoint observer reuse the existing acquisition, inspection, export and calibration services. The console owns file-backed sessions, attempts and operations under the capture root, one background worker, restart recovery that marks unfinished work INTERRUPTED, and the local-only /api/captures routes. The Captures list and detail pages, the dataset import and calibration handoff, the MSW fixtures, navigation and the command-palette entries are in place. The Schema v2 upload label now says v3.

Review fixes: the capture local-access filter also requires a loopback Host, because a same-origin GET through the public preview tunnel carries no Origin header. Attached-session inspections take a generated operation id instead of a request-id prefix that could exceed the id bound. New market attempts use the same markets/ directory as the first one. Stage reason guidance in the UI now matches the reason codes the backend actually reports.

Verification: gradlew build --offline passed 530 tests with zero failures; the console capture suite (session, operation, catalog, path policy and local-access tests) passed again after the catalog tests were added. Frontend npm test passed 116 tests; npm run lint and npm run build passed. The pages were checked visually against an isolated mock-only frontend at desktop and phone widths, without horizontal scrolling. No live provider calls, real captures or user services were used.

Known gaps: there is no Spring MVC controller test for the error envelope and download headers, and no Testcontainers test that imports through the real catalog. Catalog idempotency is covered by a checksum-keyed fake and by the existing catalog tests. An extended market scanPages value is enforced by the market capture service's resume identity, but the console's up-front 409 check reads it from the cached inspection summary, so it can fall back to the attempt's initial value after the summary is reset. The capture source files use a dense one-line style that differs from the rest of the codebase; reformatting them is a separate cleanup.

