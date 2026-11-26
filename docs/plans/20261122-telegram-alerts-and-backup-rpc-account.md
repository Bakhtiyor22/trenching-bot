# Telegram alerts, a backup RPC account with balances, and the 7–12 Nov backlog

> The bot tells you on Telegram how each buy's runs ended and when a day is scored; it moves to a second Helius account
> when the first runs out and shows both balances; and the buys stuck since 7–12 Nov are closed so their days complete.

Status: Approved (2026-11-22)

## Context

Four things came out of the 20–22 Nov investigation (the server fell three days behind and nobody could tell):

- **Nothing reports results.** A buy's runs, a day's verdict and a provider problem are visible only in the console,
  through an SSH tunnel. `notification/` is an empty package; `ApiProvider.TELEGRAM` is reserved and records nothing.
- **One RPC account, no balance.** Every capture reads `trenching.solana.rpc-url` (`SOLANA_RPC_URL`,
  `console/config/SolanaRpcProperties.kt`) through `chaincapture/HttpSolanaReadRpc.kt`, built in three places by
  `CaptureRpcs.create` (wallet capture, market capture, wallet activity) plus the Solana probe. The Helius free plan gives
  1,000,000 credits a month at 1 credit per `getTransaction` / `getSignaturesForAddress`; the server spent about 100,000 a
  day. When the credits end, Helius answers HTTP 429 "max usage reached", which the code treats as an ordinary rate limit:
  it retries, fails the step, and after `max-step-attempts` fails the buy. The user has a second Helius account.
- **217 buys from 7–12 Nov never finish** (count of 21 Nov). They were discovered while capture was off, their sessions
  are `CLOSED`, and they queue behind live buys. Their days stay incomplete, so every day whose 7-day window reaches them
  stays `PENDING`.
- **Grafana (runbook step 14) is not set up yet**, and its alert table has no rule for capture being held for hours.

Decisions taken with the user on 2026-11-22: one Telegram message per buy; the chat id comes from server settings; balances
come from Helius; the bot also reports account switches, low balances and day verdicts. Server health stays in Grafana.

## Scope

### In scope

- **A. Operations, no code:** close the stuck 7–12 Nov buys; set the daily request cap for two accounts; finish Grafana
  step 14 and add one alert rule.
- **B. Notifications:** an outbox in `notification`, a send-only Telegram channel, a message per simulated buy and per
  scored day.
- **C. RPC accounts:** primary and backup endpoint with automatic failover and return, balances read from the Helius
  Admin API, a hold instead of failed buys when no account has credits, messages for switches and low balances, and one
  read endpoint for the console.
- **D. Console UI** for the accounts (frontend, its own session), on contract C.

### Out of scope

- Telegram commands or any inbound message; several chats; other channels.
- Messages for manual runs started from the New run page, and for backfilled buys.
- Pacing the daily cap from the balances automatically; counting requests per account in `api_usage_buckets`.
- More than two accounts, or accounts of another provider with a different usage API.
- The 100-buys-per-tick batch limit (`AutomationPipelineTransaction.MAX_ITEMS_PER_TICK`): unchanged, to be revisited only
  if new buys are still skipped once the backlog is gone.

## Key Decisions

1. **Outbox, then send.** A publisher writes a row to `notification_outbox` in its own database transaction; a
   dispatcher sends pending rows later. A rolled-back change never notifies, no provider is called inside a transaction,
   and a Telegram outage delays messages instead of losing them. Delivery is at-least-once: a crash between Telegram's
   answer and the row update can repeat one message.
2. **Send-only Telegram, chat id from configuration.** `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are environment
   variables; the bot never calls `getUpdates`. This replaces the chat-registry design described in
   `.claude/skills/kotlin-spring-guidelines/references/integration-patterns.md` (long polling, `telegram_chats`), which
   is updated with this plan. The top-level `telegram` module stays unbuilt until an inbound bot is wanted; the adapter
   lives in `notification.infrastructure.telegram`.
3. **One message per buy.** When a live buy becomes `SIMULATED`, one message is published if at least one variation's
   position closed with a profit or a loss. It lists every variation's result. Backfilled buys send nothing (a month of
   backfill would send hundreds); their effect shows in day verdicts.
4. **One message per scored day.** Verdicts are derived on read and stored nowhere (`AutomationDayService`), so an hourly
   check publishes a day the first time all its rows are decided.
5. **Links point at a configurable console address**, default `http://localhost:8081` (the tunnel). Whether Telegram
   makes a localhost link tappable is not documented; see Open Question 1.
