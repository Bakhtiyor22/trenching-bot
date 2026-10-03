# Testing Console and Monitoring Dashboard

> A local-only React console to manage replay datasets and runs, inspect results with charts, check
> Solana connectivity, and watch an in-app monitoring dashboard, backed by a new `/api` in the
> Spring app.

Status: Approved (2026-10-02; open questions 1, 2, 5 answered yes — dependencies approved, local profile may enable the console, port changes committed first; 3, 4, 6 per recommendation)

## Context

Today the only way to use the system is the command line: `ReplayCommand` (in-memory) and
`PersistedReplayCommand` (writes to PostgreSQL), both in `replay/api/`, both hard-wired to
`SimulationConfig.mvp()` and a dataset file path. The app exposes nothing over HTTP except Actuator
`health` and `info`. Results are only visible as a JSON report file or by querying tables.

What exists and can be reused:

- `PersistedReplayService.execute(dataset, config)` and `SimulationRunLedger`: deterministic run
  ids, an idempotent persist, a duplicate-writer recovery path, and `SimulationRunConflictException`.
- `ReplayDatasetLoader`: full validation of a v1 dataset, including the checksum.
- Read ports for every stored record: `WalletEventStore`, `MarketObservationStore`,
  `TradeIntentStore`, `PaperOrderStore`, `PositionStore` (all keyed by `runId`).
- `SimulationConfig.of(...)`: validates every tunable parameter.
- `SolanaDevnetLiveTest`: the `RestClient` JSON-RPC pattern (HTTP/1.1, explicit timeouts).

Decisions taken with the user on 2026-10-02:

- **Frontend:** React SPA in `frontend/`.
- **Monitoring:** an in-app dashboard, not Grafana.
- **Access:** local only, no login.
- **Solana:** a read-only connectivity panel.

## Scope

### In scope

- **Datasets:** upload and validate v1 replay datasets, import the bundled sample, list them, and
  view one (counts, slot range, pairs, a price chart).
- **Runs:**
  - start a persisted run from a dataset and an edited `SimulationConfig`;
  - list runs with summary numbers;
  - open a run in detail: header and config, a price timeline with entry and exit markers, the
    +1/+2/+3 scenario results, and tables of every stored record;
  - download the report.
- **Monitoring dashboard:** app/DB/Solana health tiles, run KPIs, fill vs rejection by reason,
  realized P&L per run, and JVM/HTTP sparklines.
- **Solana panel:**
  - shows whether an RPC is configured, the cluster (from the genesis hash) and the host only;
  - an on-demand check runs `getHealth`, `getVersion`, `getSlot` and reports latency;
  - an optional address check runs `getSignaturesForAddress` and `getTransaction` and reports
    whether the fields the wallet normalizer needs are present.
- **Backend `/api`:** endpoints, DTOs and mappers for the above, plus custom Micrometer metrics.
- **Local use:** the console runs via the Vite dev server against the local profile. An opt-in
  Gradle task bundles it into the jar.

### Out of scope

- Authentication, multi-user access, and enabling the console in `prod`. It is off by default and
  stays off until a separate plan adds auth.
- Live wallet tracking, live prices, WebSocket streams. None of these exist yet; the console cannot
  manage them.
- Deleting or editing datasets or runs. Runs are immutable experiments (REPLAY_FORMAT.md).
- Editing strategy name/version or simulation semantics. Only `SimulationConfig` parameters are
  editable.
- Prometheus/Grafana, persisted metric history, alerting.

## Key Decisions

1. **A new `console` module owns the HTTP API and the dashboard read model.** Its controllers are
   thin. `ConsoleService` and `DashboardService` compose other modules **only through their public
   ports**. The rejected alternative was controllers in each module: that spreads one UI's
   transport across seven modules and mixes concerns.
2. **The `replay` module owns datasets and runs**, through two new public ports:
   `ReplayDatasetCatalog` and `SimulationRuns`. The console never touches replay entities or
   repositories.
3. **Datasets are stored**, so they can be managed and rerun with different settings. Identity is
   the dataset checksum (already the canonical fingerprint). The name is a label, not identity.
   `SimulationRunLedger.persist` also registers its dataset in the same transaction, so every run's
   dataset appears in the catalog, including CLI runs.
4. **Run summaries are stored on `simulation_runs` at persist time.** The run list then needs one
   bounded query instead of parsing report snapshots or N+1 store reads.
