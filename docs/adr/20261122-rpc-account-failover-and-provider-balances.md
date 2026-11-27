# RPC account failover and provider balances

Accepted 2026-11-22 with the approved [Telegram alerts and backup RPC account plan](../plans/archive/20261122-telegram-alerts-and-backup-rpc-account.md) (Key Decisions 6 to 9; Domain Rules 2 and 11 to 20). This record covers `console.solana.accounts` and its wiring; the endpoint seam in `chaincapture` and the notification outbox have their own parts and records.

## Context

Every capture read one Helius account (`SOLANA_RPC_URL`). A free account gives 1,000,000 credits a month and the server spent about 100,000 a day. When the credits end Helius answers HTTP 429 "max usage reached", which the capture treated as an ordinary rate limit: it retried, failed the step, and after `max-step-attempts` failed the buy. Nothing showed how many credits were left. The user has a second Helius account.

What the change must not do:

- Change a simulation result. Both accounts read the same chain; the choice of account is plumbing.
- Put a request on the database's path. The RPC client asks which account to use before every request.
- Leak a key or a project id. The RPC URL embeds the API key, and the project id is part of the Admin API address.
- Turn "out of credits" into failed buys that need a person to retry them.

## Decision

1. **Two accounts, an ordered pair.** `primary` (`SOLANA_RPC_URL`) then `backup` (`SOLANA_RPC_BACKUP_URL`); the active account is the first that is configured and has credits, so the primary takes over again as soon as it has credits. A backup without a primary, or equal to it, fails startup naming `trenching.solana.backup-rpc-url`. Without a backup nothing changes.
2. **A directory in memory, stored once.** `RpcAccountDirectory` implements the `chaincapture` seam `RpcEndpointSource`. Its state lives in memory, loaded from `rpc_accounts` at startup, and `choose()` reads memory only. A change is made in memory first, under a lock, and then stored in one short transaction that also publishes the notification it causes; a second report of the same change finds it made and does nothing, so one exhaustion stores one change and sends one message. The table holds `out_of_credits_since`, the last balance read and its problem, never an endpoint, key or project id.
3. **Two signals for "out of credits".** A 429 whose body says "max usage reached" (the Helius FAQ's wording, matched anywhere in the body, case-insensitive), reported by the RPC client; and a balance read of zero. Either one marks the account out. No other status does: a 401 or 403 is a configuration error and stays visible, and a plain 429 is a rate limit.
4. **Return by balance, or by an hourly question.** An account that is out returns when a balance read shows at least `resume-credits` (1,000), so a refill of a handful of credits does not flap the choice. An account whose balance cannot be read (no project id, Admin API refused) is asked once an hour with one `getHealth` request and returns when it answers.
5. **Balances from the Helius Admin API,** `GET {base-url}/v0/admin/projects/{projectId}/usage`, with the API key of that account's RPC URL in the `X-Api-Key` header and nowhere else. The only new settings are the two project ids. Numbers are parsed as decimals and floored to whole credits; there is no `Double`. The read is fetched outside any transaction and stored after. A failed read keeps the last balance, stores a problem category and is logged once when the problem changes.
6. **A hold, not a failure, when no account has credits.** A `WALLET` or `MARKET` operation start is refused `409 RPC_CREDITS_EXHAUSTED`, and the automation keeps the buy at its step with no attempt charged, as it does for `NOT_CONFIGURED`, under a new hold kind that is logged once when it starts and once when it ends and turns the hourly summary into a `WARN`. An operation already running when the last account runs out fails that attempt as `CREDITS_EXHAUSTED`, like any provider failure; there is no special retry for it.
7. **Messages.** Every change of the active account is one `RPC_ACCOUNT_SWITCHED` message, keyed by account pair and instant, and one `WARN`. `RPC_BALANCE_LOW` is keyed by account and cycle end, so it is sent once per account and cycle, when `credits left * 100 < limit * low-balance-percent` in integer arithmetic; a limit of zero never reads as low.
8. **The daily cap stays global.** `max-requests-per-24h` keeps counting every provider request. The accounts change where requests go, not how many are allowed. The balance reads are counted as `HELIUS_ADMIN` requests (about 200 a day).
9. **No evidence records the account.** Captured evidence is unchanged, and every operation still checks the genesis hash of the account it uses, so replay and verdicts are unaffected.
10. **The backup is verified as mainnet before it is used (review amendment R12, strengthening Rule 19).** The directory offers the backup only after one `getGenesisHash` read in this process matched mainnet, made on the first scheduled check or synchronously at the switch, outside any transaction. Another network is never offered and shows `WRONG_NETWORK`; a check that could not be made leaves it unused and is repeated. Until then `choose()` answers out of credits, so capture holds. A message is sent when the account that is actually usable changes, so verifying the backup after a restart on it does not repeat the earlier switch message.
11. **An account that keeps failing after it returns waits longer (R13).** Marked out again within an hour of returning, it may not return for an hour; each further failed return doubles the wait up to 24 hours; a return that lasts an hour resets it. In memory only.
12. **Credits left include prepaid credits (R14),** floored once, for every rule and for display. A number is bounds-checked before it is rescaled, so a hostile exponent is refused as malformed.
13. **Nothing a balance read throws is logged (R10),** and the refresh exists only with the console enabled under `local` or `prod` (R9). The usage base URL must be https unless local; both RPC URLs are checked at startup with the transport's rule (R11).

## Alternatives

- **Count our own requests per account against a typed-in allowance.** Rejected: it misses use outside the bot, needs the allowance configured by hand, and would change the unique key of `api_usage_buckets`, which `ddl-auto: update` cannot do.
- **Switch on any 429.** Rejected: a rate limit clears in seconds and a switch would move all traffic to the backup for no reason; the body's wording separates the two.
- **Read the database on every request to find the active account.** Rejected: the RPC path would depend on the database and on a round trip per request.
- **Store before changing memory.** Rejected: concurrent reporters would each find the account still usable and all store and publish; memory-first under a lock makes the first change the only one.
- **Keep the failure and let a person retry the buys.** Rejected: it is the failure this change exists to remove; a hold costs nothing and ends by itself.

## Consequences

- One additive table, `rpc_accounts`; under `ddl-auto: update` nothing is applied by hand.
- If the transaction that stores a change fails, the memory keeps the change, an `ERROR` says so, and the next balance read stores the state again; only that change's message is lost.
- The Admin API's behaviour on a free project, whether a read costs credits, and the exact body of the "max usage reached" answer are not documented. A free project that is refused shows `ADMIN_API_DISABLED` and fails over through the 429 signal and the hourly question; a fixture from a sanitized sample would make the 429 body exact. The balance read of zero is the second signal if the wording changes.
- The first balance read and the backup's network check are 30 seconds after startup, then every `refresh-interval` (15 minutes); the stored state is shown at once. While the primary is out and the backup is not yet verified, capture holds.
- A backup that is out of reach at the switch makes the switch message read "no account has credits" until it is verified, when a second message says capture is on the backup.
- A single application instance is assumed: two instances would each read balances and could each publish a switch.