6. **Accounts are an ordered pair, primary then backup; the active one is the first that has credits.** An account is
   marked out of credits by either of two signals: a 429 whose body says "max usage reached" (Helius FAQ), or a balance
   read of zero. It returns when a later balance read shows credits again, so the primary takes over again after its
   cycle resets.
7. **Balances come from the Helius Admin API** (`GET https://admin-api.helius.xyz/v0/admin/projects/{id}/usage`, header
   `X-Api-Key`). The API key is the `api-key` query parameter of that account's RPC URL, so the only new settings are the
   two project ids. Rejected: counting our own requests per account against a typed-in allowance. It misses use outside
   the bot and would change the unique key of `api_usage_buckets`, which `ddl-auto: update` cannot do.
8. **No credits anywhere is a hold, not a failure.** With every account out, wallet and market operations are refused
   before they start and buys wait with no attempt charged, as they do for `NOT_CONFIGURED`.
9. **The cap stays global.** `max-requests-per-24h` keeps counting every provider request; accounts change where requests
   go, not how many are allowed.

## Module Contracts

### `notification` (new public contract, package `com.trenching.bot.notification`)

- `fun interface NotificationPublisher { fun publish(notification: OutboundNotification) }`. Joins the caller's
  transaction; idempotent on `key`. When Telegram is not configured the bean is a no-op and nothing is stored.
- `data class OutboundNotification(key: String, kind: NotificationKind, title: String, lines: List<String>, links: List<NotificationLink>)`
- `data class NotificationLink(label: String, path: String)`; `path` is a console path starting with `/`.
- `enum class NotificationKind { BUY_RESULT, DAY_VERDICT, RPC_ACCOUNT_SWITCHED, RPC_BALANCE_LOW }`
- Internal port `NotificationChannel.send(RenderedNotification): DeliveryResult`, a sealed result: `Delivered`,
  `RetryLater(category, after: Duration?)`, `Rejected(category)`. Adapters: `TelegramNotificationChannel` and an
  in-memory fake for tests.

Publishers build plain text; `notification` escapes it for the channel and prefixes link paths with the console address.
`console` depends on `notification`; `notification` depends only on `common`, `config` and `apiusage`.

### `chaincapture` (additions; nothing existing changes meaning)

- `interface RpcEndpointSource { fun choose(): RpcEndpointChoice; fun exhausted(account: String) }` with
  `RpcEndpointSource.fixed(endpoint: String?)` for the CLI commands and tests.
- `sealed interface RpcEndpointChoice`: `Use(target: RpcEndpointTarget)`, `NotConfigured`, `OutOfCredits`.
- `class RpcEndpointTarget(account: String, endpoint: String)`: not a data class, and `toString` prints the account only,
  since the endpoint embeds an API key.
- `CaptureRpcs.create(..., endpoints: RpcEndpointSource = RpcEndpointSource.fixed(settings.endpoint))`.
- `CaptureCategory.CREDITS_EXHAUSTED`: a new provider category.
- `HttpSolanaReadRpc` asks `choose()` before every attempt, uses that target's limiter and credential check, and calls
  `exhausted(account)` on the "max usage reached" answer; the next attempt of the same call goes to the next account.

### `console.solana.accounts` (new sub-package, owner of account state)

- `RpcAccountDirectory : RpcEndpointSource`: the in-memory state every RPC client shares, loaded from `rpc_accounts`.
- Port `RpcAccountBalances.read(account): BalanceRead`, sealed: `Read(RpcAccountBalance)`, `Unavailable(problem)`.
  Adapters: `HeliusUsageClient` (`infrastructure.helius`) and a fixture.