5. **Runs are synchronous.** Input is bounded at 10,000 records per array (`ReplayLimits`), and
   replay is in-memory and fast, so `POST /api/runs` returns the finished run. If measured run time
   exceeds about 5 s, revisit with a job table and polling (Open Question 4).
6. **Decimals cross the API as strings and are never JSON numbers.** This covers prices, P&L, fees,
   quantities and raw `u64` amounts. Slots stay JSON numbers; they are safe below 2^53, and current
   slots are around 5e8. The frontend shows the exact strings in tables and converts to `Number`
   only to plot charts.
7. **Monitoring history is kept in the browser.** The dashboard polls `/api/dashboard` every 5 s,
   and the page keeps the last 30 minutes of JVM/HTTP samples for sparklines. There is no
   server-side time series and no new storage. Domain KPIs come from the database, so they survive
   restarts.
8. **The Solana probe is on demand, never background-polled.** Results are cached for 30 s and
   concurrent requests are coalesced, so repeated clicks cannot burn a paid RPC quota. The RPC URL
   is a secret (`strict-rules` A3): it is read from `SOLANA_RPC_URL` and only the host is ever
   returned or logged.
9. **The console is off unless explicitly enabled.** `trenching.console.enabled` defaults to
   `false`, so `/api/**` and the bundled UI are not registered. The local profile sets it to
   `true`.
10. **Frontend stack (new dependencies, approval needed):**
    - React 19 + TypeScript + Vite, React Router;
    - TanStack Query for fetching and polling;
    - Mantine for UI components (forms, tables, dark mode, notifications);
    - ECharts via `echarts-for-react` for charts (zoomable time series, markers, many points);
    - Vitest + Testing Library + MSW for tests.

    The rejected alternative was Recharts: lighter, but weaker with dense slot series and markers.
11. **The default backend build stays Node-free.** `./gradlew build` does not need Node. An opt-in
    `-PwithConsole` builds the frontend and copies it to `static/console/`. CI gets a separate
    frontend job.
12. **Configurable runs are an ADR-level change.** ADR 0001 fixed the MVP parameters (100 notional,
    10 bps fee, 50 bps slippage, 2/2 wait/age, hold 5). The console lets users vary them per run.
    Reproducibility holds because the configuration hash is part of the run id. New ADR:
    `docs/adr/20261002-configurable-replay-runs.md`. The `mvp()` values remain the form defaults
    and the CLI behaviour.

## Module Contracts

All additions; **no existing contract changes signature**. The replay fixture schema is unchanged.

### `replay` (public, new)

```kotlin
interface ReplayDatasetCatalog {
    fun register(name: String, json: InputStream): DatasetRegistration   // idempotent by checksum
    fun importSample(sample: SampleDataset): DatasetRegistration
    fun page(page: Int, size: Int): PageResult<DatasetSummary>
    fun find(id: String): DatasetSummary?                               // id = checksum
    fun load(id: String): ReplayDataset                                 // throws DatasetNotFoundException
}
data class DatasetRegistration(val dataset: DatasetSummary, val created: Boolean)
data class DatasetSummary(val id: String, val name: String, val trackedWallet: WalletAddress,
    val schemaVersion: Int, val transactionCount: Int, val observationCount: Int,
    val firstSlot: Slot?, val lastSlot: Slot?, val pairs: List<MarketPair>, val createdAt: Instant)
enum class SampleDataset { WEEKLY_BUY_V1 }

interface SimulationRuns {
    fun start(datasetId: String, config: SimulationConfig): RunStart    // idempotent by run id
    fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary>
    fun find(runId: String): RunSummary?
    fun report(runId: String): String                                   // stored report JSON
}
data class RunStart(val run: RunSummary, val created: Boolean)
data class RunSummary(val id: String, val datasetId: String, val engineVersion: String,
    val configurationHash: String, val resultChecksum: String, val quoteMint: MintAddress,
    val scenarioCount: Int, val filledCount: Int, val rejectedCount: Int,
    val openCount: Int, val closedCount: Int, val realizedPnl: BigDecimal, val createdAt: Instant)
```

`PageResult<T>(items, page, size, total)` goes in `common`. It is a tiny shared value type: no
Spring `Page` crosses a port.

### `papertrading` (public, addition)

```kotlin
interface PaperOrderStore {   // existing; one method added
    fun rejectionCounts(): Map<RejectionReason, Long>   // across all runs, for the dashboard
}
```

### `console` (new module; nothing public that other modules depend on)

