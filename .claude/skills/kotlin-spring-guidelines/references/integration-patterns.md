# Integration Patterns

Outbound dependencies: Solana RPC / streaming providers, market-data vendors, the Telegram Bot API,
and scheduled work. These are the rules every adapter follows.

Provider choice (RPC vendor, streaming vs WebSocket, market-data source) is an open decision —
check `docs/adr/` and `docs/WEEKLY_EXECUTION_PLAN.md` §5 before building a live adapter, and do not
merge a production adapter before the provider decision is recorded.

## General rules for every adapter

1. **Behind a port.** The adapter implements a module port and returns domain types. Vendor DTOs,
   HTTP clients and vendor exceptions stay inside the adapter's package.
2. **A fixture/in-memory adapter exists for every data port**, and the default test suite uses it.
3. **Configuration from a validated `@ConfigurationProperties` class**: base URL, timeouts,
   commitment, rate limits. **Credentials from environment variables only.**
4. **Explicit timeouts** on every call. No unbounded waits.
5. **Retries**: transient, idempotent reads only, capped exponential backoff with jitter, and a
   `WARN` per retry with the operation and attempt number. Respect `429`/`Retry-After`.
6. **Translate failures** into the module's infrastructure exception taxonomy (authentication, rate
   limited, timeout, malformed payload, unavailable).
7. **Never call a provider inside a database transaction.**
8. **Log without secrets.** Never log a URL that embeds an API key; log the host or an alias.
9. **Optional integrations degrade**: unconfigured → do not start, `WARN` and skip. Required
   integrations for the active mode fail fast at startup.

## HTTP

Use Spring's `RestClient` (already on the classpath via `spring-boot-starter-web`), built once per
adapter from a configured `RestClient.Builder` with base URL and timeouts. Do not add another HTTP
library without asking.

## Solana RPC

**Read-only.** Allowed methods are reads: `getTransaction`, `getSignaturesForAddress`,
`getAccountInfo`, `getTokenSupply`, `getSlot`, and subscriptions such as `logsSubscribe`,
`slotSubscribe`. Never `sendTransaction`, `simulateTransaction` with signers, or `requestAirdrop`.

- **Commitment** is configuration, recorded on every observed event.
- **Deduplicate** by signature (+ instruction index). Live delivery is at-least-once at best.
- **Checkpoint** the last processed slot/signature and backfill gaps with `getSignaturesForAddress`
  + `getTransaction` after a restart or reconnect.
- **Raw amounts** from token balances are `u64` strings — parse to `BigInteger`, apply the mint's
  decimals from the mint account, never via `Double`.
- Failed transactions and unrecognized instructions are retained as ingestion outcomes that create
  no signal — not dropped silently, not turned into trades.
- WebSocket subscriptions reconnect with backoff and resubscribe; a reconnect triggers gap recovery.
  A WebSocket client is not on the classpath yet — adding one is a dependency decision.

## Market data

- Observations are **slot-aware**: slot, time, price, quote currency, liquidity if available,
  source, source event id, quality flags.
- Every observation used for a fill or an exit is **persisted**, so the result can be replayed.
- A current-price endpoint is valuation, not proof of the price at a target slot — never use it to
  fill a slot-offset entry.
- Missing or stale data is an explicit outcome, never a fallback.

## Telegram Bot API

Send-only, to one configured chat (`docs/adr/20261122-outbox-notifications-over-telegram.md`). The bot never calls
`getUpdates`; there is no chat registry. The adapter is `notification.infrastructure.telegram.TelegramNotificationChannel`;
the top-level `telegram` module stays unbuilt until an inbound bot is wanted.

- `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` from the environment. Both unset: notifications are off (the publisher is a
  no-op, nothing is stored or sent, one startup `INFO` says so). One without the other fails startup naming the property.
- Callers never call Telegram. They publish through `NotificationPublisher`, which writes a `notification_outbox` row in
  their own transaction; the dispatcher sends later, outside any transaction, and stores the result in a short one.
- The token is part of the Bot API URL — **never log or store the URL, the token, or an exception message** (transport
  errors quote the URL). Log a category and the exception class only; `last_error` holds a category.
- A channel answers with a sealed `DeliveryResult` (`Delivered`, `RetryLater`, `Rejected`), never an exception: 429 and 5xx
  and no answer retry (a 429 waits its `retry_after`), 400, 401 and 403 are final.
- Messages are HTML: escape the text, cut at 4,096 characters on a line boundary. Count every request as a `TELEGRAM` call.
- Tests use a JDK `HttpServer` fake or an in-memory `NotificationChannel`, never the real API; a real test message needs the
  user's approval.

## Scheduled work

- `@Scheduled` methods are thin triggers calling a service.
- Intervals and cron expressions come from configuration; set `zone = "UTC"` on crons.
- Every run is exception-safe per item and idempotent — the app may restart mid-run.
- A single instance is assumed for now; a job that would misbehave with two instances must say so
  in its plan.

## Health

Actuator `/actuator/health` must stay dependency-tolerant: an optional provider being down must not
make the application report `DOWN`. If a provider health indicator is added, put it in a separate
health group rather than the liveness/readiness probes.
