# Implementation status — 2026-10-01

## Real capture increment (2026-10-03)

The user approved [bounded real wallet capture and replay](plans/20261003-real-wallet-replay.md) for the supplied mainnet wallet's latest 100 transactions. Its first deliverable is opt-in history acquisition plus offline artifact inspection; see [instructions](REAL_DATA_CAPTURE.md) and [decision](adr/20261003-bounded-wallet-history-capture.md). Successful signer transactions remain unclassified until actual sanitized examples establish the first supported decoder. Historical slot-aware prices and an importable real replay dataset are not delivered yet. Capture was reviewed and fixed on 2026-10-03, and the offline `./gradlew build` passed with 234 tests. The user's live runs then showed that mainnet uses v1 transactions, which led to further fixes, and that the latest 100 references are failed third-party transactions; see the plan's Progress section. Amendment A (successful-reference discovery) and unit 2 (the Pump.fun bonding-curve buy decoder) are implemented, and the build passes with 267 tests. One real buy from the capture decodes and normalizes. Unit 3 (market capture and observation extraction, Amendments B and C) is implemented; the user's live market capture completed with 17 observations. Unit 4's offline export (`exportRecordedReplay`) is implemented, and the real dataset replays to three closed paper positions; the build passes with 333 tests. Console import of the real dataset by the user and the final review (unit 5) remain. No schema change, agent live-provider call, paid upgrade or environment-file access is authorized by this increment.

The original WEEKLY_EXECUTION_PLAN was copied unchanged from the primary checkout. The deterministic, curated-fixture flow now includes durable PostgreSQL persistence. The complete production/live-data roadmap is not finished.

## Baseline and roadmap audit

Already present at review: Kotlin/Spring/Gradle foundation, PostgreSQL Compose, timestamped Flyway baseline, profiles, Actuator, and three context/health integration tests. Feature packages were empty. The old README's Telegram implementation did not exist in this checkout.

| Task | Implemented evidence / remaining acceptance |
| --- | --- |
| 1 — semantics | User accepted the fixture MVP semantics; ADR 0001 records the hand-calculated example. Actual Decu identity/network, supported swap programs and live scope remain open. |
| 2 — environment | Java 21 configured, Docker 29.6.2 recovered, full local build and PostgreSQL Testcontainers pass. Explicit profile behavior matches documentation. |
| 3 — contracts | Validated chain values; public wallet/market/strategy/fill/position/replay contracts and feature-owned persistence ports compile. No feature imports another feature's persistence implementation. |
| 4 — fixtures | Versioned synthetic balance-delta fixture, strict checksummed loader, bounded arrays and negative/duplicate/out-of-order variants tested. Sanitized raw/provider captures remain absent. |
| 5 — normalization | Confirmed signer buy/sell and explicit ignored outcomes, u64 amounts, stable signature/instruction identity and conflict/deduplication handling tested. Actual Solana route/instruction parsing is still partial against the original roadmap. |
| 6 — observations | Slot/price-slot, source/quality/commitment, age/wait/liquidity checks, deterministic ordering and exact persistence round-trip tested. |
| 7 — strategy | Fixed-size independent +1/+2/+3 intents, strategy/config provenance and overlap policy tested and persisted. |
| 8 — execution | Deterministic fees/slippage, zero-value rejection and exact observation references; filled and rejected paper orders persist. |
| 9 — portfolio | Maximum-holding-slot exit, explicit unavailable exits, duplicate-close safety, state validation and exact realized quote P&L tested. |
| 10 — persistence | Seven feature-owned tables across three additive timestamped migrations; mappers, bounded repositories, provenance FKs, unique keys, open-scenario index and optimistic versions. Fresh PostgreSQL migrations/JPA validation, exact round-trips and stale-writer rejection pass. |
| 11 — orchestration | Offline replay and a Spring persisted-replay service/command implemented; remote access remains outside transactions. |
| 12 — PostgreSQL E2E | Full lifecycle restores exactly; duplicate/reordered reruns retain counts, concurrent writers converge, different configs are independent, missing prices/exits remain explicit and invalid decimals/timestamps roll back the entire run. |
| 13 — checkpoint | Shared fixture economics/provenance checked against the ADR and emitted/persisted objects. Mapping nullability and timestamp-rounding defects discovered during integration were fixed before final verification. |
| 14 — provider research | Provisional official-source comparison and setup recommendation in PROVIDER_SPIKE. Real public-devnet connectivity verified. Representative wallet/market payloads, production delivery, retention, DEX coverage and latency remain unverified. |
| 15 — operation/docs | README, ADR, fixture/persistence format, setup and limitations updated. GitHub Actions workflow added; no hosted run dispatched. |
| 16 — demo | Two separate command processes against an isolated PostgreSQL 17 container produced one run and three closed positions, matching the offline checksum/P&L. Live Decu demonstration remains pending real captures. |

