# Persisted replay increment

Implements WEEKLY_EXECUTION_PLAN tasks 10–12 under the user's authorization to continue implementation. Fixture semantics remain those accepted in ADR 0001.

1. Add MapStruct/kapt to follow the repository mapping convention.
2. Add timestamped, additive Flyway tables for runs, normalized/ignored wallet events, market observations, intents, filled/rejected orders and positions. Persist run/config/engine identity, exact inputs, source references and economics; no provider DTOs.
3. Each feature owns its persistence entities, repositories, mappers and public store port. Replay coordinates the ports inside a single short transaction, after pure calculations and input loading. No remote call occurs in a transaction.
4. Record dataset/config/engine version in run identity. Re-delivery returns the existing verified result. Unique keys enforce source identity, scenario intents and one fill/position per order; optimistic versions protect position state. A failed transaction commits no partial lifecycle.
5. Test against PostgreSQL 17 Testcontainers: empty-schema Flyway/JPA validation, complete round-trip, exact economics/provenance, repeat-row counts, independent config runs, rejected/missing data, duplicate/concurrent writers and rollback.
6. Provide a separate persisted replay command through Spring; keep offline replay available. Default build stays free of live API calls.

Schema: new tables only, no existing data rewritten. Precision/slot/identity limits are explicit; unrepresentable values must fail before persistence rather than silently round in PostgreSQL. Time metadata uses an injected UTC Clock and is excluded from deterministic result identity.

Completed 2026-10-01. Validation and implementation evidence are recorded in docs/IMPLEMENTATION_STATUS.md. Live wallet capture and protocol decoding remain separate, dependent on public wallet/protocol/provider decisions.
