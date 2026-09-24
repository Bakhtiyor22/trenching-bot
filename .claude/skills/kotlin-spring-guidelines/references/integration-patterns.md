# Integration Patterns

Outbound dependencies: Solana RPC / streaming providers, market-data vendors, the Telegram Bot API,
and scheduled work. None of them is implemented yet beyond the README's Telegram description; these
are the rules the first implementations must follow.

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

The `telegram` module (described in the README) long-polls `getUpdates`, records chats in
`telegram_chats` with `allowed = false` by default, and broadcasts alerts only to chats with
`allowed = true`.

- Token from `TELEGRAM_BOT_TOKEN`; with no token the listener does not start and sending is a
  `WARN` + skip.
- The token is part of the Bot API URL — **never log the request URL**.
- Never promote a chat to `allowed` in code; that stays an explicit operator action until an admin
  flow is agreed.
- Update handlers are thin adapters; chat registration and alert fan-out live in services.
- One failed chat does not stop delivery to the others; each failure is logged with the chat id.
- Tests use a fake HTTP server or a fake `AlertSender`, never the real API.

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