## Final verification

- `gradlew.bat build replay liveTest --no-daemon` — passed.
- Default suite: **40 passed, 0 failed, 0 skipped**, including existing context/health tests and PostgreSQL lifecycle integration tests.
- Separately tagged live suite: **1 passed** against the real public Solana devnet API. `getVersion` returned **4.3.0**; confirmed `getSlot` returned **506305915**. This proves connectivity/response shape only; no wallet ingestion, prices, subscriptions or recovery were exercised.
- Earlier `unitTest`: 25 offline tests passed; these are also covered by the final default suite. No monetary behavior is validated using mocks.
- Native PostgreSQL 17.11 alternative: the unchanged baseline integration assertions passed before Docker recovery. Docker remains the default; final full verification used Testcontainers.
- Persisted command: executed twice in separate processes against a fresh disposable, localhost-only PostgreSQL 17 container. Second process returned the original creation time and performed no inserts.
- Flyway/JPA validation: passed from empty Testcontainer schemas and the isolated command-demo database.
- Packaging: application jar and offline replay passed.
- Hosted GitHub Actions: configured for Java 21, Docker/Testcontainers, `build replay`; **not run remotely**.
- `git diff --check`: passed; no git commit, branch, staging or push performed.

Both command processes left these counts:

| Runs | Wallet events | Observations | Intents | Orders | Fills | Positions |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 4 | 6 | 3 | 3 | 3 | 3 |

| Offset | Entry slot | Exit slot | Final state | Realized quote P&L |
| --- | --- | --- | --- | --- |
| +1 | 101 | 106 | CLOSED | 8.696567164180 |
| +2 | 102 | 107 | CLOSED | 8.696567164180 |
| +3 | 103 | 108 | CLOSED | 8.696567164180 |

Dataset checksum: `7da78ada00b144606af1578d5d72de54f879149f906e785833d6bab9c9e862f6`.
Run ID: `aa6556ccee5e35c671d4d5d6393b835bd667ad00eedcc8f4dce8594c7a8014f8`.
Result checksum: `aef31c7614f0f45da4b15dbaaca24ea0105049589ee566921264a4b7b41dfd6d`.

## Schema, recovery and model limits

Additive migrations create `simulation_runs`, `wallet_events`, `market_observations`, `trade_intents`, `paper_orders`, `paper_fills` and `positions`. The existing baseline is unchanged. The user's existing Compose database was not migrated during the isolated demo.

Pure calculations and snapshot serialization happen before the single replay write transaction. Any feature write failure rolls back all rows. Dataset/config/engine uniqueness prevents duplicate runs; source identity, scenario intent, fill/order identity and active scenario uniqueness are enforced in PostgreSQL. A competing insertion is recovered outside the rolled-back transaction by reading and checking the winner's result checksum. Optimistic versions reject stale position writers.

Monetary model values use scale 12/HALF_EVEN and fit `numeric(38,12)`; raw deltas fit signed u64 magnitude in `numeric(20,0)`; observed prices/liquidity fit `numeric(78,38)` with their original scales retained. Input times must be exactly representable at microsecond precision. Finer timestamps, unrepresentable decimals and aggregate batches beyond 10,000 fail atomically.

Runs are immutable experiments. A missing bounded exit stays open with a reason; adding observations creates another dataset/run. This is restart-safe replay, not live streaming checkpoints or reconnect/gap recovery. Inputs are synthetic and the supported-route flag is curated. Priority fee and price impact are explicitly zero; missing liquidity remains unknown. No profitability claim is supported by this example.

## Remaining setup and next increment

No registration, billing or provider credentials are needed for the fixture MVP, local PostgreSQL or public-devnet smoke test. Docker is working; its earlier failure is resolved.

Live capture needs the **public Decu address, network, tracking scope, supported swap programs and quote assets**. The proposed provider is Helius, pending adoption: register, create an API key, select the network, supply HTTP/WebSocket URLs through environment variables and set a credit budget. Provider bindings and capture/parser adapters are not implemented; see PROVIDER_SPIKE for the precise evidence gaps. No provider account was created or billed, and no secrets were read or committed.

Next research increment: capture and independently verify a supported real wallet swap plus slot-tagged pool/trade prices, then compare the existing simulator against that dataset before changing strategy rules. Live adapters, broad historical backfill, notifications and real execution remain deferred.

## Review and files changed

The repository's Kotlin/Spring conventions and structured review checklist were applied. The implemented persistence requirement is covered by real PostgreSQL tests and a cross-process demo. Residual gaps are raw protocol decoding, real wallet/market captures, production feed behavior and hosted CI execution.

