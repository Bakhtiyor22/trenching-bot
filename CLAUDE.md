# Project conventions

Rules for working in this repository. Keep this file growing as more come up.

Adapted from `~/.claude/rules/*.md` (written for Java/Lombok/MapStruct) to this
project's actual stack: Kotlin, Spring Boot, a **feature-module** monolith
(see [README.md](README.md#project-layout)), not flat technical layers. Where
a global rule assumed flat layers (`controller/`, `service/`, ...), it applies
here as a sub-package *inside* a module once that module is big enough to need
it (e.g. `telegram/model`, `telegram/repository`), not as a top-level package.

## Environment file privacy

- Agents must never read, inspect, search the contents of, copy, print, quote,
  write or edit `.env`, `.env.*` or `*.env` files, including example/template
  files. Exclude them from content searches, bulk reads, archives and diagnostics.
- Do not expose their contents indirectly through tools, subprocesses, browsers,
  logs or generated artifacts. Never inspect or print secret environment values,
  including `SOLANA_RPC_URL`.
- Ask the user to configure needed variables locally. Ask only for variable names
  or whether they are configured, never secret values. Application code may
  consume environment configuration without exposing its contents to the agent.

## Claude Code skills and agents

Project tooling lives in `.claude/`; the developer guide is
[docs/CLAUDE_CODE_GUIDE.md](docs/CLAUDE_CODE_GUIDE.md). Route work like this:

| Situation | Use |
|---|---|
| Writing, changing, reviewing or planning Kotlin code | `kotlin-spring-guidelines` skill (conventions; always loaded by the others) |
| A new feature, design, module contract or schema design | `backplan` skill → plan in `docs/plans/`, stop for approval |
| An approved plan or multi-part task to implement | `orchestrate` skill → `backend-developer` per part, then `backend-reviewer` |
| A scoped implementation part | `backend-developer` agent |
| "Review this" / after an implementation | `review` skill or `backend-reviewer` agent |
| Unclear requirements before starting | `/ask` command (user-invoked only) |

A new feature starts with `backplan`, not code. Small fixes go straight to
implementation, test first.

## Frontend work belongs to Codex

- Claude and its sub-agents never write or edit anything under `frontend/`:
  no components, styles, types, mocks or tests, not even a one-line fix.
- When a plan or task needs frontend changes, write a self-contained brief
  for Codex in `docs/plans/` instead: files, exact changes, the HTTP
  contract, test expectations and the verify commands. Then hand it to the
  user. Backend parts may define the HTTP contract the frontend consumes.
- Reading frontend code, running its tests/lint/build and checking it in the
  browser for verification is still allowed.

## Database schema

- There is no Flyway for now (removed 2026-10-02). The JPA entities are the
  schema: Hibernate runs `ddl-auto: update` in the `local` and `test` profiles
  and `validate` in `prod`.
- Declare every business unique key and lookup index on the entity, via
  `@Table(uniqueConstraints = [...], indexes = [...])`. Code that relies on a
  duplicate insert failing (e.g. `SimulationRunLedger`) only works if the
  constraint is declared there.
- `update` only adds tables and columns, plus the unique keys and indexes
  declared on entities. It never drops, renames, narrows or retypes anything.
  After such a change, reset the local database with `docker compose down -v`.
- `prod` only validates, so any schema change must be applied to the
  production database by hand before deploying.
- [docs/schema/](docs/schema/) keeps the last Flyway SQL for reference. It also
  holds the CHECK constraints, composite foreign keys and the partial unique
  index `uq_positions_open_scenario`, which Hibernate does not create.

## Code style

- Don't duplicate logic. Extract a helper method/function and reuse it instead
  of copy-pasting a variant.
- Keep responsibilities isolated per layer. A thin adapter (a controller, a
  Telegram update handler, etc.) only adapts the transport — parses input,
  calls a service, maps the response. No business logic there. Business logic
  lives in services; persistence details stay behind repositories.
- If a literal value (string, number, path, key) is used more than twice,
  pull it into a constants object instead of repeating it.
- No cross-layer imports downward: a service can use a repository and model,
  never the reverse.

## JPA entities

- Entities are data holders — no business logic, no validation methods, no
  service calls inside them. Put that in the service that uses them.
- Use a plain `class` with `var`/`val` properties, **not** a `data class`.
  Generated `equals`/`hashCode`/`toString`/`copy` on all properties breaks
  with lazy-loaded proxies and mutable collections — a well-known Kotlin+JPA
  pitfall. See [TelegramChat.kt](src/main/kotlin/com/trenching/bot/telegram/TelegramChat.kt)
  for the pattern this project already follows.
- `@Enumerated(EnumType.STRING)` always — never `ORDINAL` (breaks when enum
  order changes).
- Explicit column constraints (`nullable`, `length`, `precision`, `scale`) —
  don't rely on JPA defaults.
- Relationships: `FetchType.LAZY` always. Use `JOIN FETCH` or `@EntityGraph`
  for anything always needed with its parent; never trigger N+1 by touching a
  lazy collection inside a loop.
- `@Transactional` at the service layer only — never on a controller/handler,
  never on a repository method (except a bulk `@Modifying` query).

## DTOs and mapping

- Kotlin `data class` for DTOs — immutable (`val` properties), the Kotlin
  equivalent of a Java record.
- Build DTOs through a MapStruct mapper in a `mapper` sub-package
  (`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`), not
  via manual `.copy()`/constructor calls scattered in services — this applies
  even when the DTO is built from several scalar parameters, not just from
  one entity.
- Never expose a JPA entity directly outward (API response, outbound message)
  — always map to a DTO.

## Error handling

- Domain errors extend one shared unchecked base exception per module/area;
  one specific subclass per error case, with a message that includes the
  relevant ids/values.
- Never silently swallow an exception. Never use exceptions for control flow.
- Log `ERROR` for unexpected failures (pass the exception as the last log
  argument so the stack trace is captured); `WARN` for expected-but-abnormal
  conditions (a retry, a skipped/unconfigured feature, a business-rule miss).

## Logging

- One logger per class via `LoggerFactory.getLogger(...)` — Kotlin has no
  Lombok `@Slf4j`, so this is the explicit equivalent; see
  [TelegramAlertService.kt](src/main/kotlin/com/trenching/bot/telegram/TelegramAlertService.kt).
- Parameterized messages (`log.info("order id={}", id)`), never string
  concatenation.
- Structured `key=value` fields so logs stay aggregation-friendly.
- Never log secrets, tokens, or PII.
- No logging in the thin adapter layer — log business events at the service
  layer.

## Naming

- Interfaces: no `I` prefix, plain PascalCase (`TelegramAlertSender`, not
  `ITelegramAlertSender`).
- Add an interface for a service only when another module needs to depend on
  the abstraction without depending on its implementation (see README's
  "narrow Kotlin API" rule) — not by default for every single-implementation
  service. Where one exists, `XyzService` (or a purpose-named port like
  `AllowedChatIds`) is the interface, `XyzServiceImpl`/a concrete class is
  the implementation.
- Repositories: suffix `Repository`.
- Constants: a Kotlin `object` (the equivalent of Java's `public static
  final` + private constructor), named for what it groups.
- Tests: mirror the source class name + `Test` suffix, method names as
  backtick-quoted descriptive sentences (this repo's existing style, e.g.
  `` `posts the message to every allowed chat` ``) rather than
  `shouldX_whenY` camelCase.