- `data class RpcAccountBalance(creditsRemaining: Long, creditsUsed: Long, creditsLimit: Long, cycleEndsOn: LocalDate, plan: String)`
- `GET /api/solana/accounts` answers `{ active: String?, accounts: [RpcAccountView] }`, each with `name`
  (`primary`/`backup`), `configured`, `state` (`ACTIVE`, `STANDBY`, `OUT_OF_CREDITS`, `NOT_CONFIGURED`), `host`,
  `creditsRemaining`, `creditsUsed`, `creditsLimit`, `cycleEndsOn`, `plan`, `balanceCheckedAt`, `balanceProblem`,
  `outOfCreditsSince`. Never a URL, key or project id.

### Changed behaviour of existing contracts

- **Capture console:** a `WALLET` or `MARKET` operation start is refused `409 RPC_CREDITS_EXHAUSTED` while no account has
  credits. A new refusal code for API clients; nothing existing changes.
- **Automation:** new `AutomationHoldKind.RPC_CREDITS`; the pipeline treats `RPC_CREDITS_EXHAUSTED` like `NOT_CONFIGURED`.
- **`apiusage`:** `ApiProvider.HELIUS_ADMIN`, `ApiTask.ACCOUNT_BALANCE` and `ApiTask.NOTIFICATION` are added;
  `ApiProvider.TELEGRAM` starts recording. These requests count in the 24-hour total (about 250 a day).

No replay fixture, simulation or dataset contract changes.

## Domain Rules

**Notifications**

1. `publish` is `insert ... on conflict (notification_key) do nothing`: a repeat stores nothing, raises nothing and
   never rolls back the caller's transaction.
2. Keys: `BUY_RESULT|<itemId>`, `DAY_VERDICT|<planId>|<day>`, `RPC_ACCOUNT_SWITCHED|<from>|<to>|<instant>`,
   `RPC_BALANCE_LOW|<account>|<cycleEndsOn>`. A buy, a day and a low balance in one cycle are each reported once, ever.
3. A `BUY_RESULT` is published in the transaction that stores the buy's `SIMULATED` state
   (`AutomationPipelineTransaction.record`), only for a live buy (`backfillId` null), and only when at least one sweep row
   has a closed position with `realized_pnl` above or below zero. A buy whose variations all ended with no entry, a
   refusal, an open position or exactly zero sends nothing.
4. A variation's line reads profit when its position is closed and `realized_pnl > 0`, loss when closed and `< 0`;
   every other variation is listed by label under "no trade" with its outcome code. Amounts are the stored
   `realized_pnl` (SOL, scale 12) shown signed at scale 4, `RoundingMode.HALF_UP`, for display only. Order is `seq`.
5. A `DAY_VERDICT` is published the first time every row of that trading day is decided (none `PENDING`), checked hourly
   over the last `evaluation-window-sessions` days. It names the `SUCCESS` variations with their worst-case P&L and
   signal counts and counts the others. A verdict that changes later (an earlier day filled in) is not sent again. The
   first check after deployment can send up to seven messages.
6. Times in messages use `trenching.automation.zone`; mints and ids are shortened as the console does.
7. The dispatcher sends pending rows oldest first, at most 20 per run and at least one second apart. `Delivered` marks
   the row `SENT`. `RetryLater` (HTTP 429 with `retry_after`, 5xx, no response) schedules the next attempt after the
   stated wait or a doubling backoff from 30 seconds capped at 30 minutes; after `max-attempts` (10) the row is `FAILED`
   with one `WARN`. `Rejected` (400, 401, 403: wrong token or chat) is `FAILED` at once with one `WARN`.
8. A message longer than Telegram's 4,096 characters is cut at a line boundary and ends "… more in the console".
9. The bot token is part of the request URL: neither the URL nor the token is ever logged or stored; `last_error`
   holds a category only.
10. Not configured (no token or no chat id): one startup line says so, publishing is a no-op and the dispatcher does not
    run. A token without a chat id, or the reverse, fails startup naming the property.

**RPC accounts**

11. The active account is the first of primary, backup that is configured and not out of credits. The primary is always
    preferred when both have credits.
12. An account goes out of credits only on (a) an HTTP 429 whose body contains "max usage reached", case-insensitive, or
    (b) a balance read with `creditsRemaining` of zero. Any other 429 is a rate limit and is handled as today. A 401 or
    403 never switches accounts: it is a configuration error and stays visible.