- `api/`: `DatasetController`, `RunController`, `DashboardController`, `SolanaController`, plus
  `ConsoleExceptionHandler` (`@RestControllerAdvice`, scoped to the console controllers).
- `api/dto/`: request and response `data class`es; `mapper/`: MapStruct mappers (CLAUDE.md rule).
- `application/`: `ConsoleService` (run detail composition), `DashboardService`, `SolanaProbeService`.
- Port `SolanaProbe` with the adapter `solana/JsonRpcSolanaProbe` (`RestClient`) and a fake adapter
  for tests.
- `config/`: `ConsoleProperties` (`trenching.console`) and `SolanaRpcProperties`
  (`trenching.solana`).

### HTTP API (`/api`, JSON; registered only when `trenching.console.enabled=true`)

| Method & path | Purpose | Responses |
|---|---|---|
| `GET /api/datasets?page&size` | List datasets, newest first | 200 |
| `POST /api/datasets` (multipart `file`, `name`) | Upload and validate | 201 created · 200 already existed · 400 invalid · 413 too large |
| `POST /api/datasets/samples/{sample}` | Import a bundled sample | 201 · 200 |
| `GET /api/datasets/{id}` | Summary and pairs | 200 · 404 |
| `GET /api/datasets/{id}/observations?pair` | Price series for the chart | 200 · 404 |
| `GET /api/config/defaults` | `mvp()` values and allowed ranges | 200 |
| `POST /api/runs` `{datasetId, config}` | Start a run | 201 created · 200 already existed · 400 invalid config · 404 dataset · 409 conflict |
| `GET /api/runs?page&size&datasetId` | Run list with summaries | 200 |
| `GET /api/runs/{id}` | Header, config, scenarios (intent, fill/rejection, position, close) | 200 · 404 |
| `GET /api/runs/{id}/events` · `/observations` · `/orders` · `/positions` | Detail tables | 200 · 404 |
| `GET /api/runs/{id}/report` | Stored report JSON (download) | 200 · 404 |
| `GET /api/dashboard` | Health, KPIs, rejection counts, recent runs' P&L, JVM/HTTP gauges | 200 |
| `GET /api/solana/status` | Configured?, host, cluster, last check | 200 |
| `POST /api/solana/check` | Run the connectivity check | 200 (result carries ok / rate limited / timeout / error) |
| `POST /api/solana/address-check` `{address}` | MVP field check on an address | 200 · 400 invalid address · 409 not configured |

Errors use one body shape: `{ "code": "DATASET_INVALID", "message": "...", "fields": {...} }`.

## Domain Rules

1. **Dataset identity.** A dataset's id is `DatasetFingerprint.checksum`. Uploading the same
   content twice, even with a different name, returns the existing dataset with `created=false` and
   leaves exactly one row. The name is not changed by a re-upload.
2. **Dataset validation is the loader's.** Anything `ReplayDatasetLoader` rejects is rejected with
   400 and the loader's message, and nothing is stored. Concretely: a wrong `schemaVersion`, a
   checksum mismatch, a non-base58 or wrong-length address/signature, a raw amount above `u64`,
   decimals outside 0..255, a non-positive price, a timestamp finer than microseconds, or more than
   10,000 records in an array.
3. **Upload size.** Uploads over 20 MB are rejected with 413 before parsing. Files that are not
   UTF-8 JSON, or empty, are rejected with 400.
4. **Run identity is unchanged.** It is `StableHash(engineVersion, datasetChecksum,
   configurationHash)`. Starting the same dataset and config twice returns the same run with
   `created=false`, with no second set of intents/fills/positions. Two concurrent identical starts
   converge on one run (the existing ledger recovery path). A different config is a different run.
5. **Editable parameters** are exactly those of `SimulationConfig.of`: notional, `feeBps`,
   `slippageBps`, `maxWaitSlots`, `maxAgeSlots`, `holdingSlots`, `quoteMint`. Invalid values
   return 400 with one entry per field, using `SimulationConfig.of`'s existing bounds:
   - notional ≤ 0, or 0 after scale 12;
   - bps outside 0..9999;
   - wait or age outside 0..1000;
   - holding ≤ 0;
   - a `quoteMint` that isn't a valid 32-byte base58 address.

   `strategyName`/`strategyVersion` are not editable (`confirmed-buy`/`1`); behaviour comes from
   code, not labels.
