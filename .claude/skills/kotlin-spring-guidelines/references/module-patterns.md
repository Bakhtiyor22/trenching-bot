# Module Patterns

## The shape of a module

A module is a top-level package under `com.trenching.bot`. It starts flat and grows sub-packages
only when it needs them:

```
com.trenching.bot.marketdata
├── MarketDataSource.kt          public port (interface) — the module's contract
├── MarketObservation.kt         public value type
├── model/                       JPA entities (internal)
├── repository/                  Spring Data repositories (internal)
├── mapper/                      entity ↔ DTO/domain mapping
├── fixture/                     FixtureMarketDataSource — in-memory/replay adapter
└── <vendor>/                    live adapter + its vendor DTOs, e.g. birdeye/, helius/
```

- **Small module:** flat package, a few files. Do not create empty layer packages up front.
- **Growing module:** add `model/`, `repository/`, `mapper/`, `dto/` sub-packages as `CLAUDE.md`
  describes.
- **A module that has already adopted a layout** (for example `domain/`, `application/`,
  `infrastructure/` as sketched in `docs/WEEKLY_EXECUTION_PLAN.md`) — match it. Consistency inside
  a module beats either layout in the abstract.
- No cross-cutting `service/`, `util/`, `model/` or `repository/` package at the root.

## Public contract vs internals

Kotlin has no package-private, so the boundary is enforced by convention and review:

- **Public contract**: ports, commands, results and value types that other modules use. Keep it
  small, vendor-free, Spring-free and JPA-free.
- **Internals**: entities, repositories, mappers, vendor DTOs, adapters, service implementation
  details. Mark them `internal` where the Kotlin module boundary allows it; otherwise, simply
  never import them from another module.
- Another module needing something internal is a signal to widen the contract deliberately (an
  "always ask" if it changes a shared contract), not to import the internal type.

## Ports and adapters

A **port** is an interface named for the capability (`WalletActivitySource`, `MarketDataSource`,
`PositionRepository`, `AlertSender`). An **adapter** implements it and is named for the technology
or vendor (`FixtureMarketDataSource`, `HeliusWalletActivitySource`, `TelegramAlertSender`).

- Add a port when a boundary needs it: a second implementation exists (fixture + live), or another
  module must depend on the capability without depending on the implementation. Otherwise a
  concrete class is enough.
- **Fixture/in-memory adapters are first-class.** Every data port gets one so the rest of the flow
  can be built and tested without a provider.
- A port returns domain types, never a vendor DTO, a JPA entity or a Spring type.

## Adapters in the transport sense

Controllers, Telegram update handlers, `@Scheduled` triggers and replay/CLI entry points are thin:

```kotlin
@Component
class TelegramUpdateHandler(
    private val telegramChatService: TelegramChatService,
) {
    fun handle(update: TelegramUpdate) {
        val chat = update.message?.chat ?: return
        telegramChatService.registerChat(chat.id, chat.title ?: chat.username)
    }
}
```

- Parse input → call one service → map the response. Nothing else.
- No business logic, no repository access, no `@Transactional`, **no logging** — log business events
  in the service.
- A scheduler orchestrates only; the work lives in a service.

## Services

```kotlin
@Service
class PaperExecutionService(
    private val marketDataSource: MarketDataSource,
    private val fillModel: FillModel,
    private val paperOrderRepository: PaperOrderRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PaperExecutionService::class.java)
}
```

- Concrete `@Service` classes; primary-constructor `private val` injection.
- A function has one job — nameable in one sentence without "and".
- **A service file contains only the service class.** DTOs go in `dto/`, mappings in `mapper/`,
  even for a single caller.
- Services return DTOs, domain types, or `Unit` — never entities across the module boundary.
- Declare explicit return types on public functions.
- Guard clauses first, then load, then rules, then mutate/save, then log, then map and return.

```kotlin
val intent = tradeIntentRepository.findByIntentId(intentId)
    ?: throw TradeIntentNotFoundException(intentId)

require(intent.notional.signum() > 0) { "notional must be positive intentId=$intentId" }
```

## Pure domain logic

The core of this system — normalization, strategy decisions, target-slot calculation, fill
selection, fees/slippage, position transitions, P&L — should be **plain Kotlin** with no Spring, JPA
or JSON imports, so it can be unit-tested without a context. Services wire it to persistence and
ports; they do not re-implement it.

## Configuration

- One validated `@ConfigurationProperties` class per module, prefix named after the module
  (`trenching.marketdata`, `trenching.telegram`, `trenching.strategy`). No scattered `@Value`.
- Enable it with `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties` in `config/`.
- Validate with Jakarta constraints (`@field:NotBlank`, `@field:Positive`) and `@Validated`.
- Strategy configuration is separate from infrastructure configuration; a simulation run persists a
  normalized snapshot/hash of the strategy configuration it used.
- **Optional integrations degrade explicitly.** A module whose credentials are not configured does
  not start its listener, and its operations log a `WARN` and skip rather than fail — the pattern
  the README describes for Telegram. Required settings for the core flow fail fast instead.

## Constants

A literal used more than twice goes into an `object` named for what it groups:

```kotlin
object TelegramApi {
    const val BASE_URL = "https://api.telegram.org"
    const val GET_UPDATES = "getUpdates"
    const val SEND_MESSAGE = "sendMessage"
}
```

Keep constants in the module that owns them; move to `common` only when two modules genuinely share
the meaning.