13. On signal (a) the call that received it continues on the next account within its own attempts, so the capture
    operation does not fail. With no next account the call fails `CREDITS_EXHAUSTED`.
14. An out-of-credits account returns when a balance read shows at least `resume-credits` (1,000) remaining. An account
    whose balance cannot be read (no project id, Admin API refused) is re-tried once an hour with one `getHealth` request.
15. Every change of the active account publishes `RPC_ACCOUNT_SWITCHED` (to the backup, back to the primary, or "no
    account has credits") and logs one `WARN`. Marking is idempotent: two threads reporting the same exhaustion store
    one change and publish one message.
16. `RPC_BALANCE_LOW` is published when `creditsRemaining * 100 < creditsLimit * low-balance-percent` (integer
    arithmetic; default 10). Balance numbers are parsed as decimals and floored to whole credits.
17. While no account has credits: operation starts are refused `RPC_CREDITS_EXHAUSTED`; a waiting buy keeps its step
    and attempt; the hold `RPC_CREDITS` is logged once when it starts and once when it ends and makes the hourly summary a
    `WARN`. An operation in flight when the last account runs out fails that attempt; if it was the buy's last attempt
    the buy is `FAILED` with `CREDITS_EXHAUSTED` and can be retried from the console. Discovery scans are skipped with
    the existing `Discovery skipped` line.
18. Account state survives a restart: the directory loads `rpc_accounts` at startup, so a deploy does not re-send the
    switch message or spend a request rediscovering an empty account.
19. Captured evidence does not record which account served it: both are mainnet endpoints of one provider, and every
    operation still checks the genesis hash on the account it uses. Replay and verdicts are unaffected.
20. The request path never touches the database: `choose()` reads memory only.

**Negative cases the tests must cover:** duplicate publish; publish inside a transaction that rolls back; Telegram 429,
500, timeout and 401; a message over 4,096 characters; a buy with no closed position; a backfilled buy; a day with one
row still pending; a rate-limit 429 that must not switch accounts; "max usage reached" with and without a backup; both
accounts out; balance read 401, 403, 429, malformed body and zero limit; an RPC URL without `api-key`; restart while on
the backup; backup configured without a primary (startup fails).

## Data Model

Two new tables, both additive under `ddl-auto: update`. Nothing is dropped, renamed or narrowed, so nothing is applied
by hand on the server.

**`notification_outbox`** (entity in `notification.infrastructure.persistence`)

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `varchar(64)` PK | `sha256(notification_key)` |
| `notification_key` | `varchar(200)` not null | unique `uq_notification_outbox_key` |
| `kind` | `varchar(32)` not null | `NotificationKind` as string |
| `payload` | `text` not null | title, lines and links as JSON |
| `state` | `varchar(16)` not null | `PENDING`, `SENT`, `FAILED` |
| `attempts` | `integer` not null default 0 | |
| `next_attempt_at` | `timestamptz` not null | |
| `last_error` | `varchar(64)` | category only |
| `created_at`, `sent_at` | `timestamptz` | `sent_at` nullable |

Index `idx_notification_outbox_due (state, next_attempt_at)`. Rows older than `retention` (30 days) are deleted by the
dispatcher in bounded batches, so the table stays small.

**`rpc_accounts`** (entity in `console.solana.accounts.infrastructure.persistence`)

| Column | Type | Notes |
| --- | --- | --- |
| `name` | `varchar(16)` PK | `primary`, `backup` |
| `out_of_credits_since` | `timestamptz` | null while it has credits |
| `credits_remaining`, `credits_used`, `credits_limit` | `bigint` | last balance read, nullable |
| `cycle_ends_on` | `date` | nullable |
| `plan` | `varchar(32)` | nullable |
| `balance_checked_at` | `timestamptz` | nullable |
| `balance_problem` | `varchar(32)` | category of the last failed read, nullable |
| `updated_at` | `timestamptz` not null | |

At most two rows. No endpoint, key or project id is stored.

## Transactions & Integration

- **Telegram:** `POST https://api.telegram.org/bot<token>/sendMessage` with `chat_id`, `text`, `parse_mode=HTML` and
  link previews off, through a `RestClient` with 5 s connect and 10 s read timeouts. Sent outside any transaction; the
  row is updated in a short transaction afterwards. One instance only: two dispatchers would send twice.
- **Helius Admin API:** one `GET` per configured account every `refresh-interval` (15 minutes), with the same timeouts;
  fetched first, stored after. 400, 401 and 403 become a `balanceProblem` and are logged once per change, not every
  15 minutes; 429 and 5xx keep the last balance and try again next time. The Admin API allows 5 requests a second;
  whether it costs credits is not documented (Open Question 3).
- **RPC:** unchanged timeouts and retries. Each account has its own limiter, keyed by endpoint hash as today.
- **Configuration** (validated `@ConfigurationProperties`; all optional, so `application-prod.yml` is not edited):

| Property | Default | Environment variable |
| --- | --- | --- |
| `trenching.notification.telegram.bot-token` | empty | `TELEGRAM_BOT_TOKEN` |
| `trenching.notification.telegram.chat-id` | empty | `TELEGRAM_CHAT_ID` |
| `trenching.notification.console-base-url` | `http://localhost:8081` | |
| `trenching.notification.dispatch-interval` / `max-attempts` / `retention` | `30s` / `10` / `30d` | |
| `trenching.notification.kinds` | all four | |
| `trenching.solana.backup-rpc-url` | empty | `SOLANA_RPC_BACKUP_URL` |
| `trenching.solana.usage.primary-project-id` / `backup-project-id` | empty | `HELIUS_PROJECT_ID` / `HELIUS_BACKUP_PROJECT_ID` |
| `trenching.solana.usage.refresh-interval` / `low-balance-percent` / `resume-credits` | `15m` / `10` / `1000` | |
| `trenching.solana.usage.base-url` | `https://admin-api.helius.xyz` | |

The user adds the five variables to the server's environment file by name; no agent reads or writes that file.

## What To Implement

**A. Operations (no code; can start today)**

- **A1. Close the stuck buys.** With the tunnel open, read days 7–12 Nov, list the sessions that still have unfinished
  buys, show the count, then call the existing `POST /api/automation/sessions/{id}/close` once per session with a request
  id. `AutomationSessionTransaction.closeManually` already abandons unfinished buys of a closed session. Then re-read:
  those days are `complete`. This is permanent: an abandoned buy cannot be retried.
- **A2. Daily cap.** Two free accounts give 2,000,000 credits a month, about 66,000 a day. Until balances are visible,
  set `TRENCHING_AUTOMATION_MAX_REQUESTS_PER_24H=60000` on the server (now 100,000) and compare with the Helius
  dashboards. The user edits the environment file.
- **A3. Grafana.** The user runs runbook steps 14.1–14.7 on the server. The runbook gains one rule (unit D2).

**B. Notifications**

- **B1. A stored notification reaches Telegram once, or is retried.** The `notification` module: contract, outbox
  entity and repository, dispatcher and its schedule, Telegram channel, properties, startup line, retention.
- **B2. A simulated buy and a scored day publish their message.** Automation builds the two messages and publishes them
  under Rules 3–6. Depends on B1's contract.

**C. RPC accounts**

- **C1. An RPC call moves to the next account when the provider says the credits are used up.** `chaincapture`:
  endpoint source, the new category, per-target limiter and credential check.
- **C2. The backend knows each account's state and balance and holds capture when none has credits.** The directory,
  `rpc_accounts`, the Helius usage client and its schedule, `GET /api/solana/accounts`, the capture refusal, the
  automation hold, the two account messages, and the wiring of the three `CaptureRpcs.create` callers and the probe.
  Depends on C1 and on B1's contract.

**D. Console and documents**

- **D1. The Solana page shows both accounts** with state, credits left of the limit, the cycle end and the last check,
  and explains the new hold and the `RPC_CREDITS_EXHAUSTED` and `CREDITS_EXHAUSTED` codes. Frontend session, on C2.
- **D2. Runbook and references:** see Documentation Sync.

B1 and C1 touch different modules and can run in parallel. B2 and C2 both edit
`console/automation/application/AutomationPipelineService.kt` or its neighbours, so they run one after the other.

## Review amendments (2026-11-22)

Decided after the review of parts B1–C2. Where one of these differs from a rule above, this section wins.

**Notifications**

- **R1 (Rule 1).** The insert is `on conflict do nothing` with no conflict target, so the primary key and the unique key
  both arbitrate and two transactions publishing one key at once never raise.
- **R2 (Rule 7).** The dispatcher claims a row before sending: a short transaction raises `attempts` and moves
  `next_attempt_at` to the backoff time, then the message is sent, then the outcome is stored. A failed claim sends
  nothing; a failed outcome write repeats the message at most `max-attempts` times, with backoff.
- **R3 (Rule 7).** `max-attempts` defaults to 54, about 24 hours of retries at the 30-minute cap, so an outage delays
  messages instead of losing them (Key Decision 1). A wait asked by Telegram is capped at 24 hours, and after a 429 the
  dispatcher sends nothing to Telegram until that wait has passed.
- **R4.** Retention deletes only `SENT` and `FAILED` rows.
- **R5 (Rule 5).** Days decided in one check are published oldest first.
- **R6 (Rule 3).** A backfilled buy returns before any read. A failure while building a buy's message is logged and
  skipped; it never rolls back the buy's `SIMULATED` state. A failure of the insert itself still does.
- **R7.** `trenching.notification.telegram.link-style`: `ANCHOR` (default, every link an HTML anchor) or `PLAIN` (only
  the first link, as a plain URL), to settle Open Question 1 without a code change.
- **R8.** The test profile blanks every new provider setting (Telegram token and chat id, backup RPC URL, both project
  ids), so a test run never reaches Telegram or Helius even when the variables are exported.

**RPC accounts**

- **R9.** The balance refresh and its schedule exist only with the console enabled under the `local` or `prod`
  profile, like the capture console. The directory itself stays available everywhere.
- **R10 (Rule 9 for accounts).** No exception from a balance read is logged with its message or stack trace: every
  failure is classified into a `BalanceProblem`. The usage base URL is stored trimmed and must be https unless local.
- **R11.** Both RPC URLs are checked at startup with the transport's own rule (https unless local, no user-info, no
  fragment), each failure naming its own property.