6. **Notional crosses the API as a decimal string.** A JSON number, or a string with an exponent,
   is rejected with 400. It is normalized to scale 12 with `HALF_EVEN` by
   `DecimalPolicy.scale`, unchanged.
7. **Run summaries are exact and computed in the ledger from the report:**
   - `scenarioCount` = number of scenarios;
   - `filledCount` + `rejectedCount` = `scenarioCount`;
   - `closedCount` + `openCount` = number of positions;
   - `realizedPnl` = exact sum of `close.realizedPnl` over closed positions (scale 12, no rounding
     needed; sum of scale-12 values), in units of the run's `quoteMint`;
   - a run with no closed positions has `realizedPnl = 0.000000000000`.
8. **Summaries are deterministic.** The same report always produces the same summary. `createdAt`
   is the only clock-dependent field and comes from the injected `Clock` (existing).
9. **Read-only toward Solana.** The probe calls only `getHealth`, `getVersion`, `getSlot`,
   `getGenesisHash`, `getSignaturesForAddress`, `getTransaction`. It never constructs or sends a
   transaction.
10. **The probe classifies outcomes** as a sealed `ProbeOutcome`: `Ok(latencyMs, version, slot,
    cluster)`, `NotConfigured`, `RateLimited`, `Timeout`, `RpcError(code, message)` or
    `Unavailable(reason)`.
    - A JSON-RPC error with HTTP 200 is `RpcError`, not `Ok`.
    - HTTP 429 is `RateLimited`, with no retry.
    - Connect timeout 5 s and read timeout 10 s are both configurable.
11. **The probe never exposes the URL.** Responses and logs contain only the host. A URL with a
    query string (an API key) is never echoed anywhere, error messages included.
12. **The cluster is named from the genesis hash** (devnet / testnet / mainnet-beta). An unknown
    hash is `unknown`, not an error.
13. **Address check.**
    - The address must be a valid 32-byte base58 address, otherwise 400.
    - The check uses `limit 5` and the newest successful signature.
    - It reports which normalizer fields are missing: slot, blockTime, `meta.err`, signer flags,
      `postTokenBalances` mint/owner/raw amount/decimals.
    - `maxSupportedTransactionVersion` is 1 (devnet carries version 1 transactions, verified on
      2026-10-02).
    - No successful transaction in the 5 is a reported result, not an error.
14. **Probe calls are rate-guarded.** At most one probe call is in flight. A request within 30 s of
    the last result returns the cached result, marked `cached=true`.
15. **The dashboard never fails because Solana does.** An unconfigured or failing RPC shows on the
    Solana tile only. `/api/dashboard` and `/actuator/health` stay `UP` (integration-patterns
    "Health").
16. **Dashboard KPIs are computed from stored data with bounded queries:**
    - total runs, and runs in the last 24 h (injected `Clock`);
    - scenarios filled and rejected, and rejections by `RejectionReason`;
    - positions open and closed;
    - realized P&L of the 20 most recent runs.

    P&L across different quote mints is never summed together; it is grouped by `quoteMint`.

## Data Model

Schema changes are entity changes (no Flyway). All additive.

**New `replay_datasets`** (`replay/infrastructure/persistence/ReplayDatasetEntity`):

| Column | Type | Constraint |
|---|---|---|
| `id` | `varchar(64)` | PK (dataset checksum) |
| `name` | `varchar(128)` | not null |
| `tracked_wallet` | `varchar(44)` | not null |
| `schema_version` | `integer` | not null |
| `transaction_count`, `observation_count` | `integer` | not null |
| `first_slot`, `last_slot` | `bigint` | nullable (empty dataset) |
| `snapshot` | `text` | not null (canonical dataset JSON) |
| `created_at` | `timestamptz` | not null |
| `version` | `bigint` | not null (`@Version`) |

Index `idx_replay_datasets_created_at (created_at, id)`.

**`simulation_runs`, added columns:** `quote_mint varchar(44)`, `scenario_count integer`,
`filled_count integer`, `rejected_count integer`, `open_count integer`, `closed_count integer`,
`realized_pnl numeric(38,12)`. All are `not null`. Adding `not null` columns to a populated table
fails under `ddl-auto: update`, so **existing local databases must be reset**
(`docker compose down -v`). This is acceptable because runs are reproducible from their datasets.

Indexes `idx_simulation_runs_created_at (created_at, id)` and
`idx_simulation_runs_dataset (dataset_checksum, created_at)`.

