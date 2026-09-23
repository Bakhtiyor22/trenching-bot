---
name: kotlin-spring-guidelines
description: >
  Kotlin 2.3 + Spring Boot 4.1 + Java 21 conventions for the trenching-bot backend (Solana
  paper-trading research system, package `com.trenching.bot`). Covers the feature-module
  monolith layout (common / config / walletactivity / marketdata / strategy / papertrading /
  portfolio / replay / notification / telegram), module boundaries and ports, entities and
  Flyway migrations, DTOs and mapping, errors and logging, Solana/market-data/Telegram
  integrations, the paper-trading domain invariants (read-only toward Solana, BigDecimal,
  slots, determinism, idempotency), tests with Testcontainers, and the project's strict rules.
  Trigger whenever writing, reviewing, scaffolding, or planning code in
  `src/main/kotlin/com/trenching/bot`. Do not trigger for non-Kotlin work or another repository.
---

# trenching-bot Kotlin + Spring Guidelines

The working guide for this repository. **`CLAUDE.md` at the repo root is the base rule set** and
always wins; this skill expands it with the "how", the examples, and the rules specific to a
paper-trading system. If the two ever disagree, follow `CLAUDE.md` and say so in your output.

## Stack

- Kotlin **2.3.21**, JVM toolchain **21**, Gradle Kotlin DSL with the wrapper
- Spring Boot **4.1.1** — Spring MVC, Actuator, Spring Data JPA
- **Jackson 3** (`tools.jackson.*` packages, `jackson-module-kotlin`), not `com.fasterxml.jackson`
- **PostgreSQL 17**, **Flyway** is the only schema authority, Hibernate `ddl-auto: validate`
- Kotlin compiler plugins: `plugin.spring` (opens Spring stereotypes), `plugin.jpa` (no-arg
  constructors for entities); `-Xjsr305=strict`
- Tests: JUnit 5, `kotlin-test`, `spring-boot-starter-test` (AssertJ, Mockito), Testcontainers
  PostgreSQL via `@ServiceConnection`
- Local infra: `docker-compose.yml` (PostgreSQL only)

Base package: `com.trenching.bot`. Port `8080` (`SERVER_PORT`). Health: `/actuator/health`.

Not on the classpath today: MapStruct, an HTTP client library beyond Spring's `RestClient`, a
WebSocket client, Mockito-Kotlin, MockK. Adding any of them is a dependency decision — ask first
(see `references/dto-and-mapping.md` for MapStruct specifically).

## Read first

Before coding, reviewing or planning:

1. `CLAUDE.md` — the base conventions
2. `references/strict-rules.md` — every time, no exceptions
3. The references for what you are touching (table below)
4. `README.md` — project layout, profiles and configuration
5. `docs/` — the approved plan for the work (`docs/plans/`), decision records (`docs/adr/`) and
   `docs/WEEKLY_EXECUTION_PLAN.md` for the agreed module contracts and open decisions
6. Grep for usages of what you are changing (a port, a DTO, a column) and open only the files that
   actually match

## Environment restrictions

Non-negotiable, partly enforced by `.claude/settings.json`. Full detail is in
`references/strict-rules.md` Sections A–C. The ones to hold in mind before your first tool call:

- **Read-only toward Solana.** Never build, sign or send a transaction; never read, create or
  handle a keypair, seed phrase or private key.
- **Secrets only from the environment.** `.env`, keypair files and `*secret*` files are never read,
  written or quoted. No real token, API key or RPC URL with an embedded key in any file, fixture or
  log.
- **No live network in the default build.** Tests run offline except for the Testcontainers image.
- **Never change git state** unless the user asks. Read-only `git status/diff/log/show` is fine.

## Package layout

A **feature-module monolith**: one Gradle module, one Spring Boot process, one PostgreSQL. Modules
are top-level packages; layers are sub-packages *inside* a module, added when the module needs them.

```
com.trenching.bot
├── TrenchingBotApplication.kt
├── common/          tiny shared value types only (WalletAddress, MintAddress, Slot, ...)
├── config/          application-wide Spring configuration, @ConfigurationProperties wiring
├── walletactivity/  track the configured wallet, dedupe and normalize its swaps
├── marketdata/      token metadata, slot-aware price/trade observations
├── strategy/        normalized wallet trade → versioned trade intents, exit policy
├── papertrading/    +1/+2/+3 slot entries, deterministic fill/fee/slippage model
├── portfolio/       position lifecycle, exits, realized P&L
├── replay/          feed recorded datasets through the same flow, report a run
├── notification/    outbound result/alert delivery (deferred)
└── telegram/        Telegram bot: chat registry, alert sender
```