- **R12 (Rule 19).** The backup is offered only after its genesis hash has been verified as mainnet in this process:
  on the first scheduled check after startup, or at the switch if that comes first. A backup on another network is
  never offered, is logged once and shows state `WRONG_NETWORK` in `GET /api/solana/accounts`. Until it is verified it
  counts as unavailable, so capture holds. The primary keeps today's checks.
- **R13 (Rule 14).** An account that returned and is marked out of credits again within an hour may not return for one
  hour; each further failed return doubles that wait, up to 24 hours; a return that lasts an hour resets it. This
  bounds the switch messages if the provider's balance figure runs ahead of what its RPC allows.
- **R14 (Rules 12, 14, 16).** Credits left are the plan's `creditsRemaining` plus `prepaidCreditsRemaining`.
- **R15 (Rule 6).** Account messages show times in `trenching.automation.zone`, like the other messages.
- **R16 (Rule 17).** An operation waiting for its own automatic retry that resumes while every account is out fails
  that attempt, as an operation in flight does. This is accepted and tested, not changed.

**For the console session (D1):** the API usage page must label task `NOTIFICATION`, task `ACCOUNT_BALANCE` and
provider `HELIUS_ADMIN`, and the accounts view must show `WRONG_NETWORK`.

## Verification

- `NotificationOutboxTest` (Testcontainers): Rules 1, 2 and 10; a publish inside a rolled-back transaction leaves no
  row; the unique key exists in the database.