Reads:
- Dashboard aggregates use one grouped query per KPI, over the summary columns and
  `paper_orders.rejection_reason`.
- Lists are paged, with `size` capped at 100.
- Detail tables reuse the existing per-run store reads, which are bounded by `ReplayLimits`.

**Production SQL:** none is applied now. The console is disabled in prod and no prod schema exists
yet (see Open Question 3). The DDL for the two changes goes into `docs/schema/` with the
implementation.

## Transactions & Integration

- **Dataset upload:** parse and validate outside any transaction. Insert in a short transaction. A
  unique violation on `id` means it already exists: re-read and return `created=false`.
- **Run start:**
  - `load(datasetId)` is a read;
  - `ReplayRunner.run` is in memory with no transaction;
  - `SimulationRunLedger.persist` is the existing single transaction, extended to write the
    summary columns and upsert the dataset row.
- **Solana probe:** a `RestClient` call outside any transaction, with HTTP/1.1 (as in
  `SolanaDevnetLiveTest`), no retries, single-flight and a 30 s cache.
- **Configuration:**

| Property | Env var | Default | Notes |
|---|---|---|---|
| `trenching.console.enabled` | `TRENCHING_CONSOLE_ENABLED` | `false` | `application-local.yml` sets `true` (editing it needs approval) |
| `trenching.solana.rpc-url` | `SOLANA_RPC_URL` | none (optional) | Secret; unset means the panel shows "not configured" |
| `trenching.solana.connect-timeout` | | `5s` | |
| `trenching.solana.read-timeout` | | `10s` | |
| `trenching.solana.cache-ttl` | | `30s` | Probe result cache |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | | `20MB` | `application.yml` |

- **Metrics (Micrometer, already on the classpath through Actuator):**
  - counter `trenching.replay.runs{outcome=created|existing|failed}`;
  - timer `trenching.replay.duration`;
  - counter `trenching.datasets.uploads{outcome=created|existing|invalid}`;
  - timer `trenching.solana.probe{outcome=...}`.

  The dashboard reads these and JVM/HTTP meters from `MeterRegistry`. The `/actuator/metrics`
  exposure is not changed.

## What To Implement

1. **Replay catalog and stored summaries.** Delivers `ReplayDatasetCatalog`, the dataset entity,
   the summary columns, the ledger change, `SimulationRuns`, and moving
   `weekly-buy-v1.json` to `src/main/resources/replay/samples/`. The tests, the Gradle `replay`
   tasks and the docs follow the move. *Foundation; everything else depends on it.*
2. **Console REST API for datasets and runs.** Controllers, DTOs, mappers, the error handler, the
   `trenching.console.enabled` gate and the metrics. *Depends on 1.*
3. **Dashboard read model.** `DashboardService` plus the `PaperOrderStore.rejectionCounts`
   addition. *Depends on 1; can run in parallel with 2 if the controller file is split.*
4. **Solana probe.** `SolanaProbe` port, `JsonRpcSolanaProbe`, the outcome taxonomy, cache and
   single-flight, and the status/check/address-check endpoints. *Independent of 1–3.*
5. **Frontend foundation.**
   - Vite + React + TS scaffold, Mantine theme (light/dark), layout and navigation, API client and
     types, TanStack Query;
   - the dev proxy to `VITE_API_TARGET` (default `http://localhost:8080`);
   - decimal-string formatting helpers;
   - ESLint/Prettier, Vitest + MSW.

   *Can start against MSW mocks in parallel with 2–4.*
6. **Datasets and new-run screens.**
   - Datasets page: upload with drag-and-drop and inline validation errors, sample import, list;
   - dataset detail with a price chart per pair;
   - new-run form with `mvp()` defaults, client-side range hints and server field errors;
   - after starting, navigate to the run, with an "already existed" notice.
7. **Runs list and run detail.**
   - Run detail shows a price-vs-slot chart with markers for the source buy, the entry fill per
     offset, and exits;
   - scenario cards for +1/+2/+3 (filled or rejected with reason; entry, exit, P&L);
   - tabbed tables (events with ignored reasons, observations, orders/fills, positions) and the
     report download.
8. **Dashboard and Solana screens.** KPI and health tiles, rejection-reason bar chart, P&L-per-run
   bar chart, JVM heap and HTTP sparklines from 5 s polling, the Solana status card with a check
   button, and the address check form.
9. **Packaging and CI.** The `-PwithConsole` Gradle task (npm build, copied to `static/console/`
   with an SPA fallback), a `frontend` CI job (lint, test, build), README and Postman updates.
   *Last.*

