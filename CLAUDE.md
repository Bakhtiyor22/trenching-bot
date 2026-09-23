# Project conventions

Rules for working in this repository. Keep this file growing as more come up.

Adapted from `~/.claude/rules/*.md` (written for Java/Lombok/MapStruct) to this
project's actual stack: Kotlin, Spring Boot, a **feature-module** monolith
(see [README.md](README.md#project-layout)), not flat technical layers. Where
a global rule assumed flat layers (`controller/`, `service/`, ...), it applies
here as a sub-package *inside* a module once that module is big enough to need
it (e.g. `telegram/model`, `telegram/repository`), not as a top-level package.

## Database migrations

- Name Flyway migrations with a UTC timestamp, not sequential numbers:
  `V<yyyyMMddHHmmss>__description.sql` (e.g. `V20260923105308__telegram_chats.sql`).
  Sequential `V1`, `V2`, ... collide when two developers add a migration on
  separate branches at the same time; timestamps don't.
- Get the timestamp with `date -u +"%Y%m%d%H%M%S"`.

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