- `NotificationDispatcherTest`: Rule 7's states and waits with a fake channel and clock; order, the 20-per-run bound,
  retention.
- `TelegramNotificationChannelTest`: against a JDK `HttpServer` fake (the pattern of `FakeSolanaRpcServer`): request
  shape, HTML escaping, 4,096-character cut, 429 with `retry_after`, 401, 500, timeout; no log line holds the token.
- `AutomationNotificationsTest`: Rules 3–6 from stored rows, including a backfilled buy, no closed position, exactly
  zero P&L, and a day with a pending row.
- `AutomationPipelineServiceTest` / `AutomationSweepServiceTest`: a simulated live buy has its outbox row, and has none
  after a failed transition.
- `HttpSolanaReadRpcTest`: Rules 12–13: a rate-limit 429 stays on the account, "max usage reached" moves the call to the
  backup and succeeds, no backup gives `CREDITS_EXHAUSTED`, limiter and credential check follow the target.
- `RpcAccountDirectoryTest` (Testcontainers): Rules 11, 14, 15, 16 and 18, with concurrent exhaustion reports.
- `HeliusUsageClientTest`: against a fake server with a sample shaped like the documented response; 400, 401, 403,
  429, malformed body; the key is sent only as `X-Api-Key`.
- `CaptureOperationServiceTest` and `AutomationPipelineServiceTest`: Rule 17: a refused start charges no attempt and the
  hold starts and ends once.