Parallel-safe split: 1 → (2, 3, 4 in different packages) and 5 alongside them, then 6–8 by
screen, then 9.

## Verification

**Backend unit tests**
- `SimulationRunsSummaryTest`:
  - the sample dataset gives 3 scenarios, 3 filled, 0 rejected, 3 closed, 0 open, and
    `realizedPnl = 26.089701492540` (3 × 8.696567164180, ADR 0001);
  - the no-entry-observation variant gives 3 rejected, 0 positions, P&L `0.000000000000`;
  - the missing-exit variant gives 3 open and P&L 0.
- `ConsoleConfigRequestTest`: every out-of-range field yields its own field error; notional as a
  JSON number is rejected; the default request equals `SimulationConfig.mvp()`.
- `JsonRpcSolanaProbeTest` (`MockRestServiceServer`):
  - `Ok` with latency;
  - an RPC error under HTTP 200;
  - 429 → `RateLimited` with exactly one HTTP call;
  - read timeout → `Timeout`;
  - unconfigured → `NotConfigured` with zero HTTP calls;
  - an API-key URL never appears in the outcome or in captured log output;
  - genesis hash → cluster mapping, including unknown.
- `SolanaProbeServiceTest`: a second call within the TTL is cached; concurrent calls make one
  request.
- `AddressCheckTest`: missing `postTokenBalances` is reported as missing, a version 1 transaction
  is accepted, and five failed signatures give "no successful transaction".
- `DashboardServiceTest` (fixed `Clock`): the 24 h window boundary, and P&L grouped per quote mint.

**Integration tests (Testcontainers)**
- `ReplayDatasetCatalogIntegrationTest`: register gives `created`; re-register with another name
  gives the same id, `created=false`, one row and the name unchanged; an invalid upload stores
  nothing.
- `SimulationRunsIntegrationTest`:
  - start gives 201 semantics and persisted summary columns;
  - repeating the start gives the same id, `created=false`, and unchanged row counts in all seven
    tables;
  - a different `feeBps` gives a different id;
  - an unknown dataset gives `DatasetNotFoundException`;
  - a CLI-persisted run's dataset appears in the catalog.
- `ConsoleApiIntegrationTest` (MockMvc, full context):
  - every endpoint's status codes from the API table;
  - decimals serialized as JSON strings;
  - with `trenching.console.enabled=false`, `/api/runs` returns 404 and `/actuator/health` stays
    200.

**Frontend tests (Vitest + MSW)**
- The config form shows server field errors next to the right input.
- Decimal strings render exactly, with no float rounding in tables.
- The upload shows the loader's message on 400.
- Run detail renders a rejected scenario with its reason, and an open position with
  "exit unavailable".
- The dashboard renders with the Solana tile "not configured" while the other tiles are healthy.

**Commands**
```bash
./gradlew test
```
```bash
npm --prefix frontend ci
```
```bash
npm --prefix frontend run test
```
```bash
npm --prefix frontend run build
```
```bash
./gradlew build -PwithConsole
```

Manual check: run `bootRun` (local profile) and `npm --prefix frontend run dev`, import the sample,
start a run, and see P&L 26.089701492540 on the run detail and the dashboard.

## Documentation Sync

- **README:**
  - a new "Console" section (how to run it, the enable flag, `SOLANA_RPC_URL`, `VITE_API_TARGET`);
  - the configuration variable table;
  - the replay default dataset path moves to `src/main/resources/replay/samples/`;
  - a note that local databases must be reset once for the new columns.
- **`docs/REPLAY_FORMAT.md`:** the sample path, and that datasets are stored by checksum.
- **ADR `docs/adr/20261002-configurable-replay-runs.md`:** the decision to allow per-run
  parameters; `mvp()` stays the default; reproducibility comes from the config hash.
- **`docs/schema/`:** DDL for `replay_datasets` and the new `simulation_runs` columns.
- **Postman:** the application collection gains the `/api` requests.

## Acceptance Criteria

1. With `trenching.console.enabled=true`, importing the sample returns 201. Importing it again
   returns 200 with the same `id`, and `replay_datasets` has one row.
2. `POST /api/runs` with the sample and default config returns 201 and a run with
   `realizedPnl = "26.089701492540"`, `filledCount = 3`, `closedCount = 3`. Repeating it returns
   200, the same `id` and `created=false`, and `positions` still has exactly 3 rows for that run.
