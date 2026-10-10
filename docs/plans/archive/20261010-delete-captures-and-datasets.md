# Delete captures and datasets

> Local-only DELETE endpoints and console actions that remove a capture session's managed files, or a dataset from the catalog, without touching attached evidence or existing runs.

Status: Delivered (approved 2026-10-10; user: "approved, implement it")
Date: 2026-10-10

## Context

Neither captures nor datasets can be removed today; the [capture console plan](archive/20261010-capture-console.md) listed capture deletion as out of scope.

- **Datasets** live in `replay_datasets` (`ReplayDatasetEntity`, id = content checksum), behind the public port `replay.ReplayDatasetCatalog` (`register`, `importSample`, `page`, `find`, `load`). Each run in `simulation_runs` keeps its own `dataset_checksum` and full `dataset_snapshot`, and its lifecycle rows (`wallet_events`, `market_observations`, `trade_intents`, `paper_orders`, `paper_fills`, `positions`) reference the run, not the dataset. No foreign key points at `replay_datasets`, so deleting a dataset row cannot break a stored run. The console still links a run to `/datasets/{datasetId}` and filters runs by dataset id.
- **Captures** are file-backed under the capture root (`console/<sessionId>/…`; `CaptureFileStore`, `CaptureSessionService`, `CaptureOperationService`).
  - Managed evidence (`console/<id>/wallet`, `markets/<attempt>`, `rates/<attempt>`) and generated exports/calibration (`console/<id>/generated/…`) sit inside the session directory.
  - Attached evidence (for example `decu-pumpfun-buys`) sits outside it and is only referenced.
  - One worker runs at most one operation at a time.
- **Exposure:** `DatasetController` is gated only by `trenching.console.enabled`. The console is also served through the public preview tunnel (`npm run serve:public`). Capture routes already refuse non-local access via `CaptureLocalAccessFilter` (loopback peer, local Host, local Origin).

## Scope

### In scope

- `DELETE /api/captures/{id}`: remove the session's metadata and every managed file under `console/<id>`; never touch attached directories.
- `DELETE /api/datasets/{id}`: remove the catalog row; keep every run and its lifecycle rows.
- Both endpoints are local-only, using the existing local-access rule.
- Console: Delete actions with a confirmation dialog on capture list/detail and dataset list/detail, and an explicit "dataset deleted" state where a run links to a removed dataset.
- Startup cleanup of a capture deletion interrupted midway.

### Out of scope

- Deleting runs (Open Question 1), bulk delete, soft delete or undo.
- Deleting attached evidence (Open Question 2).
- Any database schema change.

## Key Decisions

1. **Datasets: catalog row only.** Runs are self-contained via their snapshot, so they stay and stay viewable and comparable. Re-importing the identical JSON recreates the same checksum id and restores the run links (existing idempotent `register`).
2. **Captures: managed files only.** Deletion removes `console/<id>` and everything in it: managed wallet/market/rates evidence, generated `dataset.json`/`calibration.json`, and attempt/operation/session records. Attached directories are references and are never deleted. A dataset already imported from the capture stays in Datasets.
3. **Local-only for both.** Generalise `CaptureLocalAccessFilter` into `LocalAccessFilter`. It keeps the capture routes and adds `DELETE /api/datasets/**`, so the public tunnel or a remote peer gets 403 `LOCAL_ACCESS_REQUIRED`. Other dataset and run routes keep their current access.
4. **Capture deletion is two-step and recoverable.** First an atomic rename of `console/<id>` to `console/.deleted-<id>-<generatedId>`: the session disappears immediately, and the session-id pattern excludes names starting with `.`. Then the renamed tree is deleted recursively without following links. Leftover `.deleted-*` directories (a crash mid-delete) are removed at startup, after the worker lock is taken and before recovery.
5. **Busy rule.** Deleting a session whose operation is `RUNNING` returns 409 `CAPTURE_BUSY`. Other sessions can be deleted while the worker runs. `CaptureOperationService` tracks the active session id; today it tracks only a boolean.
6. **HTTP semantics.** 204 on success. 404 for an unknown or already-deleted id (`CAPTURE_NOT_FOUND`, `DATASET_NOT_FOUND`). The frontend treats a 404 after a confirmed delete as done. Delete is naturally idempotent in effect.