- `SolanaAccountsApiIntegrationTest`: the response never contains a URL, key or project id.
- Commands: `./gradlew unitTest`, then `./gradlew build` with Docker running; frontend `npm run test`, lint and build
  for D1.
- By hand, with the user's approval each time: one test message to the real chat (Open Question 1), and one balance
  read per real account.

## Documentation Sync

- `README.md`: the configuration table (properties above), a Notifications section, the Solana section (accounts,
  failover, hold), the automation waits paragraph (`RPC_CREDITS_EXHAUSTED`), the project layout (`notification`).
- `deploy/README.md`: the five environment variable names; step 14.7 gains "Capture held for hours": Loki
  `sum(count_over_time({instance="trenching-oci", unit="trenching-bot.service", level="WARN"} |= "AutomationActivitySummary" [6h]))`
  above 4, no data OK; and a note that the bot's own Telegram messages cover results and accounts.
- `.claude/skills/kotlin-spring-guidelines/references/integration-patterns.md`: the Telegram section becomes send-only
  with a configured chat.
- New ADRs: `docs/adr/20261122-outbox-notifications-over-telegram.md` and
  `docs/adr/20261122-rpc-account-failover-and-provider-balances.md`.
- `docs/IMPLEMENTATION_STATUS.md` and the architecture review's deferred list: notifications are no longer deferred.

## Acceptance Criteria

1. After A1, `GET /api/automation/days/2026-11-07` through `2026-11-12` each report `complete: true`, and the number of
   `ABANDONED` buys grew by the count shown before closing.
2. A live buy that becomes `SIMULATED` with one variation in profit has exactly one `notification_outbox` row with key
   `BUY_RESULT|<itemId>`, and the chat receives one message listing every variation and a link under the console address.
3. Sweeping the same buy again, or restarting the app, adds no row and sends no second message.
4. A buy whose variations all ended without a closed position, and any backfilled buy, has no row.
5. With Telegram answering 500, the row stays `PENDING` with `attempts` rising and is `SENT` once it answers 200.
6. A day whose rows are all decided has one `DAY_VERDICT` row; a day with a pending row has none.
7. With a fake provider answering "max usage reached" on the primary, a running market operation completes on the
   backup, `rpc_accounts.out_of_credits_since` is set for `primary`, `GET /api/solana/accounts` reports `active: "backup"`
   and one `RPC_ACCOUNT_SWITCHED` row exists.
8. With both accounts out, no buy changes to `FAILED`, the next operation start is refused `RPC_CREDITS_EXHAUSTED`, the log
   has one "paused" line for the hold, and the hourly summary is a `WARN`.
9. When the primary's balance read shows credits again, the next call uses the primary and one message says so.
10. `GET /api/solana/accounts` shows `creditsRemaining`, `creditsLimit` and `cycleEndsOn` for each account with a
    project id, and `balanceProblem` for one without; no response, log line or table holds a URL, key or token.
11. Without the new variables the app starts and behaves as today, with one startup line saying notifications are off.

## Open Questions