Only `common`, `config` and the application class exist today; the rest are created as the work
lands. See `references/module-patterns.md` for boundaries, ports and the inner layout.

### Dependency direction

```
walletactivity ─► strategy ─► papertrading ─► portfolio
       │              ▲             ▲             │
       └─► marketdata ┴─────────────┴─────────────┘
                              replay ─► public contracts of all modules
```

A module may depend only on another module's **public contract** (its ports and value types),
never on its internals, JPA entities or vendor DTOs. Within a module:
`adapter → service → repository/port → model`, never the reverse.

## References

| Topic | Reference |
|---|---|
| Strict rules (read every time) | `references/strict-rules.md` |
| When and how to ask the user | `references/ask-protocol.md` |
| Modules, ports, adapters, services, configuration | `references/module-patterns.md` |
| Paper-trading domain invariants | `references/trading-domain.md` |
| Entities, repositories, Flyway, transactions | `references/persistence-patterns.md` |
| DTOs, vendor payloads, mapping | `references/dto-and-mapping.md` |
| Exceptions and logging | `references/errors-and-logging.md` |
| Solana RPC, market data, Telegram, HTTP, schedulers | `references/integration-patterns.md` |
| Build, tests, verification | `references/testing-and-build.md` |

## Core conventions at a glance

- Adapters (controllers, Telegram update handlers, schedulers, fixture/replay entry points) are
  thin: parse input, call one service, map the result. No business logic, no logging.
- Services own business rules and transaction boundaries. They return DTOs or domain types, never
  JPA entities, to anything outside the module.
- Constructor injection through primary-constructor `private val` parameters. No `@Autowired`
  fields and no `lateinit var` dependencies in production code.
- One logger per class: `private val log = LoggerFactory.getLogger(Foo::class.java)`, parameterized
  `key=value` messages.
- **Nullability is the contract.** `!!` is banned in new code.
- Money, prices, quantities, fees and P&L are `BigDecimal` with an explicit scale and
  `RoundingMode`. Raw on-chain amounts are integers. **Never `Double`/`Float`.**
- Slots are `Long` and are the ordering authority in a simulation; timestamps are `Instant` in UTC.
- Anything time- or id-dependent in the domain takes an injected `Clock` / id generator.
- Schema changes are Flyway migrations named `V<yyyyMMddHHmmss>__description.sql`; entities are
  validated against them at startup.
- Literals used more than twice go into a constants `object`.
- **Prefer self-documenting code to comments.** A comment earns its place only for a *why* the code
  cannot express.
- **New logic gets a test written first**, from the requirement.

## Verification

```bash
./gradlew compileKotlin
```

```bash
./gradlew test
```

```bash
./gradlew build
```

`./gradlew test` starts PostgreSQL through Testcontainers, so **Docker must be running**. Read
`references/testing-and-build.md` before interpreting a failure.

## Feature workflow

A new feature **starts as a plan**, not as code:

```
plan in docs/plans/<yyyyMMdd>-<slug>.md → user approves → implement (test first) → review
  → update README / ADRs → move the plan to docs/plans/archive/
```

Use `backplan` to write it, `orchestrate` to split it, `review` to check it.

## Documentation sync

When behaviour changes, update in the same unit of work:

- `README.md` — project layout, profiles, configuration variables, run instructions
- `docs/adr/*` — any decision that affects simulation fidelity, reproducibility, a module contract
  or a provider choice (new ADR; do not rewrite an accepted one — supersede it)
- `docs/plans/*` — the plan the work came from, archived when delivered
- `CLAUDE.md` — only when a new repository-wide convention was agreed with the user

## Reporting

- Describe what you did **in prose. Do not paste code, diffs, or file dumps** into the console —
  reference `path:line`.
- Always state the **schema impact** (new migration, or "no schema change").
- Keep it short.
- **Close every change with a "Files changed" list** — one line per file, path first, then what
  changed in it.
- **Ask when the answer would change the code; decide when it would not.** Full protocol:
  `references/ask-protocol.md`; the `/ask` command runs it on demand.