## Module Contracts

- `replay.ReplayDatasetCatalog`: add `fun delete(id: String): Boolean` (true when a row was removed). Additive; `ReplayDatasetCatalogService` implements it in a `@Transactional` service method using the repository's existing `deleteById`/`existsById`.
- `console.application.DatasetConsoleService.delete(id)` throws the existing `DatasetNotFoundException` when it returns false. `DatasetController`: `@DeleteMapping("/{id}") @ResponseStatus(NO_CONTENT)`.
- `console.capture.CaptureSessionService.delete(id)` and `CaptureFileStore.remove(id)` (rename then recursive delete) plus `CaptureFileStore.purgeDeleted()` at initialisation. `CaptureController`: `@DeleteMapping("/{id}")`, 204.
- `console.config.web.LocalAccessFilter` replaces `CaptureLocalAccessFilter`, with the same behaviour plus the dataset DELETE rule.
- Frontend: `capturesApi.remove(id)` and `datasetsApi.remove(id)`; React Query invalidation of the lists, detail, runs-by-dataset and dashboard keys.

No breaking change; no replay-format or simulation change.

## Domain Rules

1. Deleting a dataset removes exactly one `replay_datasets` row. Its runs remain listed under `/api/runs`, their reports load, and their `datasetId` still equals the checksum.
2. After deletion, `GET /api/datasets/{id}` and its observations return 404. `GET /api/runs?datasetId={id}` still lists that dataset's runs.
3. Re-registering identical content after deletion returns `created = true` with the same id, and existing runs' dataset links resolve again.
4. Deleting a capture removes `console/<id>` entirely. Files under attached paths, inside or outside the capture root, are byte-for-byte unchanged (test hashes them).
5. Deletion never follows a symbolic link or junction out of the session directory. A link inside is removed as a link, and its target is untouched.
6. A session with a `RUNNING` operation → 409 with nothing removed. A different session can be deleted during that operation.
7. An unknown id, an invalid id pattern, or a second delete → 404 / 400 with no filesystem change.
8. After deletion the session is absent from `GET /api/captures`, and reusing its creation `requestId` creates a fresh session rather than replaying the old one.
9. A crash after the rename leaves `.deleted-*`. The next startup removes it, and it never appears in the session list.
10. DELETE from a non-loopback peer, a non-local Host or a foreign Origin → 403 `LOCAL_ACCESS_REQUIRED` with nothing removed. GET and POST dataset routes are unaffected by the filter.
11. Each delete logs `INFO capture_deleted sessionId=… managedFiles=…` or `dataset_deleted datasetId=…`. No paths outside the root and no file contents are logged.

## Data Model

No schema change. The deletes rely on the existing `replay_datasets` primary key; there are no foreign keys to it.

## Transactions & Integration

Dataset delete is one `@Transactional` repository delete in `ReplayDatasetCatalogService`. Capture delete is filesystem-only, under the store lock, outside any DB transaction. No provider calls and no configuration change.

## What To Implement

1. **Dataset delete:** the catalog port and service, the console service and controller, the 404 mapping, and an integration test against PostgreSQL proving runs survive and re-import restores the link.
2. **Capture delete:** active-session tracking, `CaptureFileStore` rename/recursive delete and startup purge, the session service and controller, and tests with temporary roots including an attached directory and a link.
3. **Local-only DELETE:** generalise the filter and test the dataset DELETE refusal and the unaffected GET/POST.
4. **Console UI:**
   - Delete buttons with Mantine confirmation dialogs, each stating what is removed and what is kept.
   - After deletion, navigate to the list.
   - The run detail page shows "Dataset deleted — this run keeps its own copy" when the dataset returns 404.
   - MSW handlers and tests.
5. **Documentation:** README API table, REAL_DATA_CAPTURE (deleting a session removes managed evidence only), Postman requests, IMPLEMENTATION_STATUS/HANDOFF.

