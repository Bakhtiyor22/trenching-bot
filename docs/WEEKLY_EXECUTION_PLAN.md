# Weekly Architecture and Execution Plan

**Planning window:** 23-29 September 2026 (five working days from plan approval)  
**Team:** Two backend developers; task assignment is intentionally left open  
**System:** Solana paper-trading research system  
**Status:** Proposed; implementation starts only after the decisions in [Decisions required before implementation](#decisions-required-before-implementation) are agreed.

## 1. Current repository baseline

The repository is intentionally a foundation rather than a trading application:

- Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Gradle Kotlin DSL.
- One deployable application with package-level modular-monolith boundaries.
- PostgreSQL 17, JPA, Flyway, Docker Compose, Actuator.
- JUnit 5 and PostgreSQL Testcontainers support.
- Only the application bootstrap, health endpoint tests, and an empty `V1__baseline.sql` migration exist. There is no Solana client, market-data ingestion, trading domain, scheduler, or notification code.
- The README proposes `common`, `config`, `marketdata`, `strategy`, `papertrading`, and `notification`; these are placeholders, not implemented modules.
- Configuration currently contradicts the README: the README says there is no default profile, while `application.yml` activates `local`. Agree the intended behavior before adding provider secrets or CI configuration.
- The repository is on `develop` with no uncommitted changes at planning time.
- A local build could not be executed during planning because the environment had no valid `JAVA_HOME`/Java executable. This is an environment prerequisite, not a known source failure.

This means there is little legacy code to preserve, but contracts and ownership must be fixed before two developers create packages and migrations concurrently.

## 2. Weekly goal

### Goal

Deliver a deterministic, testable paper-trading vertical slice for one configured Solana wallet (the "Decu wallet") that can consume recorded events and run:

`wallet market event -> normalized signal -> strategy decision -> +1/+2/+3 slot paper entry -> position -> rule-based exit -> persistence`

No transaction is signed or submitted. The system remains strictly read-only toward Solana.

### End state

By the end of the planning window:

1. The decisions in this document are recorded as short ADRs or explicitly amended here.
2. Module boundaries and Kotlin interfaces described below compile in one Spring Boot modular monolith.
3. A checked-in fixture representing a Decu wallet swap plus slot-tagged market observations drives the complete flow deterministically.
4. Three independent simulations (+1, +2, and +3 entry slots) can be run for the same signal and persisted without collisions.
5. At least one deterministic exit rule (take-profit, stop-loss, or maximum holding slots) closes a position and records fees/slippage assumptions and realized P&L.
6. Restarting or replaying the same source event is idempotent and does not duplicate signals, fills, positions, or exits.
7. Unit tests and PostgreSQL integration tests pass locally and in CI. A live read-only adapter may be smoke-tested only if provider choice and credentials are available; it is not required for the deterministic acceptance test.
8. Known model limitations are visible in stored run metadata: price source, commitment, fee/slippage model, strategy version, and entry-slot offset.

### Explicitly out of scope this week

- Real orders, wallet keys, signing, custody, or Jupiter swap submission.
- Multiple tracked wallets, portfolio allocation across strategies, a UI, notifications, and user management.
- Microservices, Kafka, Redis, Kubernetes, or a separate analytics database.
- Production-grade high availability and low-latency optimization.
- Arbitrary full-chain historical backfills. This week supports deterministic replay from captured/curated fixtures; broad historical research follows after data quality is proven.
- Sophisticated MEV, pool-reserve, priority-fee, or price-impact simulation.

## 3. Architecture plan

### Architectural style

Use a modular monolith: one Gradle module, one Spring Boot process, and one PostgreSQL database. Boundaries are Kotlin packages plus explicit public application ports. Keep domain logic free of Spring, JPA, JSON, and vendor DTOs. Use synchronous in-process orchestration for this vertical slice; asynchronous infrastructure can be introduced only when measured throughput or failure isolation requires it.

```text
external Solana/fixture source
          |
          v
walletactivity -> strategy -> papertrading -> portfolio
      |             ^            ^              |
      v             |            |              v
  marketdata -------+------------+---------> PostgreSQL
          \                         
           +--------------> replay/research
```

The arrow means "may depend on the public contract of." No module may import another module's `internal`, `infrastructure`, JPA entity, or vendor DTO.

### Proposed package/module boundaries

| Module/package | Responsibility | Public contracts | Dependencies | Build now | Later |
|---|---|---|---|---|---|
| `common` | Tiny shared value types only: `WalletAddress`, `MintAddress`, `TransactionSignature`, `Slot`, UTC time, IDs, decimal quantity/money helpers | Immutable value types | None | Yes | Keep deliberately small |
| `walletactivity` | Track configured wallet, deduplicate source transactions, normalize a wallet swap into a domain event | `WalletActivitySource`, `WalletTradeObserved`, `WalletActivitySink` | `common` | Fixture/replay source and contracts | Live WebSocket/webhook and gap recovery |
| `marketdata` | Resolve token metadata and record slot-aware price/trade observations | `MarketDataSource`, `MarketObservation`, `TokenMetadata`, `MarketDataRepository` | `common` | In-memory/fixture adapter and persistence contract | Vendor REST/WebSocket adapters, pool state parser |
| `strategy` | Turn a normalized wallet trade into explicit, versioned trade intents and exit decisions | `Strategy`, `TradeIntent`, `ExitPolicy`, `StrategyContext` | `common`; public wallet/market contracts | One minimal strategy and configurable exit policy | Filters, scoring, multiple strategies |
| `papertrading` | Schedule entries by target slot, apply deterministic fill/fee/slippage rules, reject impossible/stale fills | `PaperExecutionEngine`, `FillModel`, `PaperOrder`, `PaperFill` | `common`; public market/strategy contracts | +1/+2/+3 scenarios and deterministic fill model | Order-book/AMM price-impact models |
| `portfolio` | Position lifecycle, invariants, P&L, close reasons, position persistence | `PositionService`, `Position`, `PositionRepository` | `common`; public paper-trading contracts | Single-position lifecycle | Risk limits, aggregate portfolio accounting |
| `replay` | Feed ordered normalized events/observations through the same application flow and report a run | `ReplayRunner`, `ReplayDataset`, `SimulationRun` | Public contracts from all modules | Fixture-backed end-to-end runner | Large backfill jobs, experiment comparison |
| `config` | Typed configuration and adapter wiring | `@ConfigurationProperties` classes | Application/infrastructure only | Yes | Operational tuning |
| `notification` | Outbound result/alert delivery | None this week | None | No | After core results are trustworthy |

Within a feature module, use `domain`, `application`, `infrastructure`, and optionally `api` packages. Do not create a cross-cutting `service`, `util`, `model`, or `repository` dumping ground.

### Core integration contracts

The developers agree these semantics before coding; exact Kotlin signatures can be finalized in a small contract-only PR.

| Contract | Required fields/behavior | Producer/consumer modules |
|---|---|---|
| `WalletTradeObserved` | Stable event ID, tracked wallet, signature, source slot, block time if known, side, base mint, quote mint, raw base/quote amounts, decimals, source/commitment | `walletactivity` produces; `strategy` consumes |
| `MarketObservation` | Mint/pair, observed slot, observed time, price and quote currency, liquidity if available, source, source event ID, quality flags | `marketdata` produces; `strategy`, `papertrading`, and `portfolio` consume |
| `TradeIntent` | Intent ID, source event ID, strategy name/version, side, mint/pair, notional, target entry offset and target slot, creation slot, exit-policy snapshot | `strategy` produces; `papertrading` consumes |
| `PaperFill` | Order ID, target/actual slot, price, quantity, fee, modeled slippage, observation ID, fill/rejection reason | `papertrading` produces; `portfolio` consumes; it must reference a market observation |
| `Position` | Position ID, simulation run ID, intent/fill IDs, state, cost basis, quantity, opened/closed slots, realized P&L, close reason, optimistic version | `portfolio` owns; `replay` and reporting consume |
| `ReplayDataset` | Schema version, strictly ordered events, explicit slot and source IDs, dataset checksum | `replay` loads; all core modules are exercised by it |
| Idempotency | Unique source signature + relevant instruction index; unique strategy/version/source-event/offset; one fill per paper order; one active lifecycle per position ID | Enforced at application and database boundaries |

Use raw integer token amounts plus decimals at ingestion boundaries and `BigDecimal` for calculated prices, quantities, fees, and P&L. Never use `Double`. Slots are `Long`; timestamps are `Instant`, but slot is the ordering authority for simulation.

### Flow and transaction boundaries

1. `walletactivity` validates and normalizes a successful Decu transaction; failed or unrecognized transactions are retained as ingestion outcomes but create no signal.
2. `strategy` evaluates the normalized event and emits zero or three independent `TradeIntent`s, for offsets 1, 2, and 3. Each records its strategy configuration snapshot.
3. `papertrading` waits in live mode, or advances deterministically in replay mode, until a market observation at or after the target slot is available. The permitted slot-selection rule must be agreed before coding.
4. The fill model creates a fill or an explicit rejection. No silent fallback to a later/unbounded price is allowed.
5. `portfolio` opens a position from the fill, evaluates exit rules on later observations, closes it, and calculates realized P&L.
6. Application orchestration persists each idempotent state transition. External fetches happen outside database transactions. Short transactions guard state changes and unique constraints guard retries.

For this week, prefer a direct `SimulationOrchestrator` calling application ports over Spring application events. It is easier to order, retry, and test. An outbox/event bus is deferred until there is a real asynchronous-delivery requirement.

## 4. Work sequencing and parallel tracks

No task is assigned in this document. The team will assign owners after agreeing on the blocking decisions. The purpose of this section is to show dependency order and safe parallel work.

### Work tracks

- **Track A — data and replay inputs:** wallet normalization, slot-aware market observations, fixture adapters, and replay datasets.
- **Track B — simulation domain:** strategy intents, paper fills, position lifecycle, exits, and P&L.
- **Track C — persistence and integration:** migrations, repositories, orchestration, and end-to-end tests.
- **Track D — provider research:** compare Yellowstone/RPC and market-data choices without creating a production integration.

Tracks A and B can run in parallel after the shared contracts are accepted. Track D can also run independently. Track C starts its schema work after the domain records are stable and completes the integration after Tracks A and B provide their contracts.

### Sequence

```text
First (shared decision session, <= half day)
  D0 decisions + contract-only PR + fixture schema
       |                              |
       +--> Track A: data plane -------+
       |                              |
       +--> Track B: simulation -------+--> Track C: persistence + E2E
       |
       +--> Track D: provider research
```

After the contract PR merges, Tracks A, B, and D can run in parallel. No implementation task should wait for live API credentials: fixture adapters and in-memory port implementations provide the integration seam.

### Conflict-minimization rules

- Assign exactly one owner to each task and package area before work starts; avoid two active branches editing the same module.
- Assign one migration owner for the week, regardless of who implements persistence, to avoid Flyway numbering conflicts.
- Shared edits to `build.gradle.kts`, root configuration, `replay/**`, or application wiring are made in small dedicated PRs and announced before work starts.
- Do not both add migrations. Reserve migration numbers in the execution board before creating a file.
- Contract changes require both developers' approval and a fixture/version update when serialization changes.

## 5. Paper-trading data and external API requirements

### Important interpretation

"Decu wallet" is treated as a configured Solana wallet address, not as a protocol-specific API. Before implementation, record the exact mainnet address and confirm whether to follow only transactions signed by it or every transaction mentioning it (including associated token accounts).

### Required capabilities for the MVP

| Need | Required capability | Recommended MVP source | Notes |
|---|---|---|---|
| Detect Decu activity | Solana transaction notifications filtered by wallet plus transaction retrieval/parsing | A reliable Solana RPC provider offering HTTP + WebSocket; standard `logsSubscribe` with `mentions` and `getTransaction` is sufficient for one wallet. A provider webhook/parsed stream is an alternative adapter. | `logsSubscribe` supports one mentioned pubkey per subscription. Subscribe at an agreed commitment, persist the signature/slot, and deduplicate. `accountSubscribe` alone is insufficient because it reports state changes, not the complete swap transaction. |
| Recover gaps/restarts | Paginated address history and transaction details | Standard `getSignaturesForAddress` + `getTransaction`; an archival provider method such as Helius `getTransactionsForAddress` is a convenience | Persist a cursor/checkpoint. Live delivery is never assumed exactly once. |
| Identify wallet swaps | Decode transaction instructions and token balance deltas into base/quote changes | Start with stored raw/parsed Solana transactions and a narrow parser for supported swap routes, or a provider's parsed transaction/webhook product | Provider parsing accelerates the MVP but the normalized event must remain vendor-neutral and retain raw signature/slot for audit. |
| Token metadata | Mint decimals, symbol/name where available, token-program identity | On-chain mint account through RPC for decimals; provider/DAS token metadata for display | Decimals are required for arithmetic. Symbol/logo is not required for correctness and may be missing or untrusted. |
| Live price updates | Slot-aware trades/quotes for affected token pairs | A Solana DEX trade/pool stream or a market-data vendor with real-time trades; persist every observation used for a fill/exit | A current-price REST endpoint is useful for valuation but cannot by itself prove the price at target slot +1/+2/+3. |
| +1/+2/+3 entry | Trigger slot, continuous slot progression, and an observation-selection rule | Source transaction slot + Solana `slotSubscribe`/slot-tagged trade stream + persisted observations | Run three isolated scenarios. Define whether fill uses the first eligible trade at/after target slot, the last observation in exactly that slot, or rejects missing slots. Never interpolate silently. |
| Simulated exits | The same continuing price stream plus deterministic exit rules | Reuse persisted `MarketObservation`s | Use bid/ask or conservative slippage when available; otherwise flag midpoint/last-trade assumptions. |
| Deterministic replay | Immutable normalized input dataset with source IDs, slots, and prices actually observed | PostgreSQL capture plus versioned JSON test fixtures this week | Replaying the same dataset and configuration must produce the same checksum/result. |
| Historical research | Archival wallet transactions and historical trade/price data at adequate granularity | Archival RPC/provider plus market-data history provider | Not required for the first vertical slice. Minute OHLCV cannot accurately simulate 400-ms-style slot offsets; historical trades or captured slot-level observations are needed. |

### Required vendor decisions, not necessarily multiple vendors

At least one reliable Solana RPC endpoint is required for any live work. Public mainnet endpoints may be used for a developer smoke test but should not be the planned production feed because rate limits, retention, and delivery guarantees are not under our control.

For live price/trade observations, select one of these approaches after a one-day spike:

1. **Preferred for research fidelity:** derive slot-tagged trades from Solana transactions/pool data through the chosen RPC/stream provider. More parsing work, but event and price share the same slot model.
2. **Preferred for speed:** use a specialist market-data WebSocket/REST vendor (for example Birdeye) that supplies real-time trades and historical trades/OHLCV. Confirm that responses carry sufficient timestamp/slot precision and that the selected plan includes WebSockets before committing.

Helius is a candidate consolidated RPC, parsed-event/webhook, and archival provider, not a domain dependency. Birdeye is a candidate market-data provider. The spike must compare latency, supported DEX coverage, slot/timestamp precision, history depth, rate limits, cost, replay terms, and behavior for new/illiquid tokens.

### Optional APIs/services

- Jupiter Price/Quote APIs: useful for current valuation or an executable-quote comparison, but not the source of truth for slot-exact historical fills.
- A second RPC/provider: useful for failover and data-quality comparison after the MVP.
- Token risk/metadata APIs: useful for scam/liquidity filters later, not required for the core flow.
- ClickHouse/time-series storage: only after PostgreSQL volume/query measurements justify it.
- Notification APIs (Telegram/Slack/email): only after strategy and P&L results are reliable.

### Data-source acceptance checklist

Before an adapter is approved, demonstrate: reconnect and backoff; gap detection/backfill; commitment semantics; stable source IDs; rate-limit handling; raw payload retention policy; slot/timestamp precision; token decimal correctness; illiquid/missing-price behavior; and cost at expected event volume. Secrets must be supplied by environment variables and must never appear in fixtures or logs.

Useful primary references reviewed for this plan:

- [Solana `logsSubscribe`](https://solana.com/docs/rpc/websocket/logssubscribe)
- [Solana `accountSubscribe`](https://solana.com/docs/rpc/websocket/accountsubscribe)
- [Solana `getTransaction`](https://solana.com/docs/rpc/http/gettransaction)
- [Solana `getSignaturesForAddress`](https://solana.com/docs/rpc/http/getsignaturesforaddress)
- [Helius data APIs and archival options](https://www.helius.dev/docs/getting-data)
- [Birdeye plan/WebSocket availability](https://docs.birdeye.so/docs/pricing)

## 6. Testing strategy

### Test pyramid

**Unit tests (fast, no Spring/database):** value-object validation; raw amount conversion; swap normalization; strategy decisions; target-slot calculation; fill selection; fees/slippage; position invariants; exits and P&L; idempotency-key generation. Use fixed `Clock`, deterministic IDs, and builders with explicit slots.

**Module integration tests:** PostgreSQL repositories and Flyway with the existing Testcontainers configuration; JSON/vendor DTO mapping from captured sanitized payloads; adapter contract tests against a fake HTTP/WebSocket server; transactional uniqueness and retry behavior. Spring context is used only where wiring/persistence matters.

**End-to-end application test:** load one versioned fixture into the replay adapter, invoke the same orchestrator used by live mode, and assert persisted signals, three intents, fills/rejections, position transitions, exits, P&L, provenance, and no duplication on a second run.

Live-provider tests are tagged (for example `live`) and excluded from the default build. They require credentials, make no writes to Solana, and assert only schema/connectivity with tolerant values. Default CI must never depend on internet access.

### Independent task testing

- Wallet/market tasks use checked-in sanitized JSON and adapter contract tests; they do not require simulation or persistence implementations.
- Strategy/execution/portfolio tasks use in-memory implementations of the public data ports; they do not require a live provider.
- Persistence tasks use Testcontainers and domain fixtures; they do not require live Solana data.
- The shared E2E fixture is the integration seam. Changes to its input schema or expected outcome require review by both developers.
- Keep `./gradlew test` offline except for image availability and deterministic once dependencies/images are cached. Use a separate `liveTest` task/tag later.

### Required fixtures and test data

1. Successful Decu buy/swap with signature, slot, raw token balances, decimals, and expected normalized event.
2. Successful sell/swap.
3. Failed transaction that emits no trade signal.
4. Duplicate delivery of the same signature/instruction.
5. Out-of-order market observations.
6. Observations for exact +1/+2/+3 target slots, plus a missing-slot case.
7. Illiquid/missing-price and stale-price cases.
8. Exit sequences for take-profit, stop-loss, and maximum-holding-slots (only one rule must ship this week; fixtures define later behavior).
9. Restart/replay dataset proving identical results and no duplicate persistence.

Fixtures contain no API keys or private wallet material. Preserve raw vendor payloads only as sanitized test resources; normalized fixtures carry a `schemaVersion` and checksum.

### Full-flow E2E assertions

Given a fixed wallet event at slot `S` and price observations for subsequent slots:

1. Exactly one normalized wallet event is accepted.
2. The strategy emits the agreed intents, including target slots `S+1`, `S+2`, `S+3`.
3. Each intent uses only an eligible observation under the agreed selection rule; missing data produces an explicit rejection.
4. Each fill references its source observation and records fee/slippage assumptions.
5. Each fill opens an independent position; later observations close it according to the configured rule.
6. Stored P&L matches a hand-calculated expected value at the configured decimal scale/rounding mode.
7. Every record carries simulation run, source event, strategy version, and configuration provenance.
8. Running the dataset twice leaves business-row counts unchanged and returns the same result checksum.

### Acceptance criteria by major work item

| Work item | Acceptance criteria |
|---|---|
| Contracts/value types | Invalid addresses/amounts/slots are rejected; types are immutable and vendor-free; serialization fixture is reviewed by both developers. |
| Wallet normalization | Buy, sell, failed, unknown, and duplicate fixtures produce expected outcomes; signature + instruction identity is stable. |
| Market data | Observations retain slot/source/quality; out-of-order inputs are ordered deterministically; missing/stale data is explicit. |
| Strategy | Same event/config yields the same intents; no floating-point math; strategy/version/config are recorded. |
| Paper execution | +1/+2/+3 targets are correct; only allowed observations fill; fees/slippage and rejection reasons are reproducible. |
| Position lifecycle | Illegal transitions fail; one close only; quantity/cost/P&L rounding is tested at boundary values. |
| Persistence | Fresh migration succeeds on PostgreSQL 17; JPA validates schema; uniqueness prevents duplicate processing; repositories round-trip domain values. |
| Replay/E2E | Full flow passes from one command/test, produces expected persisted state and checksum, and remains unchanged on rerun. |
| Provider spike | Written recommendation covers capability, fidelity, limits, cost, and fallback; no production integration is merged before approval. |

## 7. Team conventions

### Code and package structure

- Base package: `com.trenching.bot.<module>.<layer>`.
- Domain code has no Spring/JPA/vendor imports. Application services depend on ports; infrastructure implements them.
- Default to `internal` for module implementation. Expose the smallest public API possible.
- Use constructor injection; no field injection in production code.
- Prefer small cohesive files and behavior-rich domain types over anemic global services.
- `common` contains only stable primitives used by at least two modules; convenience helpers stay in their owning module.

### Naming and Kotlin style

- Follow official Kotlin style and existing Gradle configuration.
- Domain nouns are singular (`Position`, `TradeIntent`); actions are explicit verbs (`observeTrade`, `openPosition`).
- Ports describe capability (`MarketDataSource`, `PositionRepository`); adapters name technology/vendor (`FixtureMarketDataSource`, later `HeliusWalletActivitySource`).
- Avoid `Manager`, `Helper`, `Util`, `Impl`, ambiguous acronyms, and boolean parameters.
- Prefer `data class` for immutable values/commands/results, sealed hierarchies for finite outcomes, exhaustive `when`, and nullable values only when absence is valid.
- Use `BigDecimal` with an explicitly documented scale and `RoundingMode`; raw on-chain amounts use integer types.

### DTO, domain, and entity separation

- Vendor/API DTOs live under `infrastructure.<vendor>.dto` and are never returned from a port.
- HTTP DTOs, if added, live under `api.dto`.
- Domain models live under `domain` and enforce invariants.
- JPA entities live under `infrastructure.persistence.entity`; map explicitly to/from domain models. Do not annotate domain classes with JPA.
- Persist provider/source and raw-reference metadata so calculations are auditable, without coupling domain types to the vendor payload.

### Error handling

- Expected domain outcomes use typed results/sealed classes: ignored event, rejected fill, missing/stale price, invalid transition.
- Infrastructure failures use a small exception taxonomy (authentication, rate limit, timeout, malformed payload, unavailable) at the adapter boundary.
- Retry only transient idempotent reads with capped exponential backoff and jitter. Never retry validation/permanent parsing failures blindly.
- No empty catches, null-as-error, or silent price fallback. Unexpected failures propagate to an application boundary and are logged once.

### Logging and observability

- Structured key/value logs with correlation fields: `runId`, `sourceEventId`, `signature`, `slot`, `strategyVersion`, `intentId`, `positionId`.
- INFO for lifecycle transitions and summarized adapter health; DEBUG for sanitized mapping detail; WARN for recoverable gaps/stale data; ERROR for exhausted/unexpected failures.
- Never log secrets, full credentials, private keys, or entire unbounded payloads. Wallet addresses and signatures are public but should be logged only when operationally useful.
- Add metrics after the flow works: received/deduplicated/failed events, lag in slots, missing prices, fills/rejections, open positions, adapter latency/reconnects.

### Configuration

- Use validated `@ConfigurationProperties`, grouped by module. No scattered `@Value` strings.
- Secrets come only from environment/secret storage. Safe non-secret defaults may exist in `local`; production fails fast when required values are absent.
- Separate strategy configuration from infrastructure configuration and persist a normalized config snapshot/hash for every simulation run.
- Keep test configuration deterministic; clocks, IDs, commitment, rounding, fees, and slippage must be injectable.

### Database and migrations

- Flyway is the only schema authority; Hibernate remains `ddl-auto=validate`.
- Assign one migration-numbering owner at kickoff. Use `V<n>__lower_snake_case_description.sql`; never edit an applied migration—add a new one.
- Tables/constraints/indexes use `snake_case`; primary keys and all idempotency keys are explicit. Store timestamps as UTC and slots as 64-bit integers.
- Add unique constraints for external identity, not just application-side checks.
- Every migration must pass from an empty PostgreSQL 17 Testcontainer. Destructive migrations require an explicit rollback/data-migration note in the PR.

### Git, commits, and review

- Branch from current `develop`; names use `codex/` only for Codex-created branches, otherwise `feature/<short-scope>`, `test/<short-scope>`, or `docs/<short-scope>` by team agreement. No shared long-lived feature branch.
- Use Conventional Commits: `feat(marketdata): ...`, `test(replay): ...`, `docs(architecture): ...`, `fix(papertrading): ...`.
- Keep PRs small and single-purpose; draft early when a contract is visible. Rebase/update before final review; do not mix formatting with behavior.
- The other developer reviews every contract, migration, money/P&L calculation, and integration adapter change. Require green tests and resolved comments before merge.
- PR description includes intent, contract/schema changes, tests run, sample result, risks/assumptions, and follow-ups. Both developers run the shared E2E test before the integration PR merges.

### Test conventions

- Tests mirror production packages. Name tests as readable behavior in backticks and structure them Given/When/Then without excessive comments.
- One behavior per unit test; parameterize offset/rounding edge cases.
- Unit tests end with `Test`; database/provider contract tests end with `IntegrationTest`; live tests are tagged `live`.
- No real network, current clock, random unseeded values, sleeps, or shared database state in the default suite.
- Builders/fixtures must require economically important fields rather than hide them behind unrealistic defaults.

## 8. Lightweight development methodology

Use a one-week, hypothesis-driven Kanban flow rather than Scrum.

1. **Kickoff (60-90 minutes):** settle D0 decisions, define one fixture and hand-calculated expected result, and reserve file ownership/migrations.
2. **Board:** `Ready -> In progress -> Review -> Integration -> Done`; work-in-progress limit of one implementation task per developer. A review does not count against the reviewer's WIP.
3. **Daily 10-minute sync:** yesterday's evidence, today's target, blocker/contract change. Update this document/board asynchronously; no status meeting beyond this.
4. **Thin vertical slices:** merge contracts first, then deterministic behavior, then persistence, then live adapter experiments. Each task ends in executable evidence, not percentage complete.
5. **Decision log:** any choice affecting fidelity or reproducibility gets a short ADR: context, decision, alternatives, consequences, revisit trigger.
6. **Research discipline:** every run records dataset/config/code version and result checksum. A strategy result without provenance is considered invalid.
7. **Midweek integration checkpoint:** run both branches against the shared fixture before either stream grows further.
8. **End-of-week demo/retro (30 minutes):** replay the full flow, compare +1/+2/+3 outcomes, record data-quality gaps, and choose the next hypothesis. Keep/drop/change decisions replace velocity metrics.

Definition of Done: reviewed code; acceptance criteria met; deterministic tests pass; migration/contract docs updated; observable failure behavior exists; no secret or live-network dependency in default CI.

## 9. Decisions required before implementation

Resolve these in D0. Items marked **blocking** change contracts or simulation meaning.

Update 2026-10-08: items 2, 3, 4, 6, 8 and 11 are now settled by the [dip-entry strategy decision](adr/20261008-dip-entry-strategy.md): one cycle per token from the tracked wallet's first buy, a dip entry filled at the next slot's trade, take-profit and stop-loss exits within a 4,500-slot horizon. Item 12 gains a derived USD use: the market-cap rule reads SOL/USDT rates stored in the dataset ([decision](adr/20261008-reference-rates-in-dataset.md)).

Update 2026-10-09: item 7 (costs) and the execution part of items 4 and 6 are settled by the [bonding-curve execution decision](adr/20261009-bonding-curve-execution.md), engine `dip-paper-v2`: fills trade against the fill trade's post-trade Pump.fun curve in raw integer units (price impact from the curve, rounding down), fees are the trade's own protocol and creator basis points (rounded up), a fixed `transactionCostSol` (default 0.001 SOL) is charged per transaction, `latencySlots` (default 1) replaces the next-slot rule, and an entry the curve cannot fill is rejected as `EXCEEDS_CURVE`. A slippage tolerance with failed transactions remains deferred.

1. **Decu identity and scope (blocking):** exact wallet address; mainnet/devnet; signer-only vs any mentioning transaction; include associated token accounts; which swap programs/routes count.
2. **Copied action (blocking):** mirror Decu buys only, sells only to exit, or both? Does a Decu sell force our exit or do only local exit rules apply?
3. **Entry scenarios (blocking):** run +1/+2/+3 as three independent experiments (recommended) or choose one configured offset per run.
4. **Eligible fill price (blocking):** first observed trade at/after target slot is recommended for a conservative deterministic MVP. Define maximum wait in slots, missing-slot rejection, quote currency, and stale threshold.
5. **Event finality (blocking):** detect at `processed` for realistic latency then reconcile at `confirmed`, or act only at `confirmed` for simpler/reorg-safe results. Recommended MVP: `confirmed`; measure processed mode later.
6. **Sizing (blocking):** fixed quote notional per scenario is recommended. Define value, allowed quote assets, decimals, and insufficient-liquidity behavior.
7. **Costs (blocking):** fee, slippage, priority fee, and price-impact assumptions; explicit zero is still a recorded model choice. Agree scale and rounding mode.
8. **Exit policy (blocking):** choose one first-week rule and tie-break ordering when TP/SL/timeout appear in the same slot. Decide whether Decu's sell is an exit signal.
9. **Market source/provider (blocking for live, not fixtures):** RPC vendor, price/trade approach, plan/cost, credentials, and retention rights. Complete the provider spike before merging a production adapter.
10. **Historical fidelity:** captured-forward replay only this week (recommended) versus purchasing/importing granular history now.
11. **Position concurrency:** permit multiple open positions for the same mint across source events, or one per strategy/offset/mint. Recommended MVP: one per strategy run/offset/mint with explicit duplicate/rejection.
12. **Rounding and P&L currency:** recommended `BigDecimal`, quote-asset P&L as source of truth, USD as optional derived reporting value.
13. **Retention/audit:** how long to retain raw provider payloads versus normalized records; scrub policy and storage budget.
14. **Build environment:** establish JDK 21 and Docker on both machines/CI before claiming the baseline or weekly work is green.
15. **Spring profile default:** remove the implicit `local` profile to match the README's fail-explicitly intent (recommended), or document and test the current implicit-local behavior.

## 10. Prioritized implementation backlog

Task assignment is intentionally omitted so the team can distribute work itself.

This is the complete prioritized path to the vertical slice, not a claim that every row fits into one calendar week. The rough estimates total more than ten developer-days, so the team must set a weekly cut line after assigning owners. Preserve the dependency order; do not reduce scope invisibly by marking unfinished acceptance criteria as done.

### Priority and difficulty scales

- **P0 — blocking/critical:** required for the weekly vertical slice or blocks multiple other tasks.
- **P1 — important:** materially improves validity or readiness, but the fixture-based vertical slice can finish without it.
- **P2 — next:** deliberately deferred until the P0 flow is proven.
- **S — small:** up to half a developer-day with limited uncertainty.
- **M — medium:** roughly one developer-day or several known moving parts.
- **L — large:** roughly two developer-days, meaningful integration work, or substantial domain reasoning.
- **XL — extra large:** more than two developer-days or high uncertainty; split before assigning.

Difficulty is an engineering estimate, not a promise. If a task grows to XL, stop and split it rather than carrying an oversized task through the week.

| # | Task | Priority | Difficulty | Dependencies | Description | End result | How to test | Status |
|---:|---|:---:|:---:|---|---|---|---|---|
| 1 | Agree MVP semantics and hand-calculated example | P0 | M | None | Resolve the blocking decisions in Section 9: Decu identity/scope, supported actions, entry offsets, fill selection, commitment, sizing, costs, exit rule, concurrency, and rounding. Work one representative trade through all calculations manually. | A committed decision record with no ambiguous simulation behavior and one table of exact expected intents, fills, exit, fees, and P&L. | A developer who did not write the example can reproduce every expected number and state transition without asking for clarification. | Ready |
| 2 | Restore and verify the build environment | P0 | S | None | Install/configure JDK 21 and Docker on each development/CI environment; resolve the invalid `JAVA_HOME`; decide whether the `local` Spring profile is implicit or explicit. | A clean checkout builds consistently, PostgreSQL Testcontainers start, and configuration behavior matches documentation. | Run `./gradlew build` and the existing health/context tests from a clean environment. | Blocked locally: Java unavailable during planning |
| 3 | Freeze public contracts and value types | P0 | M | Task 1 | Define vendor-neutral value types and the public contracts for wallet events, market observations, strategy intents, fills, positions, replay datasets, errors, and idempotency. This PR contains contracts and contract tests only. | Both data and simulation work can compile against stable interfaces without importing each other's implementation or vendor DTOs. | Compile contract consumers using fakes; validate addresses, slots, raw amounts, decimal rules, serialization version, and idempotency-key examples. | Ready |
| 4 | Define versioned replay fixture format | P0 | M | Tasks 1, 3 | Specify the JSON fixture schema and create sanitized inputs for a buy, sell, failed transaction, duplicate, out-of-order updates, +1/+2/+3 observations, missing/stale prices, and an exit path. | A deterministic, checksummed dataset represents the complete use case and contains no credentials or private material. | Schema validation and checksum tests pass; the dataset loads without network or database access. | Ready |
| 5 | Implement wallet transaction normalization and deduplication | P0 | L | Tasks 3, 4 | Convert supported raw/parsed Solana transaction fixtures into canonical wallet-trade events. Classify buy/sell/ignored/failed inputs and derive a stable signature-plus-instruction identity. | Supported Decu swaps produce exactly one correct domain event; unsupported, failed, and duplicate inputs produce explicit non-signal outcomes. | Parameterized unit tests cover buy, sell, failure, unknown protocol/instruction, malformed data, and duplicate delivery. | Ready |
| 6 | Implement slot-aware market observations and fixture adapter | P0 | L | Tasks 3, 4 | Model market observations with slot, price, source, timestamp, liquidity/quality metadata, and deterministic ordering. Provide the fixture/in-memory adapter used by tests. | Consumers can request eligible observations for target slots and receive a value or explicit missing/stale outcome with full provenance. | Unit/contract tests cover exact slot, first later slot, maximum wait, missing data, stale data, duplicate source IDs, and out-of-order arrival. | Ready |
| 7 | Implement strategy decisions and +1/+2/+3 intents | P0 | M | Tasks 1, 3 | Evaluate a normalized wallet event and produce the agreed independent entry scenarios with fixed sizing and a strategy/configuration snapshot. | The same event and configuration always produce the same zero-or-three intents with target slots `S+1`, `S+2`, and `S+3`. | Pure unit tests cover eligible/ineligible events, already-open policy, offsets, sizing, IDs, and strategy-version provenance. | Ready |
| 8 | Implement deterministic paper fill model | P0 | L | Tasks 1, 3, 6, 7 | Select an eligible observation for each intent and calculate quantity, fees, slippage, and fill or rejection under the agreed rules. Use no real transaction execution. | Every paper order has one auditable fill or explicit rejection referencing the exact market observation and model assumptions used. | Unit tests cover all offsets, missing/stale prices, wait limits, decimal/rounding boundaries, fees/slippage, and retry idempotency. | Ready |
| 9 | Implement position state machine, exit rule, and P&L | P0 | L | Tasks 1, 3, 8 | Enforce explicit position transitions, evaluate the first agreed exit rule, handle competing exit triggers deterministically, and calculate realized P&L. | A fill opens one valid position; a later observation closes it once with an explicit reason and hand-verifiable P&L. Illegal or duplicate transitions fail safely. | Unit tests cover open/close, TP/SL/timeout or chosen rule, tie-breaking, duplicate close, invalid transition, recovery state, and rounding boundaries. | Ready |
| 10 | Design and implement persistence schema/repositories | P0 | L | Task 3; stable records from Tasks 5-9 | Add Flyway migrations, JPA entities/mappers, repositories, unique constraints, and indexes for runs, source events, observations used by trades, intents, fills, and positions. Do not put vendor DTOs in the database model. | A fresh PostgreSQL 17 database stores and restores the complete auditable lifecycle; uniqueness constraints prevent duplicate business records. | Start an empty Testcontainer, run all migrations, validate JPA, round-trip each aggregate, and deliberately attempt duplicates/concurrent state updates. | Ready |
| 11 | Implement simulation orchestrator and replay runner | P0 | L | Tasks 5-9 | Connect the modules through their public ports using one deterministic application flow. Keep live-provider access outside the core and use the same orchestration semantics intended for live mode. | One command/test loads a replay dataset and produces normalized events, intents, fills/rejections, positions, exits, and a run summary without network access. | In-memory integration test asserts order, results, provenance, checksum, and identical output across repeated runs. | Ready |
| 12 | Complete PostgreSQL end-to-end vertical-slice test | P0 | M | Tasks 4, 10, 11 | Run the full fixture through the Spring application and real PostgreSQL Testcontainer, then repeat it to prove idempotency. | The required flow is persisted end to end, expected +1/+2/+3 results match the hand-calculated example, and rerunning creates no duplicates. | Assert every persisted state and foreign-key/provenance link, row counts after two runs, P&L values, and final result checksum. | Ready |
| 13 | Run midweek contract/integration checkpoint | P0 | S | First usable versions of Tasks 4, 6-9 | Integrate the fixture and both core tracks early to detect contract drift before persistence and orchestration are finalized. | A written list of mismatches is resolved or converted into explicit follow-up tasks; no silent schema/semantic divergence remains. | Run the shared fixture through in-memory ports and review the emitted event/intent/fill/position objects together. | Ready |
| 14 | Research provider/API choice | P1 | M | Task 1 provider criteria | Compare Yellowstone gRPC/streaming, standard RPC/WebSocket fallback, parsed transaction support, on-chain pool/curve pricing, archival access, latency, precision, DEX coverage, limits, and cost. Do not implement a production adapter. | A short recommendation names the primary and fallback sources, required plan/credentials, measured or documented limitations, and a go/no-go criterion for live integration. | Inspect representative payloads and complete the data-source acceptance checklist; confirm slot precision for the proposed fill model. | Ready |
| 15 | Document operation, limitations, and decisions | P1 | M | Tasks 1, 12, 14 | Update README/ADRs with run instructions, contracts, state machine, data assumptions, known optimism in paper results, recovery behavior, and deferred work. | A new contributor can understand and reproduce the deterministic experiment and knows what is not yet production-ready. | Follow the instructions from a clean checkout and perform an independent review against Sections 2, 5, 6, and 9. | Ready |
| 16 | Demonstrate the weekly deliverable | P1 | S | Tasks 12, 15 | Present one replay run, compare +1/+2/+3 outcomes, show persistence/idempotent rerun, and record data-quality gaps and the next research hypothesis. | A reproducible demo and a short decision note state whether the next week should prioritize live data capture, fill-model refinement, or strategy changes. | Another team member runs the same dataset/configuration and obtains the same checksum and results. | Ready |

### Deferred P2 backlog — not part of this week's commitment

| Task | Priority | Difficulty | Start condition | End result |
|---|:---:|:---:|---|---|
| Production Yellowstone/RPC wallet-activity adapter | P2 | XL | Provider decision approved and fixture contracts stable | Reconnecting, gap-aware, read-only live ingestion produces the same canonical wallet events as fixtures. Split into connection, parsing, checkpointing, and health subtasks before assignment. |
| Live on-chain curve/pool market-state adapter | P2 | XL | Supported protocols and price formula agreed | Slot-tagged live observations match verified on-chain examples and expose missing/uncertain quality explicitly. Split by protocol before assignment. |
| Historical wallet/market backfill | P2 | XL | Replay fidelity and provider retention confirmed | A bounded date/slot range imports reproducibly with checkpoints, provenance, and completeness reporting. |
| Telegram control and notifications | P2 | M | Core state/result semantics stable | Authorized start/stop/status/result commands and feed-health alerts wrap the core without becoming a trading dependency. |
| Real execution adapter | P2 | XL | Paper results justify controlled live testing and a separate security review is complete | Still deferred: transaction construction/signing/sending is isolated behind an execution port and cannot be enabled accidentally. |

### Recommended merge order

1. Decisions/document amendment and contract-only PR.
2. Independent unit-tested data-plane and simulation-plane PRs in either order.
3. Persistence migration/repositories.
4. Shared replay/E2E integration PR.
5. Provider adapter work only after the spike decision; otherwise carry it into next week without compromising the deterministic deliverable.

### Approved real capture increment (2026-10-03)

The user approved the [bounded real wallet replay plan](plans/archive/20261003-real-wallet-replay.md): mainnet, the supplied wallet and latest 100 address-reference transactions, with Helius standard HTTP RPC adopted for user-run acquisition. Its first unit provides an audited frozen history sample and offline inspection without database or schema changes. Later units added the Pump.fun bonding-curve buy decoder, user-run same-mint market capture and an offline export to a v1 dataset. The user's capture produced one real source buy with 17 observations, which replays to three closed paper positions. The broader production/backfill backlog above is not marked delivered. See the [capture ADR](adr/20261003-bounded-wallet-history-capture.md) and [user instructions](REAL_DATA_CAPTURE.md).
