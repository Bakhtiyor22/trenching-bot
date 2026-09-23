# DTOs and Mapping

## Three kinds of types — keep them apart

| Kind | Purpose | Where | Shape |
|---|---|---|---|
| **Domain / contract types** | What modules exchange: `WalletTradeObserved`, `MarketObservation`, `TradeIntent`, `PaperFill` | the module's public package (or `domain/`) | `data class` / sealed, vendor-free, Spring-free, JPA-free |
| **DTOs** | What crosses a transport boundary: an HTTP response, an outbound Telegram message, a replay report | the module's `dto/` (or `api/dto/`) | `data class`, `val` only |
| **Vendor DTOs** | A third party's payload: Solana RPC JSON, Helius/Birdeye responses, Telegram Bot API updates | beside the adapter: `<vendor>/dto/` | `data class` matching *their* JSON |

Plus **JPA entities** (`persistence-patterns.md`), which never leave their module's service.

## DTOs

- Kotlin `data class`, immutable `val` properties, explicit types.
- Name for the operation: `SimulationRunReport`, `PositionSummaryResponse`, `AlertMessage`.
- No business logic, no computed property that applies a rule.
- Collections default to `emptyList()` so JSON never carries `null` for a list.
- Nullability is documentation: `String?` means "may be absent", `String` promises it is not.
- **Never expose a JPA entity outward** (HTTP response, outbound message, another module).

## Vendor DTOs

A third party's schema is not our contract.

- Live next to the adapter that uses them, never in a shared package, and **never returned from a
  port** — the adapter maps them to domain types first.
- Field names follow the vendor's JSON; use `@JsonProperty` rather than renaming.
- Ignore unknown properties on inbound types so a vendor addition does not break us.
- Numbers that are money, prices or amounts are read as `String` or `BigDecimal`/`BigInteger` —
  **never through `Double`**.
- Keep the raw reference (signature, slot, source id) when mapping, so the normalized record stays
  auditable.
- Captured sample payloads used in tests are **sanitized** — no API keys, no private data.

## Jackson

This project uses **Jackson 3** (`tools.jackson.databind.*`, `tools.jackson.module.kotlin`). Import
from `tools.jackson`, not `com.fasterxml.jackson.databind` (the annotations package
`com.fasterxml.jackson.annotation` is still used for `@JsonProperty` etc.). Inject the
Spring-configured `ObjectMapper`/`JsonMapper`; do not construct a private one.

## Mapping

`CLAUDE.md` rule: **build DTOs through a MapStruct mapper in a `mapper` sub-package**
(`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`), not through `.copy()` or
constructor calls scattered in services — this applies even when the DTO is built from several
scalar parameters.

**MapStruct is not on the classpath yet.** It needs the `mapstruct` dependency plus an annotation
processor (`kapt` or KSP) in `build.gradle.kts`. The first change that needs a mapper must:

1. Ask the user before adding the dependency and processor (a build change — see `ask-protocol.md`).
2. Once approved, add it in a small dedicated change so the other developer sees the build edit.

Until then, do not silently substitute hand-written mappers for the MapStruct rule — raise it.

Mapper rules regardless of implementation:

1. **Mappers translate, they never decide.** No business rule, no validation, no policy branch.
2. **No repository, service, or network call** inside a mapper. Pre-load and pass data in.
3. **No lazy traversal the caller did not load.**
4. **Null handling is explicit per field.** Never `!!`.
5. **Unit conversion happens exactly once**, at a named place — raw amount ↔ decimal quantity with
   the mint's decimals is a domain function the mapper calls, not arithmetic re-done per mapper.
6. **Never map an entity into another entity.**
7. **A mapper never returns a vendor DTO or an entity across the module boundary.**

## Contract discipline

Types in a module's public contract, and the replay fixture schema, are shared with the other
developer's work:

- Adding an optional field is compatible.
- **Renaming, removing, or changing the meaning/type of a field is a contract change** — ask, bump
  the fixture `schemaVersion` if serialization changes, and update the affected fixtures in the same
  change.