Units 1–3 are independent; 4 depends on the endpoint shapes only.

## Verification

- `ReplayDatasetCatalogIntegrationTest` (Testcontainers):
  - delete removes the row and leaves runs and their lifecycle rows;
  - a second delete returns false;
  - re-registering recreates the same id with `created = true`.
- `ConsoleApiIntegrationTest`: DELETE dataset → 204, then 404; runs-by-dataset still lists the runs.
- `CaptureSessionServiceTest`:
  - managed files removed;
  - an attached directory's file hashes are unchanged;
  - a link inside the session is not followed;
  - a running session → 409;
  - another session is deletable meanwhile;
  - unknown id → 404;
  - requestId reuse creates a new session.
- `CaptureFileStoreTest`: a leftover `.deleted-*` is purged on initialise and never listed.
- `LocalAccessFilterTest`: dataset DELETE refused for a tunnel Host or remote peer; dataset GET/POST pass; the existing capture cases still pass.
- Frontend: the confirmation dialog is required (cancel sends nothing), delete navigates to the list, and the run page shows the deleted-dataset state.
- Commands: `./gradlew.bat build --offline`, then `npm test`, `npm run lint` and `npm run build`.

## Documentation Sync

README console/API section; REAL_DATA_CAPTURE capture-console section; Postman collection; IMPLEMENTATION_STATUS; HANDOFF. No ADR: no fidelity, reproducibility or contract-breaking decision.

## Acceptance Criteria

- `DELETE /api/datasets/{id}` → 204. The row is gone; `GET /api/runs?datasetId={id}` still returns its runs; re-upload gives the same id.
- `DELETE /api/captures/{id}` → 204. `console/<id>` no longer exists; an attached `decu-pumpfun-buys` directory is unchanged.
- Through a non-local Host, both DELETEs → 403 and nothing changes.
- A session with a running operation → 409.
- The console shows confirmation dialogs and the deleted-dataset state on run pages.

## Open Questions

1. **Delete a dataset's runs too?** Runs are self-contained and remain useful for comparison. **Recommend no**; run deletion would be a separate, explicit feature.
2. **Offer to delete attached evidence when deleting a capture?** Attached directories are often your only copy of CLI captures. **Recommend no**; delete those directories by hand if you really want them gone.
3. **Allow dataset deletion only from the local machine?** The public preview tunnel exposes the dataset API to anyone with its URL. **Recommend yes**, local-only like captures.

## Delivered

2026-10-10, with all three Open Questions resolved as recommended: runs are kept, attached evidence is kept, and both deletes are local-only.

- **Datasets:** `ReplayDatasetCatalog.delete` uses a bulk `@Modifying` delete in `ReplayDatasetTransaction`. `DELETE /api/datasets/{id}` returns 204, or 404 `DATASET_NOT_FOUND`.
- **Captures:** `CaptureFileStore.remove` renames the session to `.deleted-…`, then deletes it recursively. Startup purges leftovers, and `CaptureOperationService` tracks the running session. `DELETE /api/captures/{id}` returns 204, 404, or 409 `CAPTURE_BUSY`.
- **Access:** `CaptureLocalAccessFilter` became `LocalAccessFilter`, now active in every profile with the console enabled, and also guards `DELETE /api/datasets/**`.
- **Console:** confirmation dialogs on the capture and dataset list and detail pages, plus the deleted-dataset state on the run page.

Review finding fixed during implementation: Java's tree walk treats a Windows directory junction as a normal directory, so the recursive delete would have descended into a junction's target. Deletion now removes reparse points and links as entries and skips their subtree. A test with a real junction (`mklink /J`) failed without the guard and passes with it.

Verification:
- Backend build passed: 562 tests, 0 failures, 0 skipped, including the new PostgreSQL integration tests: runs survive deletion, and re-registering restores the same id.
- Frontend `npm test` passed 123 tests; lint and build passed.
- Documentation updated: README, REAL_DATA_CAPTURE and the Postman collection (a delete request at the end of the Console API folder).

