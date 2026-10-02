# trenching-bot

Read-only Solana paper-trading research system. The deterministic fixture slice is implemented: curated wallet balance deltas -> confirmed-buy strategy -> independent +1/+2/+3 entries -> modeled fills -> maximum-holding-slot exits -> atomic PostgreSQL persistence and JSON report. Live wallet/market ingestion remains pending. No transactions are constructed, signed or sent.

The roadmap is [WEEKLY_EXECUTION_PLAN](docs/WEEKLY_EXECUTION_PLAN.md). See [implementation status](docs/IMPLEMENTATION_STATUS.md), [accepted semantics](docs/adr/0001-fixture-simulation-semantics.md), [fixture format](docs/REPLAY_FORMAT.md), and [provider research/setup](docs/PROVIDER_SPIKE.md).

## Stack and requirements

Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Gradle wrapper 9.6.0, PostgreSQL 17, JPA, MapStruct 1.6.3/kapt and JUnit/Testcontainers. MapStruct generates the persistence DTO mappings; replay and the RPC smoke test use the existing runtime libraries.

Set `JAVA_HOME` to an installed JDK 21. Docker is required for PostgreSQL integration tests and the Spring application, but not for offline tests or standalone replay. On Windows use `.\gradlew.bat`; on Unix use `./gradlew`.

## Run the deterministic experiment

```powershell
.\gradlew.bat unitTest replay
```

The default dataset is `src/test/resources/fixtures/weekly-buy-v1.json`. Replay runs without Spring, PostgreSQL, network access or credentials. It writes `build/replay-report.json` and prints a compact scenario summary. Override paths with `-Pdataset=<path>` and `-Preport=<path>`. Generated reports are not committed.

| Offset | Target / actual entry slot | Exit slot | Entry quantity | Realized quote P&L |
| --- | --- | --- | --- | --- |
| +1 | 101 | 106 | 9.950248756219 | 8.696567164180 |
| +2 | 102 | 107 | 9.950248756219 | 8.696567164180 |
| +3 | 103 | 108 | 9.950248756219 | 8.696567164180 |

The accepted model copies confirmed signer buys, uses 100 quote units per scenario, charges 10 bps fees and 50 bps adverse slippage on both sides, and exits after five holding slots. Prices must be eligible within a two-slot wait and age window. Quote amounts and quantities use scale 12; prices keep scale 38 so memecoin prices stay exact; both round HALF_EVEN. Only swaps against the configured quote mint (wrapped SOL by default) become trades; others are ignored as `UNSUPPORTED_QUOTE`. Fees are additional to entry notional. Priority fees and price impact are explicitly zero. Missing prices, stale/invalid inputs, insufficient supplied liquidity and overlapping positions produce explicit results.

Repeat runs produce identical IDs and result checksums; duplicate input delivery is deduplicated and conflicting identities fail. Persisted replay stores the complete lifecycle in one transaction. Repeating it after a process restart returns the original run and does not create new records. Different dataset/configuration/engine versions have independent run identities. Inputs are synthetic fixtures, not verified Decu transactions. The route-supported flag is curated; raw Solana instruction decoding, actual token metadata, live quote-asset restrictions and provider captures are not implemented.

To save the experiment locally:

```powershell
docker compose up -d
.\gradlew.bat persistedReplay
.\gradlew.bat persistedReplay # Same run; no duplicate events, fills or positions
```

The command starts Spring without an HTTP server, lets Hibernate create or update the schema (`local`), saves the result and exits. It writes `build/persisted-replay-report.json`. The same `-Pdataset` and `-Preport` overrides work; `-Pprofile=local` is the default. Production uses `-Pprofile=prod` and the existing environment configuration.

## Tests and packaging

```powershell
.\gradlew.bat unitTest       # Offline domain/configuration tests; no Docker
.\gradlew.bat test           # Offline tests plus PostgreSQL Testcontainers
.\gradlew.bat liveTest       # Explicit read-only public Solana devnet smoke test
.\gradlew.bat assemble       # Application jar without tests
.\gradlew.bat build          # Full packaging and default suite
```

Default `test` and `build` exclude the `live` tag. Existing PostgreSQL context/health tests remain enabled by default; tagging them `integration` lets `unitTest` run independently without weakening the full suite. `liveTest` calls only `getVersion` and confirmed `getSlot` with explicit timeouts and HTTP/1.1. It verifies connectivity and response shape, not wallet parsing, market prices, subscriptions or recovery. No account or key is needed for the public devnet smoke test.

The GitHub Actions workflow runs Java 21, `build` and offline `replay` with PostgreSQL Testcontainers. It does not call live providers. The workflow has been added locally; a hosted CI run has not been dispatched.

Docker is the default integration-test provider. An optional, explicitly selected native PostgreSQL 17 runtime supports environments where Docker is unavailable:

| Test-only environment variable | Meaning |
| --- | --- |
| `TRENCHING_TESTS_POSTGRES_PROVIDER` | `docker` (default), or `native`; no automatic fallback |
| `TRENCHING_TESTS_POSTGRES_BINARY_DIRECTORY` | Required for `native`: absolute directory containing PostgreSQL 17 `postgres`, `initdb` and `pg_ctl` binaries |

