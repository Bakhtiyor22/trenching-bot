# Errors and Logging

## Expected outcomes vs errors

- **Expected domain outcomes are values**: an ignored wallet event, a rejected fill, a missing or
  stale price, a no-op duplicate. Model them as sealed results (`trading-domain.md`). Never use an
  exception for control flow, and never return `null` to mean "error".
- **Exceptions are for broken invariants and infrastructure failure**: an illegal state transition
  requested by code, a malformed vendor payload, an unavailable provider, a constraint violation.

## Exception hierarchy

One unchecked base exception per module, one subclass per error case, message carrying the ids and
values needed to debug it:

```kotlin
sealed class PortfolioException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class PositionNotFoundException(positionId: PositionId) :
    PortfolioException("position not found positionId=$positionId")

class IllegalPositionTransitionException(positionId: PositionId, from: PositionState, to: PositionState) :
    PortfolioException("illegal transition positionId=$positionId from=$from to=$to")
```

- Name `*Exception`; live in the module that owns the error.
- Take a cause when wrapping, and pass it on — never drop it.
- Adapter-boundary infrastructure failures use a small taxonomy per integration: authentication,
  rate limited, timeout, malformed payload, unavailable. The adapter translates vendor/HTTP
  exceptions into these; nothing vendor-specific escapes the adapter.

## Rules

1. **Never swallow.** No empty `catch`, no catch-log-and-continue with inconsistent state.
2. **Never downgrade** a meaningful exception into a generic one; wrap only the genuinely unknown,
   preserving the cause.
3. **`catch (e: Throwable)` is a BLOCKER.** Catch `Exception`, and only where you can act.
4. **Catch where you can decide**, log once there, not at every layer.
5. **Batch/scheduled work is exception-safe per item**: one bad event is logged with its id and
   recorded as an ingestion outcome; it does not abort the sweep.
6. **Retries only for transient, idempotent reads** — capped exponential backoff with jitter. Never
   retry a validation or permanent parsing failure.
7. **No `!!`.** Throw a typed exception with a real message instead.
8. **Do not leak internals** (SQL, stack traces, URLs with keys) into anything user-facing — a
   Telegram message or an HTTP response.

If an HTTP API is added, map module exceptions to statuses in one `@RestControllerAdvice` with one
error envelope; do not let an unmapped exception become a misleading status.

## Logging

One logger per class, created explicitly (Kotlin has no `@Slf4j`):

```kotlin
private val log = LoggerFactory.getLogger(PaperExecutionService::class.java)
```

- **Parameterized** messages, never concatenation or string templates:
  `log.info("fill rejected intentId={} targetSlot={} reason={}", intentId, targetSlot, reason)`.
- **Structured `key=value` fields** with the correlation ids this system uses: `runId`,
  `sourceEventId`, `signature`, `slot`, `strategyVersion`, `intentId`, `orderId`, `positionId`,
  `mint`.
- Levels:
  - `ERROR` — unexpected failure or exhausted retries; **the exception is the last argument** so the
    stack trace is captured.
  - `WARN` — expected but abnormal: a retry, a gap, stale/missing data, an unconfigured optional
    feature skipped, a business-rule miss.
  - `INFO` — lifecycle transitions (event accepted, intent created, position opened/closed, run
    finished) and summarized adapter health.
  - `DEBUG` — sanitized mapping detail.
- **Never log** secrets, tokens, API keys, RPC URLs containing keys, `Authorization` headers, or
  entire unbounded vendor payloads. Wallet addresses and signatures are public — log them when
  operationally useful.
- **No logging in thin adapters** — the service logs the business event.