- `build.gradle.kts`, `.gitignore`, `.github/workflows/build.yml`: task/test configuration, generated-file exclusions, MapStruct/kapt and CI.
- `application.yml`: explicit environment/profile selection. Local/prod secret configuration remains unchanged.
- `common`: chain values, decimal/time precision, stable storage identities and primitive mapper conversions.
- `walletactivity`, `marketdata`: normalization/ordered fixture inputs, feature store ports, entities, repositories and MapStruct mappings.
- `strategy`, `papertrading`, `portfolio`: deterministic lifecycle plus feature-owned persistence for intents, filled/rejected orders, fills and positions.
- `replay`, `config/ReplayPersistenceConfiguration.kt`: fixture loading/hashes, orchestration, offline/persisted commands, run ledger/transaction, mappings and UTC clock.
- `src/main/resources/db/migration/V20261001140140__simulation_runs.sql`, `V20261001141640__wallet_events_market_observations.sql`, `V20261001142600__intents_orders_positions.sql`: new schema.
- `src/test/kotlin`, `src/test/resources/fixtures`: offline boundaries, PostgreSQL round-trips/races/rollback, explicit live smoke test and sanitized synthetic fixture; existing foundation tests remain enabled.
- `README.md`, this status, `REPLAY_FORMAT.md`, `PROVIDER_SPIKE.md`, ADR 0001 and archived persistence plan: reproducible operation, accepted assumptions and verification limits. The original weekly roadmap remains unchanged.


## Follow-up — 2026-10-02: existing database compatibility

The implementation was transferred into `C:/Users/user/IdeaProjects/trenching_bot` and verified there. Startup then exposed a historical baseline rename: Git commit `b72cf6c` renamed `V1__baseline.sql` to its timestamped equivalent, while the existing database still recorded version `1`.

Restored the original V1 byte-for-byte from commit `5016c2d` (Git blob `8490d4cad8c20de5ed06ae91d06129a90e4fb659`). Both baseline files remain unchanged. New migrations still use timestamps. No schema history was repaired or deleted, no database was reset, and no existing data was removed.

Added `LegacyMigrationIntegrationTest` and a historical fixture. The test first reproduced the reported Flyway validation failure, then verified the upgrade after restoration, preservation of the original checksum/data marker, an idempotent rerun and Spring/JPA startup. All 16 targeted PostgreSQL integration tests passed.

A temporary-port application process also started against the user's existing localhost Compose database: Flyway validated five migrations, upgraded from version `1` by applying four pending migrations to `20261001142600`, and application/database health returned `UP`. The verification process was stopped afterward.

The user's current `local` profile default was retained, and its configuration test/README/ADR were aligned with that setting. Production still requires explicit profile/environment configuration. Git staging and existing user edits were preserved.

Final follow-up verification: gradlew.bat build --no-daemon passed with 41 tests, zero failures/errors/skips. Existing-database application/database health was UP; the temporary verification process was stopped.

## Follow-up — 2026-10-02: review fixes

- Prices keep scale 38 (`DecimalPolicy.PRICE_SCALE`); quote amounts and quantities stay at scale 12. Migration `V20261002115114__price_scale.sql` widens `paper_fills.execution_price` and `positions.exit_price` to `numeric(78,38)`. Engine version is now `fixture-paper-v2`, so earlier stored runs are not compared against the new results.
- `SimulationConfig.quoteMint` (default wrapped SOL): swaps against any other quote mint are ignored as `UNSUPPORTED_QUOTE`, so every scenario's notional and P&L share one unit.
- Shared `ReplayLimits`: 10,000 input records and 30,000 scenario records, so any dataset the loader accepts can be persisted. Store batch errors are `RecordBatchException` subclasses with run ids, sizes and record ids.
- The loader now applies the persistence checks: microsecond times, `numeric(78,38)` prices and liquidity, and string column lengths.
- Market data lookups are indexed per pair with binary search; the overlap check only compares against the latest position for each scenario.
- Flyway `out-of-order: true` lets databases created from the timestamped baseline apply the restored V1. `LegacyMigrationIntegrationTest` covers both histories and uses the configured PostgreSQL provider (Docker or native).
- `FixtureMarketDataSource` was removed; tests use `OrderedMarketData`.

Verification: `gradlew.bat build replay --no-daemon` passed, 45 tests with no failures. The fixture P&L is unchanged at 8.696567164180 per scenario. New run ID `ae13b5d3ba8d9bd60df6e24d034addec723f3a0ebcc7c1aa3440bb4426a1564c`, result checksum `8bc4f95d10f4f431e92c62d3f9a96cb91b03eefdff556f6516d726a3a49a89e9`. The run ID and result checksum earlier in this file are from engine `fixture-paper-v1`.