Native tests create a fresh localhost-only cluster under ignored `build/native-postgres-tests`, use trust authentication for that disposable cluster and stop it when the test context closes. They do not register an OS service. Test-provider selection is a Gradle test input, so switching providers reruns the tests.

## Modules

Feature packages remain one deployable modular monolith. Shared value types live in `common`; domain contracts contain no Spring, JPA or vendor types.

| Module | Implemented responsibility |
| --- | --- |
| `common` | Validated base58 wallet/mint/signature values, slots, unsigned raw token amounts and decimal policy |
| `walletactivity` | Curated balance-delta normalization into accepted or ignored outcomes |
| `marketdata` | Slot-aware observations, ordered catalog, fixture data source |
| `strategy` | Versioned fixed-sizing confirmed-buy intents and configuration |
| `papertrading` | Bounded observation selection, fee/slippage model and explicit rejections |
| `portfolio` | Position lifecycle, timeout exit, liquidity checks and quote P&L |
| `replay` | Checksummed fixture loader, deterministic orchestration, offline/persisted commands and durable run ledger |

Implementation packages are `domain`, `application`, `infrastructure`, and `api`. Other modules consume public contracts/domain values. Notification/Telegram work is deferred; the earlier README's Telegram implementation description did not correspond to this checkout.

Each feature owns its entities, repositories, MapStruct mappings and public store port. Replay coordinates these ports; it does not import another feature's JPA types. Tables are `simulation_runs`, `wallet_events`, `market_observations`, `trade_intents`, `paper_orders`, `paper_fills` and `positions`. Unique constraints, provenance foreign keys, position state checks, an open-scenario unique index and optimistic versions protect the lifecycle.

Model quote amounts and quantities use `numeric(38,12)`; execution and exit prices use `numeric(78,38)`; raw signed deltas use `numeric(20,0)` with u64 bounds; observed prices/liquidity use `numeric(78,38)` with original decimal scales retained. Unrepresentable decimals or source times finer than microseconds fail rather than silently round. Datasets are bounded to 10,000 wallet transactions and 10,000 observations; intents, orders, fills and positions to 30,000 per run (three scenarios per buy), so every loadable dataset persists. The loader applies the same timestamp, decimal and length checks as persistence. Run snapshots preserve inputs, configuration, engine/version, result checksum and model limitations. Creation time is UTC metadata, excluded from deterministic identity.

## Spring application and PostgreSQL

```powershell
docker compose up -d
.\gradlew.bat bootRun
```

`bootRun` selects `local` and the application listens at `http://localhost:8080`. Check `/actuator/health`. It currently exposes the foundation/health endpoint; replay is invoked separately, not on application startup. Compose defaults are local development credentials, not production secrets: database `trenching_bot`, username/password `trenching`, port 5432; override using `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`.

`application.yml` defaults to `local` for IDE/local development. Select production explicitly with `SPRING_PROFILES_ACTIVE=prod`; environment configuration overrides the local default. `application-local.yml` uses the local Compose database and verbose health; `application-prod.yml` requires `DATABASE_URL` (JDBC), `DATABASE_USERNAME`, `DATABASE_PASSWORD`, with optional `DATABASE_POOL_SIZE` (10). `SERVER_PORT` defaults to 8080. Production datasource settings have no fallback credentials. There is no Flyway for now: Hibernate creates and updates the schema from the entities (`ddl-auto: update`) in `local` and tests, and only validates it in `prod`, so production schema changes are applied by hand. See `CLAUDE.md` and `docs/schema/`.

Secrets and provider URLs containing keys must stay in environment configuration or ignored local secret storage; never commit or log them. No provider credentials are consumed by the fixture runner. Live capture will require the Decu public address/network/scope and an adopted RPC/slot-aware market source; see the provider setup note before registering or paying for a service.

## Current verification limits

Docker recovered, and `build replay liveTest` passed on 2026-10-01: 40 default tests and one live test. PostgreSQL tests cover complete round-trips, exact P&L, repeated and concurrent delivery, independent configurations, rejected entries, missing exits, rollback, duplicate/provenance constraints and stale optimistic writers. Two separate persisted command processes also produced one durable run and three closed positions with identical results.

Live wallet parsing/capture, live slot-aware markets, streaming recovery and provider retention/latency/coverage validation remain unfinished. Open positions in a fixed dataset remain open; late data requires a new dataset/run rather than mutating the original experiment. Notifications and real execution are deferred by the weekly plan.

## Repository conventions

See [CLAUDE.md](CLAUDE.md) and [developer tooling](docs/CLAUDE_CODE_GUIDE.md). The original roadmap is preserved for traceability; accepted changes and actual test evidence are in the ADR and implementation status, rather than marking unfinished acceptance criteria complete.


## Postman API checks

Import the collections and environments in [postman](postman/README.md) to check the running application and optionally public Solana devnet. The guide lists the available HTTP endpoints and current coverage limits.