1. **Does Telegram make a `localhost` link tappable?** Not documented either way. Recommendation: after B1, send one
   test message with the user's approval containing an HTML link and a plain URL for both `http://localhost:8081` and
   `http://127.0.0.1:8081`; keep the form that is tappable in the user's client and set `console-base-url` to it. If
   Telegram refuses links to local addresses, each message carries one plain, copyable URL to the buy's page instead
   of a link per run.
2. **Is the Admin API enabled for a free Helius project?** The API can answer 403 "Admin API not enabled for this
   project". Recommendation: build it; if a free project is refused, that account shows "balance not available",
   failover still works through the 429 signal and the hourly `getHealth` re-check, and low-balance messages are not
   sent for it. Where the project id is found is not in the API page; it is expected in the Helius dashboard.
3. **Does reading the usage cost credits?** Not documented. At 15 minutes it is 96 reads a day per account.
   Recommendation: keep 15 minutes and compare `creditsUsed` across an idle hour once; lengthen the interval if it moves.
4. **The exact body of the "max usage reached" answer** is known only from the Helius FAQ. Recommendation: match the
   phrase anywhere in a 429 body; the balance read of zero is the second signal if the wording changes. A sanitized
   sample from the user would make the fixture exact.
5. **Manual runs send nothing.** Recommendation: keep it so; the person who starts a run is looking at it.

## Delivery status (2026-11-22)

Parts B1, B2, C1 and C2 are built, reviewed and fixed on branch `feature/telegram-alerts-backup-rpc`, uncommitted and
not merged. The plan stays here, not in the archive, until the open parts are done.

**Built:** the `notification` module (outbox, dispatcher, send-only Telegram channel); the buy-result and day-verdict
messages; the endpoint seam and credits detection in `chaincapture`; the account directory with failover and return,
balances from the Helius Admin API, the `RPC_CREDITS` hold, the two account messages and `GET /api/solana/accounts`.

**Where it differs from the plan above:** everything in "Review amendments" (R1–R16). Beyond those: any 4xx from
Telegram other than 429 is a rejection; a cut message keeps its first link; the token and chat id formats and the
Telegram and usage base URLs are validated at startup; `ApiPurpose` gained `SEND_NOTIFICATION` and `CREDIT_BALANCE`;
the transport's address rule is public as `chaincapture/RpcEndpointRules.kt`; the first balance read and network check
run 30 seconds after startup; a switch message is sent only when the usable account changes, so a restart on the
backup announces nothing.

**Schema:** two new tables, `notification_outbox` and `rpc_accounts`, both additive under `ddl-auto: update`. Nothing
is dropped, renamed or narrowed and no SQL is applied by hand. `api_usage_buckets.provider` and `.task` gain enum
values; the existing startup schema repair widens their checks, logging `Database schema repaired` once on the server.

**Verification:** `./gradlew build` with Docker running: 274 test classes, 2,647 tests, 0 failures, 0 skipped. No live
provider was called.

**Documents updated:** `README.md`, `deploy/README.md`, `docs/adr/20261122-outbox-notifications-over-telegram.md`,
`docs/adr/20261122-rpc-account-failover-and-provider-balances.md`, `docs/IMPLEMENTATION_STATUS.md`,
`docs/architecture/20261112-architecture-review.md`, and the skill's `SKILL.md` and `integration-patterns.md`.

**Open:**

- A1 is done (2026-11-22, 22:16 UTC+5): 15 closed sessions of 7–12 Nov were closed by hand through the console API,
  abandoning 193 unfinished buys (5, 27, 72, 66, 12 and 11 per day); all six days now read `complete`. 13 Nov (19
  buys) and 18 Nov (1 buy) are incomplete again because failed buys there were sent back with Retry; they were left
  alone. A2 and A3 are the user's server steps.
- D1: the console page for the accounts and the new labels (frontend session).
- Open Questions 1–4 need the real services: one test message to the chat, and one balance read per account.
- Merge into `develop`: `README.md`, `application.yml` and files under `console/automation` were also changed by the
  variations work in the main checkout and need reconciling.
- Accepted limits: a wrong token or chat id fails messages at once and they are not re-queued; a day decided later
  than the 7-day lookback is never announced; the return wait is kept in memory, so a restart forgets it.
