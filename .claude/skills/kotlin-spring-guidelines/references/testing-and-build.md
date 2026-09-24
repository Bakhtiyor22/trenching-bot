# Testing and Build

## Environment

- JDK 21 via the Gradle toolchain. `JAVA_HOME` must point at a JDK — on a machine where `java` is not
  on `PATH`, set it (a per-developer `env.JAVA_HOME` in `.claude/settings.local.json` does this for
  Claude Code sessions).
- **Docker must be running** for `./gradlew test`: integration tests start PostgreSQL 17 through
  Testcontainers.

## Commands

On Windows PowerShell use `.\gradlew.bat`; the Bash tool accepts `./gradlew`.

| Purpose | Command |
|---|---|
| Compile main sources (fastest feedback) | `./gradlew compileKotlin` |
| Compile tests too | `./gradlew compileTestKotlin` |
| One test class | `./gradlew test --tests "*FillModelTest"` |
| One test method | `./gradlew test --tests "*FillModelTest.rejects*"` |
| Full test suite | `./gradlew test` |
| Full build (compile + tests + jar) | `./gradlew build` |
| Jar without tests | `./gradlew assemble` |

`bootRun` (with the `local` profile and the Compose database) needs the user's approval — see
`strict-rules.md`.

## What exists today

- `TrenchingBotApplicationTests` — context load + datasource connects, against Testcontainers.
- `health/HealthEndpointTest` — `/actuator/health` reports `UP` including `db`.
- `support/PostgresTestContainerConfiguration` — the `@ServiceConnection` PostgreSQL container;
  `@Import` it into any test that needs the real database.
- `src/test/resources/application-test.yml` — the `test` profile (`@ActiveProfiles("test")`).

Existing tests assert with `kotlin.test` (`assertEquals`). AssertJ and Mockito are also available
through `spring-boot-starter-test`; stay consistent within a test class.

## Test pyramid

**Unit tests — the bulk.** Plain JUnit 5, no Spring context, no database, no network. Value-type
validation, raw-amount conversion, swap normalization, strategy decisions, target-slot calculation,
fill selection, fees/slippage, position transitions, exits and P&L, idempotency-key generation.
Fixed `Clock`, deterministic ids, builders with explicit slots.

**Integration tests.** Spring context and/or PostgreSQL via Testcontainers, only where wiring or
persistence matters: Flyway migrations on an empty database, JPA validation, repository round-trips,
unique-constraint behaviour, vendor-JSON mapping from sanitized captured payloads, adapter contract
tests against a fake HTTP server.

**End-to-end replay.** A versioned fixture through the same orchestrator used by live mode, against
PostgreSQL; asserts persisted state, provenance, P&L against a hand-calculated value, and that a
second run creates no duplicates.

**Live tests.** Tagged `live`, excluded from the default `test` task, require credentials, write
nothing to Solana, assert only schema/connectivity. Never part of CI.

## Conventions

- Mirror the production package: a test for `com.trenching.bot.papertrading.FillModel` lives in
  `src/test/kotlin/com/trenching/bot/papertrading/`.
- Class names: `<ClassUnderTest>Test` for unit tests; `<Subject>IntegrationTest` for database or
  adapter-contract tests.
- **Method names are backtick sentences** describing behaviour and consequence:
  `` `rejects the +2 entry when no observation exists within the max wait` ``.
- One behaviour per test; parameterize offsets and rounding edge cases (`@ParameterizedTest`).
- Given / When / Then structure without narrating comments.
- Assert the negative too: nothing persisted, nothing sent, no second fill.
- **Prefer fakes over mocks**: an in-memory implementation of a port is clearer and survives
  refactoring. Mockito works on Kotlin classes (inline mock maker) but `any()` returns null for
  non-null parameters — avoid it for Kotlin signatures; Mockito-Kotlin/MockK are not on the
  classpath and adding one is a dependency decision.
- Test builders require the economically important fields (price, slot, decimals, notional) rather
  than hiding them behind unrealistic defaults.
- **Forbidden in the default suite:** real network, the current clock, unseeded randomness,
  `Thread.sleep`, shared database state between tests, real Telegram chats.
- Fixtures under `src/test/resources` are sanitized, carry a `schemaVersion`, and contain no keys.

## TDD loop — tests come from the requirement

**Write the test before the implementation, every time you add logic.** Take the behaviour from the
requirement (the task, the plan in `docs/plans/`, an ADR, the hand-calculated example), not from code
you just wrote — a backfilled test mostly restates the implementation and rarely catches a defect.

1. **RED** — the narrowest failing test for one behaviour; confirm it fails for the expected reason.
2. **GREEN** — the smallest production change that passes.
3. **REFACTOR** — clean up only once green.

Repeat per behaviour. Run `./gradlew compileKotlin` constantly and the targeted test class between
steps; `./gradlew build` once at the end.

Pure plumbing (a field forwarded through an existing mapping, a rename, wiring with no new branch)
does not need a test first — say why you skipped it.

## Interpreting failures

- `Could not find a valid Docker environment` / Testcontainers startup errors → Docker is not
  running. That is an environment failure: report it with the output, say which tests could not run,
  and do not claim a green build.
- `JAVA_HOME is not set` / `no Java executable` → the JDK is not configured for this shell.
- `Schema-validation: missing table/column` at context start → entity and migration disagree; fix the
  mismatch, never switch `ddl-auto` away from `validate`.
- **Never delete, `@Disabled`, or weaken a failing test to get green.**

## Dependencies and build changes

- Do not add or bump a dependency, plugin or Gradle setting without the user's approval.
- Build changes (`build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`) go in small,
  dedicated changes so the other developer notices them.

## Verification reporting

State the exact commands you ran and their real result. If something failed or could not run —
Docker down, JDK missing, a denied tool — say so with the output. Never describe unverified work as
verified.
