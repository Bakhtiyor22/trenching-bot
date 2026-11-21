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
  `log.info("Fill rejected: no price observation in the window. intentId={} targetSlot={} code={}", intentId, targetSlot, code)`.
- **Correlation ids as `key=value` fields**: `runId`, `sourceEventId`, `signature`, `slot`, `strategyVersion`,
  `intentId`, `orderId`, `positionId`, `mint`, and the console's `itemId`, `sessionId`, `captureSessionId`,
  `backfillId`, `operationId`, `datasetId`, `wallet`.
- **Levels** are defined in the table under "Log message style" below. In short: `ERROR` is a bug or unexpected
  failure (the exception is the **last argument** so the stack trace is captured); `WARN` is work the bot cannot do or
  lost and that needs a person or time; `INFO` is a business event; `DEBUG` is everything that can repeat.
- **Never log** secrets, tokens, API keys, RPC URLs containing keys, `Authorization` headers, or
  entire unbounded vendor payloads. Wallet addresses and signatures are public — log them when
  operationally useful.
- **No logging in thin adapters** — the service logs the business event.

### Log message style

Every line written by `com.trenching.bot` code reads like this, so a person scanning the journal or Grafana Loki can
follow the bot without knowing the code.

1. **A plain sentence, then `key=value` context.** The sentence says what happened and what it means or what happens
   next, in words a non-author knows (buy, token, wallet, capture, market, request cap, backfill, session, dataset,
   sweep). Sentence case, ending with a full stop. Internal type and field names (manifest, watermark, coverage,
   selection) stay out of it. Keep the message format parameterized: no string templates.
2. **Keep the key names** in the list above; new keys are camelCase. Never rename one to a shorter form: the README,
   the console and grep habits quote them. Values never hold spaces.
3. **Omit what is absent.** An optional value that is not there is left out, never printed as `=null`. Use a second
   message variant or a conditional format, not a `null` argument.
4. **Instants are ISO-8601 UTC** (`Instant.toString`). **Durations are compact** through `LogDurations.compact`
   (`2d10h31m`, `35m`, `45s`). Attempts are `attempt=2/2`.
5. **Codes appear as `code=`** and are also explained in the sentence. Never log a provider's message or a URL
   (see **Never log** below); log the exception class or a fixed meaning instead.
6. **Log once per layer that adds meaning.** The layer above logs the consequence for its own object, not the cause
   again: a capture module's per-operation line is `DEBUG`, the capture console logs one line when an operation
   stops short, and the automation logs what that means for the buy.

| Level | In this project | Examples |
|---|---|---|
| `ERROR` | A bug or an unexpected failure, with the stack trace. Someone should look. | An unhandled exception in a tick or request |
| `WARN` | The bot cannot do part of its job, or lost or refused work, and it needs a person or time to clear. Logged when the state starts, not on every tick. | A buy failed for good, a step is retried, a hold started, a provider still failing after its retries, data skipped |
| `INFO` | A business event, about three per buy, and what a person did in the console. | Buy found, captured, simulated; session opened or closed; run started; dataset registered or deleted; hourly summary; startup settings |
| `DEBUG` | Steps, pages, windows, retries, cache events, bookkeeping, resolved races, scans that found nothing. | A retry, a page read, a session merge, a duplicate writer that lost the race |

**Volume rule.** A line that can fire once per page, request, window, retry or tick in normal running is `DEBUG`. If its
count matters, a once-per-operation or once-per-hour line carries the count. With nothing happening, `prod` logs nothing
at `INFO` except the hourly summary. `local` logs `DEBUG`; on the server one package can be turned up without a code
change (README, Logs).

```kotlin
log.warn("Buy failed at the {} step and will not be retried: {}. code={} itemId={} mint={}", step, meaning, code, itemId, mint)
log.info("Dataset deleted. datasetId={}", id)
```
