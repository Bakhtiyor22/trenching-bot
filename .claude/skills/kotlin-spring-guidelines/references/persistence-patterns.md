# Persistence Patterns

PostgreSQL 17, Spring Data JPA, Flyway. **Flyway owns the schema; Hibernate only validates it**
(`ddl-auto: validate` in every profile).

## Migrations

Location: `src/main/resources/db/migration`.

- Name: `V<yyyyMMddHHmmss>__<lower_snake_description>.sql`, UTC timestamp from
  `date -u +"%Y%m%d%H%M%S"`. Example: `V20260923105308__telegram_chats.sql`.
- One migration per logical change; its module in the description when useful
  (`__marketdata_observations.sql`).
- **Never edit a migration that may have been applied.** Add a new one.
- Tables, columns, constraints and indexes are `snake_case`. Name constraints explicitly
  (`uq_paper_fills_order_id`, `fk_positions_fill_id`, `idx_market_observations_mint_slot`) so an
  error message tells you what was violated.
- Primary keys and every idempotency key are explicit. **External identity gets a unique
  constraint**, not just an application check.
- Timestamps are `timestamptz`; slots are `bigint`; raw token amounts `numeric(20,0)`; decimals
  `numeric(p,s)` with the scale the domain uses.
- A module owns its tables. Another module does not add columns to them or query them directly.

What needs the user's approval first (an "always ask"):

| Change | Why |
|---|---|
| Drop a table or column | Data loss |
| Rename a table or column | Breaks the running version and every query |
| Narrow a type or length, change a type | Can fail on existing data |
| Add `NOT NULL` to a populated table without a default | Fails the migration |
| Backfill or rewrite rows | Data migration — needs a rollback note |
| Add a unique constraint to a populated table | Fails on existing duplicates |

Every migration must apply cleanly to an **empty** PostgreSQL 17 — the Testcontainers integration
tests prove this on every `./gradlew test`.

## Entities

```kotlin
@Entity
@Table(
    name = "telegram_chats",
    uniqueConstraints = [UniqueConstraint(name = "uq_telegram_chats_chat_id", columnNames = ["chat_id"])],
)
class TelegramChat(

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Column(name = "title", length = 255)
    var title: String?,

    @Column(name = "allowed", nullable = false)
    var allowed: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

Rules (from `CLAUDE.md`, expanded):

- **Plain `class`, never `data class`.** Generated `equals`/`hashCode`/`toString`/`copy` walk lazy
  proxies and mutable collections.
- `var` for what changes, `val` for what never does after insert.
- **No business logic** — no validation methods, no service calls, no mapping functions.
- `@Table(name = ...)` and `@Column(name = ...)` explicit; `nullable`, `length`, `precision`,
  `scale` explicit and **identical to the migration** — `validate` fails startup otherwise.
- `@Enumerated(EnumType.STRING)` always, with a `length` that fits the longest constant.
- Money/price columns: `@Column(precision = ..., scale = ...)` matching the migration's `numeric`.
- Entities are internal to their module and live in its `model/` (or `infrastructure/persistence/`)
  package. Map them to domain/DTO types before they leave the service.

### Associations

- **`FetchType.LAZY` on every `@ManyToOne` and `@OneToOne`** — the JPA default is EAGER.
- `@OneToMany` stays lazy. Initialize collections: `var fills: MutableList<PaperFill> = mutableListOf()`.
- `cascade = [CascadeType.ALL], orphanRemoval = true` only for children whose lifecycle belongs to
  the parent.
- Prefer referencing another module's aggregate **by id** (a `Long`/value-type column), not a JPA
  association — an association across modules couples their persistence.
- Anything always needed with its parent: `JOIN FETCH` or `@EntityGraph`. Never touch a lazy
  collection inside a loop.
- `open-in-view` is `false`; a lazy association touched outside the transaction throws
  `LazyInitializationException`. Load what the mapper needs inside the service.

### Concurrency

Rows that can be updated concurrently (positions, orders) carry `@Version var version: Long = 0`
with a matching `bigint not null default 0` column, or are protected by a unique constraint that
makes the second writer fail cleanly.

## Repositories

```kotlin
interface TelegramChatRepository : JpaRepository<TelegramChat, Long> {

    fun findByChatId(chatId: Long): TelegramChat?

    fun findAllByAllowedTrue(): List<TelegramChat>
}
```

- Interface extending `JpaRepository<Entity, Long>`, suffix `Repository`, one per aggregate.
- **Query only** — no business logic.
- **Return `T?`, not `Optional<T>`.** A lookup that can miss is nullable.
- Derived queries while readable (roughly ≤ 3 conditions); JPQL `@Query` with named `@Param`s beyond
  that or for fetch joins; native SQL only for genuine PostgreSQL needs.
- Name fetch-join queries `...With<Association>` so callers know what is loaded.
- A `@Modifying` query is the one place `@Transactional` may sit on a repository method.
- **Bound every query on a growing table** (events, observations, fills): `Pageable`, a slot range,
  or a `LIMIT`.
- Batch: `findAllBy...In(ids)` then `associateBy`, `saveAll(...)` — never a query or `save()` per
  element in a loop.

## Transactions

- `@Transactional` on service functions that write more than once or read-then-write;
  `@Transactional(readOnly = true)` on reads that need lazy data.
- **Never on an adapter or a repository** (except bulk `@Modifying`).
- `plugin.spring` opens Spring beans, but a `private` `@Transactional` function or a self-invocation
  inside the same bean still gets **no** transaction. Move the boundary to another bean.
- **No remote call inside a transaction** — no RPC, market-data, or Telegram request while a
  connection is held. Fetch first, then open a short transaction for the state change.
- Keep transactions short; rely on unique constraints to make retries safe.

## Testing persistence

Repository and migration behaviour is tested against real PostgreSQL through
`support/PostgresTestContainerConfiguration` — see `testing-and-build.md`. Test the unique
constraints by deliberately inserting a duplicate.