3. `POST /api/runs` with `feeBps = 10000` returns 400 with `fields.feeBps` set, and no row is
   written.
4. Uploading a dataset with a tampered checksum returns 400 with
   `code=DATASET_INVALID`, a message containing "Replay checksum mismatch", and no row.
5. With `SOLANA_RPC_URL` unset, `/api/solana/status` reports `configured=false`, the dashboard
   shows the Solana tile as not configured, and `/actuator/health` is `UP`.
6. With a fake RPC returning 429, `/api/solana/check` returns `outcome=RATE_LIMITED` after exactly
   one HTTP request. A second call within 30 s makes no request and returns `cached=true`.
7. With `trenching.console.enabled=false` (the default, and therefore prod), `GET /api/runs`
   returns 404.
8. In the browser, run detail shows the price chart with three entry markers (slots 101/102/103)
   and three exit markers, and three scenario cards each showing P&L `8.696567164180`.
9. `./gradlew build` succeeds without Node installed. CI runs the frontend job green.

## Open Questions

1. **Approve the new dependencies?** On the frontend: React, Vite, TypeScript, Mantine, TanStack
   Query, React Router, ECharts, Vitest, Testing Library and MSW. Backend: none; Micrometer and
   `RestClient` are already present. *Recommend: approve as listed.*
2. **May I edit `application-local.yml`** to set `trenching.console.enabled: true`? It's an "ask"
   file. *Recommend: yes. Otherwise the console needs an env var every run.*
3. **Production schema.** `prod` validates and no prod schema has ever been created. When is it
   created, and by whom? *Recommend: out of scope here, because the console is off in prod. Track
   it with the auth plan that would enable a shared console.*
4. **Synchronous runs.** Fine at the current 10,000-record bound. *Recommend: keep synchronous;
   add a job/poll model only if a real dataset takes over about 5 s.*
5. **Your uncommitted port changes** (app on 8081, DB on 5433) affect the Vite proxy default and
   the README. *Recommend: commit them first, or tell me the intended defaults.* The plan uses
   `VITE_API_TARGET` so either works.
6. **Deleting datasets and runs.** Excluded, because runs are immutable experiments. *Recommend:
   keep excluded. Add "archive/hide" later if the lists get noisy.*

## Delivered

Delivered 2026-10-03 on `feature/persisted-replay`. All feature changes remain uncommitted.

### What shipped

- The checksum-addressed dataset catalog, immutable configurable runs, persisted summary queries,
  console HTTP API, monitoring dashboard and on-demand read-only Solana probe.
- The React console screens for dashboard, datasets and dataset detail, new runs, runs and run
  detail, and Solana status. Decimal strings remain exact; dashboard polling is five seconds with
  up to 30 minutes of browser history. Route modules and the shared chart are loaded separately.
- Opt-in `build -PwithConsole` runs npm and bundles `static/console/`. Enabled extensionless
  routes use the SPA fallback; missing assets return 404. Disabling the console blocks both API
  and bundled resources. Plain builds register no npm tasks and remove stale bundled resources.
- A separate frontend CI job, README Console commands/configuration/reset note, replay format
  documentation, configurable-run ADR, reference DDL and Postman console requests. Optional
  Postman provider probes are skipped by default.

### Review and fixes

One full review covered tracked changes and new feature files, followed by one fix pass. Regression
tests first reproduced concurrent callers both claiming creation, UTF-16/32 upload acceptance,
short/decoded credential exposure, unsafe exception-chain logging, decimal rounding disagreement,
and a stored position reason being replaced by report JSON.

Creation status now comes from persistence, including duplicate-writer recovery. Upload registration
rejects malformed/non-UTF-8 bytes before parsing. RPC redaction uses one replacement pass for raw
and decoded sensitive components; unexpected failure logs retain safe exception type/stack data.
Frontend notional validation follows scale-12 HALF_EVEN with integer arithmetic and submits the
original decimal string.

All nine handoff items were resolved: one error envelope/mapper, shared console gating, strict
UTF-8, shared strategy bounds, a serialization-safe order-id accessor, additive reads of persisted
position exit reasons, documented snapshot duplication, shared row-count test support and qualified
clocks. Advice controller scopes are disjoint, so extra ordering is unnecessary. The existing
aggregate boundary test now overrides the named clock bean explicitly; its assertions were retained.

### Accepted deviations and schema

