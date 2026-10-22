# trenching-bot

Read-only Solana paper-trading research system. The deterministic fixture slice is implemented: wallet balance deltas -> the dip-entry strategy (the tracked wallet's first buy of each token, a 15% dip at a market cap of at least $10,000, then +30% take profit or -15% stop loss, all within about 30 minutes) -> modeled fills -> atomic PostgreSQL persistence and JSON report. Live wallet/market ingestion remains pending. No transactions are constructed, signed or sent.

The roadmap is [WEEKLY_EXECUTION_PLAN](docs/WEEKLY_EXECUTION_PLAN.md). See [implementation status](docs/IMPLEMENTATION_STATUS.md), [the dip-entry strategy](docs/adr/20261008-dip-entry-strategy.md), [original fixture semantics](docs/adr/0001-fixture-simulation-semantics.md), [fixture format](docs/REPLAY_FORMAT.md), and [provider research/setup](docs/PROVIDER_SPIKE.md).

## Stack and requirements

Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Gradle wrapper 9.6.0, PostgreSQL 17, JPA, MapStruct 1.6.3/kapt and JUnit/Testcontainers. MapStruct generates the persistence DTO mappings; replay and the RPC smoke test use the existing runtime libraries.

Set `JAVA_HOME` to an installed JDK 21. Docker is required for PostgreSQL integration tests and the Spring application, but not for offline tests or standalone replay. On Windows use `.\gradlew.bat`; on Unix use `./gradlew`.

## Run the deterministic experiment

```powershell
.\gradlew.bat unitTest replay
```

The default dataset is the bundled synthetic sample `src/main/resources/replay/samples/dip-sample-v3.json`, with one token per cycle outcome. Replay runs without Spring, PostgreSQL, network access or credentials. It writes `build/replay-report.json` and prints a compact scenario summary. Override paths with `-Pdataset=<path>` and `-Preport=<path>`. Generated reports are not committed.

| Cycle | Dip / fill slot | Exit | Realized quote P&L |
| --- | --- | --- | --- |
| Take profit | 1002 / 1003: 0.1 SOL buys 1,160,584.892249 tokens, impact +19 bps, cost basis 0.10225 | 1006, take profit: sells for 0.133175124, impact -22 bps, net 0.130510434 | 0.028260434000 |
| Stop loss, gapped | 2001 / 2002: buys 1,247,541.616895 tokens, impact +20 bps, cost basis 0.10225 | 2005 for 0.062280246 (about 0.0000000499 per token, below the 0.0000000681 stop), impact -16 bps, net 0.060501742 | -0.041748258000 |
| Migrated, horizon, no exit fill | filled | open | none |
| No dip, low market cap, migrated before entry, no fill, unsupported supply | none | none | none |

The run totals -0.013487824000 SOL over 10 cycles (5 entered, 2 closed). The [dip-entry decision](docs/adr/20261008-dip-entry-strategy.md) records every rule. In short:
- one cycle per token starts at the tracked wallet's first confirmed signer buy of it, at that buy's price P0;
- the first eligible trade in a later slot at or below P0 × 0.85, with a market cap of at least $10,000 (price × 1B supply × the dataset's SOL/USDT rate) and before the bonding curve completes, triggers the entry;
- entries and exits fill at the first eligible trade at least `latencySlots` (default 1) after their trigger;
- take profit is at Pe × 1.30 and stop loss at Pe × 0.85, where Pe is the entry's curve execution price (our own price impact included, fees excluded);
- everything happens within 4,500 slots of the buy.

Execution follows the [bonding-curve execution decision](docs/adr/20261009-bonding-curve-execution.md) (engine `dip-paper-v2`). Each entry spends 0.1 SOL (a whole number of lamports) against the fill trade's post-trade Pump.fun curve: tokens out and SOL out use the program's integer formula rounding down, so our own price impact follows from the curve, and an entry larger than the curve's real token reserves is rejected as `EXCEEDS_CURVE`. The protocol and creator fees are the fill trade's own basis points, rounded up to whole lamports, paid on top of the notional on entry and out of the proceeds on exit. A fixed `transactionCostSol` (default 0.001 SOL, for the network fee plus priority fee or tip) is charged on each entry and each exit; token-account rent is not charged, since it is refunded when the account closes. Notional, latency and transaction cost are editable per run. Prices must be at most two slots old by price slot. Quote amounts and quantities use scale 12; prices keep scale 38 so memecoin prices stay exact; both round HALF_EVEN. Only swaps against the configured quote mint (wrapped SOL by default) become trades; others are ignored as `UNSUPPORTED_QUOTE`. Every cycle that never enters and every position that never exits carries an explicit reason.

Repeat runs produce identical IDs and result checksums; duplicate input delivery is deduplicated and conflicting identities fail. Persisted replay stores the complete lifecycle in one transaction. Repeating it after a process restart returns the original run and does not create new records. Different dataset/configuration/engine versions have independent run identities. The bundled sample is a synthetic fixture, not verified Decu transactions, and its route-supported flag is curated. A real dataset can be exported from recorded captures (below). Offline decoding of real captures supports only SOL-quoted Pump.fun bonding-curve buys (`buy` and `buy_exact_quote_in_v3`) signed by the tracked wallet ([decision](docs/adr/20261003-pumpfun-bonding-curve-buy-decoder.md), [V3 amendment](docs/adr/20261010-pumpfun-exact-quote-in-v3-buys.md)). Actual token metadata and live quote-asset restrictions are not implemented.

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

The GitHub Actions workflow runs Java 21, `build` and offline `replay` with PostgreSQL Testcontainers. A separate Node 22 frontend job installs the lockfile and runs lint, tests and build. Neither job calls live providers. A hosted CI run has not been dispatched.

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
| `chaincapture` | Vendor-neutral, read-only Solana JSON-RPC capture plumbing: guarded transport, mainnet check, signature-listing parsing, budgets and shared limits, hashed artifact store, manifest field readers, parsed-transaction and unsafe-text checks |
| `protocol.pumpfun` | Pump.fun bonding-curve instruction layout and reader for `buy` and `TradeEvent` instructions, without market or wallet rules |
| `walletactivity` | Curated balance-delta normalization into accepted or ignored outcomes |
| `marketdata` | Slot-aware observations, ordered catalog, fixture data source; mint-referenced market capture (`MarketHistoryCapture`) and offline Pump.fun `LAST_TRADE` extraction (`RecordedMarketHistory`) |
| `strategy` | The dip-entry strategy: first buy per token, P0, the dip trigger with market cap, and run configuration |
| `papertrading` | Latency-delayed fills after a dip trigger against the Pump.fun bonding curve, real fees, transaction cost and explicit rejections |
| `portfolio` | Position lifecycle, take-profit and stop-loss exits sold into the curve, and quote P&L |
| `replay` | Checksummed fixture loader, deterministic orchestration, offline/persisted commands, durable run ledger, capture commands and market-window planning from recorded wallet buys |
| `console` | Gated dataset/run REST API, local React console, dashboard and on-demand read-only Solana probe |

Implementation packages are `domain`, `application`, `infrastructure`, and `api`. `chaincapture` and `protocol.pumpfun` are the exception: flat packages of small building blocks with no Spring context, annotations or database (the transport uses spring-web's `RestClient`) that `walletactivity` and `marketdata` share, so neither feature imports the other's capture internals. Other modules consume public contracts/domain values. Notification/Telegram work is deferred; the earlier README's Telegram implementation description did not correspond to this checkout.

Each feature owns its entities, repositories, MapStruct mappings and public store port. Replay coordinates these ports; it does not import another feature's JPA types. Tables are `replay_datasets`, `simulation_runs`, `wallet_events`, `market_observations`, `trade_intents`, `paper_orders`, `paper_fills` and `positions`. Unique constraints, provenance foreign keys, position state checks, an open-scenario unique index and optimistic versions protect the lifecycle.

Model quote amounts and quantities use `numeric(38,12)`; execution and exit prices use `numeric(78,38)`; raw signed deltas use `numeric(20,0)` with u64 bounds; observed prices/liquidity use `numeric(78,38)` with original decimal scales retained. Unrepresentable decimals or source times finer than microseconds fail rather than silently round. Datasets (schema v3, v4 or v5, with SOL/USDT minute rates and curve reserves, from v4 each observation's decoded trade, and for v5 each market window's observed coverage; a run decided beyond that coverage is refused with `MARKET_COVERAGE_ENDED`, see [the format](docs/REPLAY_FORMAT.md#schema-v5-coverage)) are bounded to 100,000 wallet transactions and 100,000 observations; intents, orders, fills and positions to 100,000 per run (at most one cycle per buy), so every loadable dataset persists. The loader applies the same timestamp, decimal and length checks as persistence. Run snapshots preserve inputs, configuration, engine/version, result checksum and model limitations. Creation time is UTC metadata, excluded from deterministic identity.

## Spring application and PostgreSQL

```powershell
docker compose up -d
.\gradlew.bat bootRun
```

`bootRun` selects `local` and the application listens at `http://localhost:8081`. Check `/actuator/health`. The local profile also enables the console `/api` endpoints; replay is never invoked on application startup. Compose defaults are local development credentials, not production secrets: database `trenching_bot`, username/password `trenching`, port 5433; override using `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`.

`application.yml` defaults to `local` for IDE/local development. Select production explicitly with `SPRING_PROFILES_ACTIVE=prod`; environment configuration overrides the local default. `application-local.yml` uses the local Compose database and verbose health; `application-prod.yml` requires `DATABASE_URL` (JDBC), `DATABASE_USERNAME`, `DATABASE_PASSWORD`, with optional `DATABASE_POOL_SIZE` (10). `SERVER_PORT` defaults to 8081. Production datasource settings have no fallback credentials. There is no Flyway for now: Hibernate creates and updates the schema from the entities (`ddl-auto: update`) in `local` and tests, and only validates it in `prod`, so production schema changes are applied by hand. See `CLAUDE.md` and `docs/schema/`.

## Console

The React console in `frontend/` manages stored replay datasets and immutable runs, displays exact decimal results, and polls the monitoring dashboard every five seconds. Browser sparklines retain up to 30 minutes of history. The Solana panel checks connectivity only when requested; an unset RPC is shown as "Not configured". The console has no authentication and is intended for local use. Both `/api/**` and bundled `/console/**` resources return 404 when the console is disabled.

For development, install Node 22.12 or newer and use two terminals with the local PostgreSQL instance available on port 5433:

```powershell
.\gradlew.bat bootRun # Local profile, port 8081
```

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open `http://localhost:5173/console/`. Vite proxies `/api` and `/actuator` to the backend. Import the dip sample, start a run with the defaults, and inspect its ten cycle cards: take profit realizes `0.028260434000`, the gapped stop loss `-0.041748258000`, totaling `-0.013487824000`. Each fill and close shows its fees, price impact and transaction cost. `VITE_USE_MOCKS=true` runs the development UI with local MSW fixtures.

**Captures.** The local console can create a bounded wallet capture or attach existing evidence under the capture root, then run wallet, market and SOL/USDT-rate acquisition as background operations. It shows each invocation's allowance separately from cumulative usage. Wallet and market acquisition can opt into bounded automatic recovery with cumulative request, byte and attempt limits, provider retry delays, and pause/resume/cancel controls. Each wallet-planned market case is listed independently: downloaded counts reach `READY_TO_VALIDATE` before selected artifacts are verified, and a healthy case can then prepare its own rates, export a canonical schema-v5 dataset, download it or import it into Datasets while a sibling remains incomplete or invalid. The existing full-capture rates and export path stays strict and still requires every market window. Calibration also retains its full-capture checks. The **Wallet + token** scope takes one mint, verifies whether the wallet's earliest buy through its token account is its actual first Pump.fun curve buy, and qualifies the exported dataset automatically against the dip-entry strategy ([guide](docs/REAL_DATA_CAPTURE.md#wallet--token-capture), [independent-case decision](docs/adr/20261012-independent-market-cases.md)). Its market attempt defaults to `DECIDED` mode, which stops each window once its outcome is decided, while reference rates are enabled (`FULL` otherwise and for the other scopes); a `MARKET` operation with `cutoffMode` `FULL` captures the rest of a decided window, and `DECIDED` without reference rates is refused with `RATES_NOT_CONFIGURED` ([decided and full mode](docs/REAL_DATA_CAPTURE.md#decided-and-full-mode)). The pages use the existing console theme and work in dark and light modes.

**Deleting.** `DELETE /api/captures/{id}` removes a capture session and every file the console created for it: managed evidence, exports and calibration reports. Attached directories are only referenced and are never touched, and imported datasets stay. A session with a running operation returns 409. `DELETE /api/datasets/{id}` removes a dataset from the catalog; its runs stay, each with its own copy of the dataset, and re-uploading the same file restores the same id. Both return 204, or 404 for an unknown id. Both work only from a local browser or client, never through the public preview tunnel (403 `LOCAL_ACCESS_REQUIRED`). The console asks for confirmation before either delete. See the [delete plan](docs/plans/archive/20261010-delete-captures-and-datasets.md).

Capture status polling, inspection, export and calibration are offline; the shared SOL ticker retains its existing independent behavior. A browser refresh reconnects to persisted operation status. After a backend restart, an eligible recovery-enabled wallet or market operation continues within its saved limits; paused, cancelled, stale or exhausted work stays stopped. An operation without recovery is marked interrupted and requires an explicit retry. Because a forced kill loses usage after the last checkpoint, recovery conservatively charges the remaining reserved allowance before retrying; artifacts written after the last manifest checkpoint can be fetched again. Capture routes are local-only and require the local profile with the console enabled. Configure SOLANA_RPC_URL in the backend process; the browser never receives the endpoint. Capture files default to build/captures and are removed by clean, so retained research should use an ignored root outside build or be backed up. The workflow stays read-only toward Solana; live trading, signing and transaction submission remain out of scope. See [browser capture workflow](docs/REAL_DATA_CAPTURE.md#capture-from-the-console), the [independent-case decision](docs/adr/20261012-independent-market-cases.md) and the [capture console decision](docs/adr/20261010-capture-console.md).

**USDT view and market links.**
- **The switch:** a run quoted in wrapped SOL has a **SOL / USDT** switch, and its page shows the current SOL price. USDT converts each price, fill, exit and P&L at the Binance SOLUSDT 1-minute open of its own block time's minute. It is a display conversion only: the simulation, report and checksums stay in SOL ([decision](docs/adr/20261005-sol-usdt-display-conversion.md)).
- **Missing rates:** a value whose rate is missing shows the reason instead of an estimate. The choice is kept in the URL (`?quote=usdt`).
- **Links:** trades and tokens from recorded Pump.fun captures link to Solscan, Solana Explorer, pump.fun and DexScreener, each in a new tab. The synthetic sample has no links.

**Capture, run and usage API.** These endpoints were added or extended by the [console capture, run and results plan](docs/plans/archive/20261016-console-capture-run-ux.md). Every change is additive: existing fields and requests keep their meaning. The full field lists are in the [frontend handoff](docs/plans/20261016-console-capture-run-ux-codex-handoff.md). Errors use the console error body (`code`, `message`, `fields`). `/api/captures/**` needs the local profile, as before.

| Method · path | What it does |
| --- | --- |
| `GET /api/wallets/{address}/activity?limit=&before=&refresh=` | A wallet's recent Pump.fun curve buys and sells, newest first, for picking a token (`limit` 1–50, default 20; `before` is the `nextBefore` of the previous page). Live provider requests, cached 60 s; `refresh=true` skips the cache ([cost](docs/REAL_DATA_CAPTURE.md#pick-a-token-from-wallet-activity)). `requestsUsed` is 0 for a cached page. Local browser only (403 `LOCAL_ACCESS_REQUIRED` through the tunnel). Errors: 400 `INVALID_ADDRESS`, 400 `INVALID_REQUEST` for a bad `limit` or `before`, 409 `RPC_NOT_CONFIGURED`, 409 `NOT_MAINNET`, 429 `PROVIDER_RATE_LIMITED` with a top-level `retryAfterSeconds` when the provider sent `Retry-After`, 502 `PROVIDER_UNAVAILABLE` |
| `GET /api/captures/config` | Adds `suggestedWallets` (from `trenching.console.suggested-wallets`) and up to 5 `recentWallets` from saved sessions |
| `GET /api/captures?page&size&sort&status` | Newest first by default. `sort` is `createdAt,desc`, `createdAt,asc`, `name,asc` or `attention`; `status` filters by summary status; `statusCounts` counts every session. Each session, here and in `GET /api/captures/{id}`, adds `displayName` and a derived `summary` (status, reason, step, progress, next action) |
| `POST /api/captures` | `name` is optional: blank means generated (`Decu · 7mup…pump · 16 Oct 20:13`), using the optional IANA `timeZone` (default UTC). A taken name returns 409 `NAME_TAKEN` with a `suggestion` |
| `PATCH /api/captures/{id}` | Renames (`{requestId, name}`) with the same uniqueness rule; idempotent per `requestId` |
| `GET /api/captures/names/check?name=` | `{available, normalized, suggestion}` for live checks |
| `GET /api/captures/recommendations?wallet=&scope=&mint=` | Capture setup recommendations |
| `GET /api/runs/preset` | The agreed-strategy preset: values, a status per field and the 8 spec rules the engine cannot apply ([decision](docs/adr/20261016-agreed-strategy-run-preset.md)) |
| `GET /api/runs/recommendations?datasetId=` | Run setup recommendations |
| `GET /api/config/defaults` | Adds `dipPercent`, `takeProfitPercent` and `stopLossPercent` beside each bps value in `defaults` and `limits`. The defaults are still `SimulationConfig.mvp()` |
| `POST /api/runs` | `config` accepts the three `…Percent` strings instead of the bps fields (at most two decimals, never rounded; 400 `INVALID_PERCENT` or `CONFLICTING_FIELDS`). Optional `presetId` is validated and logged, never stored, and never changes the run id |
| `GET /api/runs/{id}` | Adds percents in `config` and on fills and closes (`feePercent`, `priceImpactPercent`), `source` (dataset and, when a capture exported it, the capture and case) and a per-scenario `timeline` of stage instants |
| `GET /api/datasets/{id}` | Adds `source`, as on run detail |
| `GET /api/runs/{id}/chart`, `GET /api/datasets/{id}/chart` | Add `extent`, the pair's full data range, independent of `from` and `to`. Candles open and close on the trades each slot executed first and last, proven by the bonding-curve reserves chaining from one trade to the next; a slot that does not chain keeps the id order. Add `millisPerSlot` (the pace measured over the pair's recording, null under 500 slots), `coverage` (the slots each market window was recorded through; trades in the first buy's own slot after it are not recorded) and `estimatedTime` on markers and paper fills: block times are whole seconds, so each slot is placed inside its second at that pace. Optional `tickLimit` (0–5,000, default 0) adds `ticks`: every observation of the window one by one in execution order, with `indexInSlot`, `slotOrderProven`, `estimatedTime`, the execution `price` and `mcap`, and the curve's `curvePrice`/`curveMcap` right after the trade; over the limit `included` is false and only `total` is given |
| `GET /api/usage?period=&captureId=` | The API usage report (below) |

**API usage.** Every HTTP request the backend sends to the Solana RPC or Binance is counted where it is sent, retries and connection probes included, and reported by `GET /api/usage` for the console's API usage page. The units are fixed and named in every response: `requests` are HTTP requests sent, `failedRequests` are transport errors, non-2xx replies other than 429 and JSON-RPC errors, `rateLimitedResponses` are HTTP 429 replies, `responseBytes` are response body bytes received, and `cacheHits` are `getTransaction` answers served by the shared transaction cache without a request. They are request counts, not provider billing credits. `period` is `LAST_HOUR` (default), `LAST_24_HOURS`, `LAST_7_DAYS` or `ALL`; each report states its exact `from` (aligned to 5 minutes) and `to` (now). `captureId` narrows the report to one capture. Counts are kept in memory and added to 5-minute UTC buckets in the `api_usage_buckets` table every `trenching.api-usage.flush-interval` (15 s); reports include counts not yet written. A clean shutdown writes them, but a hard kill loses up to one interval (15 s) of counts. Gradle CLI captures (`captureWalletHistory`, `captureReplayMarkets`, `captureReferenceRates`) do not start the database and are not counted. Rows are never pruned.

To package the UI under `/console/` in the Spring jar:

```powershell
.\gradlew.bat build -PwithConsole
java -jar build/libs/trenching-bot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

On Bash, set `JAVA_HOME` to your JDK 21 before `./gradlew build -PwithConsole`. The opt-in build runs `npm ci` and `npm run build`, then includes `frontend/dist` under `static/console/`. Run the resulting jar with the local profile against local PostgreSQL and open `http://localhost:8081/console/`. Extensionless client routes use the SPA fallback; missing assets return 404. Plain `./gradlew build` remains Node-free and removes bundled console resources left by a previous opt-in build.

| Property / variable | Default | Purpose |
| --- | --- | --- |
| `TRENCHING_CONSOLE_ENABLED` (`trenching.console.enabled`) | `false`; local profile `true` | Gates the console API and bundled UI; keep disabled in production |
| `trenching.console.suggested-wallets` | Decu (`application.yml`) | Public wallets the new-capture form offers first, as a list of `{label, address}`; labels 1–24 characters, addresses base58 and distinct. A suggested wallet's label also names its generated captures. Example: `[{label: Decu, address: 4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9}]` |
| `trenching.api-usage.flush-interval` | `15s` (1s to 300s) | How often counted provider requests are written to `api_usage_buckets`; a hard kill loses at most one interval of counts |
| `trenching.console.capture.root-directory` | `build/captures` | Root for managed capture sessions and root-relative attachment paths; `transaction-cache` under it holds the shared transaction cache; use an ignored directory outside build for retained evidence |
| `marketdata.capture.max-concurrent-requests` / `walletactivity.capture.max-concurrent-requests` | `4` (1 to 16) | Capture requests in flight per RPC endpoint across the whole process; the first capture to use an endpoint fixes its pacing |
| `marketdata.capture.min-request-interval` / `walletactivity.capture.min-request-interval` | `125ms` (20ms to 10s) | Minimum time between two capture request starts on an endpoint; `1` request with `500ms` reproduces the old pacing |
| `SOLANA_RPC_URL` (`trenching.solana.rpc-url`) | Unset (optional) | Secret, environment only; responses expose the host only |
| `trenching.solana.connect-timeout` | `5s` | RPC connection timeout |
| `trenching.solana.read-timeout` | `10s` | RPC response timeout |
| `trenching.solana.cache-ttl` | `30s` | On-demand probe cache and concurrent request coalescing |
| `marketdata.reference-rates.enabled` | `false`; local profile `true` | Display-only SOL/USDT rates from Binance's public API (no key); when off, the USDT view reports `NOT_CONFIGURED` |
| `marketdata.reference-rates.base-url` | `https://api.binance.com` | Binance REST base (https only) |
| `marketdata.reference-rates.connect-timeout` / `read-timeout` | `5s` / `10s` | Rate request timeouts; no retries |
| `marketdata.reference-rates.ticker-cache-ttl` | `30s` | Current SOL price cache, and how long a rate limit or region block is answered without calling Binance |
| `marketdata.reference-rates.max-cached-minutes` | `100000` | Completed minutes kept in memory; the oldest are evicted first |
| `marketdata.reference-rates.max-requests-per-lookup` | `20` | Klines requests per view, 1,000 minutes each; further minutes are `REQUEST_LIMIT` |
| `VITE_API_TARGET` | `http://localhost:8081` | Development proxy target; set before starting Vite |
| `VITE_USE_MOCKS` | `false` | Development-only local fixture responses |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `100MB` | Maximum upload/file and multipart request size; larger requests return 413 |

Existing local databases from before this feature must be reset once because the new run-summary columns are NOT NULL. `docker compose down -v` deletes the local database volume; obtain approval and preserve any data you need before running it, then restart with `docker compose up -d`. Fresh databases are created from the entities. Reference DDL is in `docs/schema/`; no Flyway migration runs. Bonding-curve execution (engine `dip-paper-v2`) needs one more reset: runs stored before it carry `feeBps` and `slippageBps` in their configuration snapshot, which the console can no longer read. Production applies `docs/schema/V20261009120000__bonding_curve_execution.sql` by hand. Schema-v4 trade fields add four nullable `trade_*` columns to `market_observations` (rows stored earlier read back without a trade); `local` and tests add them through `update`, and production applies `docs/schema/V20261015120000__market_observation_trades.sql` by hand before deploying. API usage tracking adds the `api_usage_buckets` table, which the application needs in every profile: `local` and tests create it through `update`, and production must create it by hand with `docs/schema/V20261016120000__api_usage_buckets.sql` before deploying, or `validate` refuses to start. Nothing existing changes, so no local reset is needed.

Secrets and provider URLs containing keys must stay in environment configuration or ignored local secret storage; never commit or log them. No provider credentials are consumed by the fixture runner. Agents never read environment files or secret values. The console's RPC checks do not import replay data.

## Real wallet history capture

The approved next increment uses Helius standard HTTP RPC for a user-run, bounded mainnet sample. `captureWalletHistory` freezes up to 100 distinct successful address-reference signatures from a bounded newest-first scan (`-PscanPages`, default 20 pages of 1,000), counts the failed references it skips without fetching them, retains unavailable/non-signer outcomes and saves audited transaction evidence under ignored build output. `-Pscope=pumpfun-user-buys` scans the wallet's derived Pump.fun user volume accumulator instead of the wallet, which skips other accounts' bot transactions and finds the wallet's own Pump.fun buys ([decision](docs/adr/20261006-pumpfun-user-accumulator-discovery.md)). `inspectWalletHistory` verifies that evidence offline without RPC credentials or PostgreSQL. Neither task runs as part of build/test or starts the backend. Set `SOLANA_RPC_URL` in the capture terminal's environment; the command does not load `.env` files, and an IDE run configuration does not configure a separate terminal.

Follow [real capture instructions](docs/REAL_DATA_CAPTURE.md) for the selected wallet, scan bound, stop reasons, request/byte budgets, resuming a frozen sample and safe inspection. Capture directories from before successful-reference discovery are refused; capture into a new directory. `inspectWalletHistory` also decodes a complete capture offline: only a SOL-quoted Pump.fun bonding-curve buy (`buy` or `buy_exact_quote_in_v3`) signed by the wallet becomes a wallet transaction. Every other selected transaction keeps an explicit decode outcome, and inspection counts rejection reasons and the Pump.fun instructions found (see the [decoder decision](docs/adr/20261003-pumpfun-bonding-curve-buy-decoder.md) and its [V3 amendment](docs/adr/20261010-pumpfun-exact-quote-in-v3-buys.md)). Capture runs stop at a per-run deadline, 10 minutes unless `-PoperationTimeoutMinutes=<1..30>` is given, and print `stopReason`; re-run the same command to resume. A wallet capture alone is not a simulation dataset: it needs the market capture below for same-pair prices at the relevant slots, and `exportRecordedReplay` combines the two. Actual wallet P&L and complete current/closed token-account history are outside this sample's scope. See the [approved plan](docs/plans/archive/20261003-real-wallet-replay.md), the [capture decision](docs/adr/20261003-bounded-wallet-history-capture.md) and the [successful-reference discovery decision](docs/adr/20261003-successful-reference-discovery.md).

`captureReplayMarkets` is the second user-run stage. It requires a complete, decodable wallet capture (`-PwalletCaptureDirectory`, no default) and checks it before it reads `SOLANA_RPC_URL`. For the wallet's first buy of each mint, at slot S, it captures the mint's successful transactions in [S+1, S+4500], the dip-entry horizon (about 30 minutes); a busy mint can hold thousands of trades there. Discovery lists the mint's signatures newest-first, never older than the source buy, bounded by `-PscanPages` (default 20) per window, and fetches at most `-PmaxTransactions` (default 300, up to 50,000) details in total, with request and byte budgets of up to 60,000 and 4 GiB. A window that hits either bound is reported as `COVERAGE_INCOMPLETE` or `OVER_CAP` and yields no observations. `-PcutoffMode=DECIDED` stops each window once its dip-entry outcome is decided ([decided and full mode](docs/REAL_DATA_CAPTURE.md#decided-and-full-mode)); the default is `FULL`. A rerun with a larger `-PscanPages` (up to 500) extends a `COVERAGE_INCOMPLETE` window from its saved cursor. Each completely discovered window has its details checkpointed before discovery starts for the next window. The console can validate that case alone, prepare its exact rate-minute set and export/import its own canonical v5 dataset; corrupt or incomplete siblings remain visible without blocking it. `inspectReplayMarkets` still re-verifies the whole capture offline and extracts one `LAST_TRADE` observation per Pump.fun bonding-curve trade event, now (extractor `PUMPFUN_BONDING_CURVE_TRADE_V2`) also carrying the event's trader, side and amounts, with every exclusion counted by reason ([observation decision](docs/adr/20261004-pumpfun-last-trade-observations.md)). `captureReferenceRates` and `exportRecordedReplay` also keep their full-capture requirements. The rate command records the Binance SOL/USDT minute rate of every captured trade for the market-cap rule ([decision](docs/adr/20261008-reference-rates-in-dataset.md)); the export command turns all three verified captures into a canonical v5 dataset offline, recording how far each window was observed (v3 and v4 datasets still load and replay unchanged; a re-export has a new dataset id, see [the format](docs/REPLAY_FORMAT.md) and the [decision](docs/adr/20261015-chart-trade-actors-and-mcap.md)) and refuses a mismatched or incomplete market capture. See [market capture](docs/REAL_DATA_CAPTURE.md#market-capture), [independent case validation](docs/REAL_DATA_CAPTURE.md#independent-case-validation-in-the-console) and [export](docs/REAL_DATA_CAPTURE.md#export-to-a-replay-dataset). `calibrateExecution` verifies the curve math against each recorded buy, measures latency error and reports network fees and other lamport costs entirely offline; see [calibration](docs/REAL_DATA_CAPTURE.md#calibrate-execution-offline).

## Current verification limits

Console verification on 2026-10-03 passed `./gradlew build` with 174 tests, zero failures/errors, and frontend lint, all 65 Vitest tests, and the production build. The frontend suite includes the real chart export after Vite development optimization, covering a CommonJS interop failure hidden by chart mocks. The opt-in bundled build passed 169 tests before the review fixes; its jar contained the console assets, and subsequent plain packaging removed them. The plain build task graph contains no npm tasks. PostgreSQL tests cover exact P&L, concurrent creation, repeat delivery, encoding rejection without writes, rollback, provenance and persisted exit reasons. See the [delivered console plan](docs/plans/archive/20261002-console-and-monitoring.md) for the complete verification record.

A local HTTP smoke check through Vite's proxy imported the sample and started its run with 201 responses, then repeated both with 200 responses and unchanged identities. It verified three closed positions, P&L `26.089701492540` in the run and dashboard, report download, duplicate upload, and validation errors without extra records. The existing local database was compatible and was not reset. Hosted CI and interactive browser rendering remain unverified; browser automation was blocked by a saved permission setting. No live provider was called during this console verification.

Decoding beyond the single evidenced Pump.fun bonding-curve buy route, market observations beyond Pump.fun bonding-curve trades, a history scope that finds more than the wallet's latest references, streaming recovery and provider retention/latency/coverage validation remain unfinished. History capture has been run against the live provider only by the user; no agent has called it. Market capture has been run against the live provider only by the user; the resulting real dataset replays offline and in PostgreSQL tests. Open positions in a fixed dataset remain open; late data requires a new dataset/run rather than mutating the original experiment. Notifications and real execution are deferred by the weekly plan.

## Repository conventions

See [CLAUDE.md](CLAUDE.md) and [developer tooling](docs/CLAUDE_CODE_GUIDE.md). The original roadmap is preserved for traceability; accepted changes and actual test evidence are in the ADR and implementation status, rather than marking unfinished acceptance criteria complete.


## Postman API checks

Import the collections and environments in [postman](postman/README.md) to check the running application and optionally public Solana devnet. The guide lists the available HTTP endpoints and current coverage limits.
