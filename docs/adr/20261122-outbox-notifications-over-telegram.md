# Outbox notifications over a send-only Telegram channel

Accepted 2026-11-22 with the approved [Telegram alerts plan](../plans/20261122-telegram-alerts-and-backup-rpc-account.md) (Key Decisions 1, 2 and 5; Domain Rules 1, 2 and 7–10). This record covers the `notification` module; the messages publishers build and the RPC account failover have their own parts and record.

## Context

A buy's runs, a scored day and a provider problem were visible only in the console, through an SSH tunnel; the server fell three days behind and nobody could tell. The bot should tell one chat on Telegram.

What a message must not do:

- Report a change that was rolled back, or hold a database connection while it waits for Telegram.
- Fail or roll back the change it reports because Telegram is down, slow or misconfigured.
- Leak the bot token, which Telegram makes part of the request URL.
- Change a simulation result. A notification observes stored results.

## Decision

1. **Outbox, then send.** A publisher inserts a row into `notification_outbox` in its own database transaction; a dispatcher sends pending rows later. The insert is `insert ... on conflict do nothing` with no conflict target, so a repeat stores nothing and raises nothing, and, unlike catching a unique-constraint violation, it never marks the caller's transaction rollback-only. The target is left out on purpose: the id is the SHA-256 of the key, so two transactions publishing one key at once can meet on the primary key as well as on the unique key, and both must be skipped. The unique key `uq_notification_outbox_key` is the idempotency guarantee, not the application check.
2. **At-least-once delivery.** A crash between Telegram's answer and the stored `SENT` repeats one message. Exactly-once would need Telegram to deduplicate, which it does not. One message twice is accepted over one message lost. The dispatcher claims a row before sending: a short transaction counts the attempt and moves `next_attempt_at` to the backoff time, then the message is sent, then the outcome is stored. A claim that fails sends nothing; an outcome that cannot be stored repeats the message after its backoff, at most `max-attempts` times, so a database fault never sends one message on every run.
3. **Send-only Telegram, one chat from configuration.** `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are environment variables; the bot never calls `getUpdates`, so there is no chat registry. The earlier long-polling, `telegram_chats` design in the integration patterns is replaced. The adapter is `notification.infrastructure.telegram`; the top-level `telegram` module stays unbuilt until an inbound bot is wanted.
4. **Off unless fully configured.** No token and no chat id: the publisher is a no-op, nothing is stored, the dispatcher and its scheduling are not created, one startup `INFO` says so. One of the two alone fails startup naming the property. Scheduling is enabled by the notification configuration itself, because the automation's `@EnableScheduling` exists only when the automation does.
5. **Delivery rules.** Oldest first, at most 20 a run, one second apart. A 429 waits its `retry_after` (capped at 24 hours, so an absurd value cannot overflow a timestamp), a 5xx or no answer waits a doubling backoff from 30 seconds up to 30 minutes, and either ends the run so an outage never holds the single scheduling thread. After a 429 the dispatcher keeps one in-memory "not before" instant and sends nothing to Telegram until it passes. After `max-attempts` (54, about 24 hours of retries at the 30-minute cap, so an outage delays messages instead of losing them) the row is `FAILED`; a 400, 401 or 403 is `FAILED` at once. Both log one `WARN`. Any other 4xx is a rejection too, a status outside 2xx, 4xx and 5xx a retry.
6. **The token never leaves the adapter.** The URL is built once and neither it, the token nor an exception message is logged or stored: a failure is a category (`last_error`, the log) plus the exception class, since transport errors quote the URL. The token must match `[A-Za-z0-9:_-]+`, so a malformed one fails startup without printing it, and `api-base-url` is parsed and checked alone (https, or http only for the exact host `localhost`, `127.0.0.1` or `::1`, no user info) before the token is joined to it, so a bad address cannot quote the token either. The properties class is not a data class, so it cannot print the token.
7. **A message over 4,096 characters** keeps the lines that fit whole, then the first link, and ends "… more in the console". The first link is the subject's main page, so it survives the cut.
8. **Links.** A publisher gives console paths; the address comes from `trenching.notification.console-base-url` (default `http://localhost:8081`, the tunnel). Whether Telegram makes a `localhost` link tappable is not documented, so `trenching.notification.telegram.link-style` is `ANCHOR` (every link an HTML anchor, the default) or `PLAIN` (only the first link, as a plain URL on its own line); the plan's Open Question 1 settles which one the operator keeps after one test message.
9. **Requests are counted** as `ApiProvider.TELEGRAM` `sendMessage` calls under `ApiTask.NOTIFICATION`, so they show in the 24-hour total.

## Alternatives

- **Send inside the publishing transaction.** Rejected: a remote call inside a transaction, a rolled-back change would still notify, and a Telegram outage would fail the change.
- **Send after commit from the publisher** (a transaction callback). Rejected: a crash after the commit and before the send loses the message, and the retry state would live only in memory.
- **Catch the unique violation instead of `on conflict do nothing`.** Rejected: in PostgreSQL a failed statement aborts the surrounding transaction, so the caller's change would be lost.
- **A chat registry with long polling.** Rejected for now: one chat, no commands; it adds an inbound surface and a table for no use.
- **Log the URL with the token masked.** Rejected: masking is a rule a later edit can break; logging nothing of the URL cannot leak.

## Consequences

- One additive table, `notification_outbox`; under `ddl-auto: update` nothing is applied by hand.
- Sent and failed rows older than `retention` (30 days) are deleted in batches of 500 at most once an hour (a pending row is kept however old), so a key published again after that could notify again; the keys name events that do not recur after a month.
- A single application instance is assumed; two dispatchers would send twice.
- A burst of 20 messages holds the scheduling thread for about 20 seconds (the pauses); it is bounded, and an outage ends the run at the first failure.
- Telegram's per-chat limit (about one message a second) is respected by the pause, not by a token bucket.