- `replay_datasets.pairs` stores distinct pairs so list queries do not parse snapshots.
- `SimulationRuns.totals(createdSince)` supplies dashboard aggregates.
- The frontend obtains dashboard Solana status from `GET /api/solana/status`; polling does not
  trigger provider probes.
- Local defaults are app 8081, PostgreSQL 5433 and Vite 5173. Documentation reflects these ports.
- `simulation_runs.dataset_snapshot` intentionally remains beside the catalog snapshot. Removing
  it needs a separate approved schema change.

The approved schema consists of `replay_datasets` and seven NOT NULL summary columns on
`simulation_runs`, with entity-declared keys/indexes. The JPA entities remain authoritative; the
SQL in `docs/schema/V20261002191025__replay_datasets_run_summaries.sql` is reference DDL, not a
Flyway migration. Production must apply compatible DDL before validation. A populated database
predating the columns needs an approved reset/data-preservation decision. This continuation made
no additional entity/schema change and did not reset the local database or edit local/prod YAML.

### Verification

- `export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10"; ./gradlew build --console=plain`:
  **passed, 174 tests, zero failures/errors**, with Docker-backed Testcontainers. The first final
  build exposed the old test-clock override; after correcting that fixture, the complete build
  passed in 53 seconds. Unix-wrapper line endings were temporarily normalized for Git Bash and
  then restored to their original checkout format, with no wrapper content change.
- In `frontend/`, `npm.cmd run lint`, `npm.cmd run test`, `npm.cmd run build`:
  **all passed**, 13 test files / 63 tests; the final test run took 20.78 seconds. No heap-size
  increase was used. Concurrent jsdom workers are bounded at four.
- `build -PwithConsole` via the Windows wrapper and exported Java 21: **passed before review
  fixes, 169 tests, zero failures/errors**. The jar had 28 console entries including index and
  23 JS assets. Subsequent plain packaging plus enabled/disabled resource checks passed five
  tests and produced a jar with zero console entries. Plain build dry-run had no npm tasks.
  Packaging code was unchanged by the fix pass; the final frontend production build passed.
- Targeted RED/GREEN checks covered the review regressions, simultaneous starts with stable
  seven-table counts, stored exit reasons, a second clock bean and exact rounding boundaries.
- Local HTTP checks through `http://127.0.0.1:5173` used the actual Vite proxy and backend on
  8081: health UP, sample import/run start 201, repeat import/run start 200 with unchanged ids,
  one dataset and one run, three orders and three closed positions, total P&L
  `26.089701492540`, each position `8.696567164180`, entry slots 101/102/103 and exit slots
  106/107/108. Dataset/run lists and detail, observations, events, report attachment, duplicate
  multipart upload and dashboard passed. Invalid upload and fee returned 400 with their expected
  error envelopes and no extra records. Solana status reported `configured=false`.
- Postman JSON and request-script syntax were checked statically; the collection itself was not
  executed. No live provider was called, no secrets were read, and no git state was changed.

### Remaining verification limits

The historical three-file heap crashes could not be reproduced in the working tree received;
it already had the chart mock matching the modular import and more tests than the handoff reported.
An observed full-suite resource-contention timeout passed alone and with four workers. This does
not establish the cause of the historical crashes.

Hosted CI has not run because no push was authorized. Interactive browser/canvas checks were
blocked by a saved browser permission setting; API checks through Vite and frontend tests passed.
The destructive reset/browser end-to-end procedure was replaced by the local HTTP check because
the existing database was compatible. No reset decision was needed. The finished backend jar and
frontend development server were left running at the user's request; Solana is unconfigured in
this backend process.

### Browser runtime follow-up, 2026-10-03

The user reported React's invalid element error in `Chart`. Vite's development optimizer exported
the CommonJS object from `echarts-for-react/lib/core`; SSR tests and the production bundler unwrapped
that export, and mocked screen tests hid the mismatch. The chart now imports the package's ES module
core through a small shared binding. Its mock uses the same ES module path.

A regression test first reproduced the object export using the actual Vite development optimizer,
then passed with the ES module entry. A second test renders the real component on the server without
the package mock. The latest frontend lint, all **65 tests in 14 files**, and production build passed;
the full test run took 16.78 seconds. The running Vite server was checked to serve the new ES module
binding with HTTP 200, and backend health remained UP. No backend, dependency, schema or configuration
change was needed. Interactive browser verification remains blocked by the saved permission setting.
This confirmed import defect does not establish the cause of the historical heap crashes.
