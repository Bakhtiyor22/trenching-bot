# Decu Dip-Entry Strategy: Execution Spec (v2)

This file is the single source of truth for the bot. It answers both question rounds: *"A Few Things to Clarify"* and *"A Few Remaining Details"*. If the code and this file disagree, this file wins.

- Strategy name: `decu-dip`, version **2**. It runs as its own strategy with its own config and results. Existing capture modes stay unchanged.
- Last updated: 2026-10-11

### Changes from v1

| Area | v1 | v2 |
|---|---|---|
| First-buy check | Pre-trade balance + bot log (approximate) | **Strict**: live ownership log + history checked only since the token was created (Section 3) |
| Ownership log | Decu trades only | **Every** token balance change in Decu's wallet, any size, any token |
| Entry slippage | 40% | **15%**, measured against the quote at submission (Section 7) |
| Position slot | Taken when the buy confirmed | Taken **when the buy is prepared**, held until the result is final (Section 9) |
| Exits | "Sell 100% at deadline" | Exit **starts** at the deadline; retry policy; `UNRESOLVED` state (Section 8) |
| Price measure | Mixed (trade prints vs. post-trade) | **Post-trade price** everywhere, in SOL per token (Section 5) |
| Recording | Starts at detection | Backfills the missed beginning; coverage marked (Section 11) |

---

## 1. All parameters at a glance

| # | Parameter | Value |
|---|---|---|
| P1 | Wallet tracked | Decu (one wallet address) |
| P2 | Trigger | Decu's **first-ever interaction** with a token, and it is a buy (Section 3) |
| P3 | Signal size | **> 1.9 SOL**, strictly. Only SOL spent on tokens counts; network fees, tips and account rent are excluded |
| P4 | Reference price | Post-trade price **immediately after** Decu's buy |
| P5 | Min reference mcap | **> $10,000 USD** at the reference price |
| P6 | Entry level | Reference price × **0.80** (−20%) |
| P7 | Dip window | Buy must be **submitted ≤ 30.000 s** after `t0` (Decu's tx block time) |
| P8 | Entry slippage | **15%** above the quote at submission (Section 7) |
| P9 | Take profit | Our average fill × **1.55** (+55%) |
| P10 | Stop loss | Our average fill × **0.60** (−40%) |
| P11 | Time exit | Exit **starts** at `min(entry_confirmed + 3 min, t0 + 4 min)` |
| P12 | Position size | **0.1 SOL** |
| P13 | Max positions | **1** (including entries in flight) |
| P14 | Exit size | **100%**, no partial exits |
| P15 | Units | Price comparisons in **SOL per token**. USD only for the $10k filter. PnL reported in SOL and USD |

### Config (engine format)

```yaml
strategyName: decu-dip
strategyVersion: 2
trackedWallet: <DECU_WALLET>
minSignalSolExclusive: 1.9
minReferenceMcapUsdExclusive: 10000
dipBps: 2000
dipWindowMs: 30000            # submission deadline, inclusive
takeProfitBps: 5500           # from our average fill
stopLossBps: 4000             # from our average fill
maxHoldMs: 180000             # from entry confirmation
hardExitStartMs: 240000       # from t0
notionalSol: 0.1
maxOpenPositions: 1
entrySlippageBps: 1500
entryPriorityFeeSol: 0.001
exitRetry:                    # Section 8 (recommended values)
  intervalMs: 3000
  slippageBpsSteps: [1500, 1500, 1500, 2500, 2500, 2500, 4000]
  priorityFeeSol: { start: 0.001, multiplier: 1.5, maxPerAttempt: 0.003 }
  activePhaseMs: 60000
  maxPaidFeesSol: 0.02
  unresolvedRetryIntervalMs: 30000
history:
  gapFetchBudgetMs: 5000      # Section 3, only for tokens older than the log
recordingWindowMs: 240000     # from t0, extended while a position is unresolved
```

---

## 2. Lifecycle of one signal

```
Decu tx detected
  └─ Signal checks (Sec. 3, 4) ── fail → SKIP_* (token dead forever)
       └─ WATCHING (dip window, Sec. 6) ── no dip / too late → SKIP_NO_DIP
            └─ slot acquired → ENTERING (Sec. 7, 9)
                 ├─ failed / expired → ENTRY_* (slot released)
                 └─ confirmed → OPEN (Sec. 8)
                      └─ TP / SL / time → EXITING
                           ├─ sold → CLOSED (TAKE_PROFIT / STOP_LOSS / TIME_EXIT)
                           └─ can't sell → UNRESOLVED (alert, no new entries)
```

---

## 3. First-ever check: strict, only since the token was created

### Rule

A Decu buy is a signal only if Decu has **never had any interaction** with that token before this transaction:

- no earlier buy of any size (including ones he later sold), **and**
- no tokens received by transfer (even if later sent away), **and**
- no token account for that mint existing before this transaction.

If his history for that token **can't be established**, skip (`SKIP_UNVERIFIED`).

| Case | Result |
|---|---|
| Never touched it, buys 2.5 SOL | ✅ Signal |
| First buy 1 SOL, later buy 3 SOL | ❌ Both skipped (first too small; second not first) |
| Received by transfer, sent away, buys 3 SOL | ❌ `SKIP_NOT_FIRST` |
| Bought earlier, sold all, buys 3 SOL | ❌ `SKIP_NOT_FIRST` |
| Spam airdrop of the token, then buys 3 SOL | ❌ `SKIP_NOT_FIRST` (he received it) |
| Bot is catching up after a restart | ❌ `SKIP_UNVERIFIED` |

### Why we don't need his full wallet history

Decu can't touch a token **before it exists**. So the only history that matters is from the **token's creation** up to his buy.

Example: a token is created at 14:00 and Decu buys it at 14:06. Only his wallet activity from 14:00 to 14:06 matters: a handful of transactions, not his whole history. If the bot has been running since before 14:00, that activity is already in the live log, and the check needs **zero** RPC calls.

### The three parts

**A. Live ownership log** (runs from bot start)

- Stream every transaction that includes Decu's **wallet address**.
- Stream every change to token accounts whose **owner field is Decu** (SPL Token and Token-2022; owner at byte offset 32).
- Record **every** event: any token, any size, buys, sells, transfers in and out, account creates and closes. Store mint, slot, signature and type.
- Store `coverage_start_slot` (the log has no gaps from this slot onward) and `last_processed_slot` in the database.

This is separate from the 4-minute market recording in Section 11.

**B. Token creation slot**

- Stream pump.fun token-creation events and store `mint → creation_slot`. This costs one small event per new token.
- Fallback: the oldest signature of the token's bonding-curve account.
- If the creation slot can't be found → `SKIP_UNVERIFIED`.

**C. Pre-existing account check** (from the signal transaction itself)

- In the signal transaction's `preTokenBalances`, there must be **no** Decu-owned account for this mint. On a real first buy, his account is created inside that same transaction.

### Check at signal time

```
if log.state != LIVE:                          → SKIP_UNVERIFIED
creation = creation_slot(mint)
if creation is unknown:                        → SKIP_UNVERIFIED

if creation >= log.coverage_start_slot:
    history = log                              # instant, no RPC calls
else:
    # token is older than the log: fetch only the missing piece
    gap = Decu wallet txs from creation to coverage_start_slot
          (getSignaturesForAddress paging back + getTransaction in parallel)
          within a 5-second budget
    if not finished in 5 s:                    → SKIP_UNVERIFIED
    add gap to log; move coverage_start_slot back to creation
    history = log

if history has any event for this mint before the signal tx:   → SKIP_NOT_FIRST
if signal tx preTokenBalances has a Decu-owned account for mint: → SKIP_NOT_FIRST
→ first-ever ✅
```

The signal transaction itself is added to the log **after** this check.

### Why this is strict

Every way Decu could have held the token leaves a trace that one of the checks catches:

| How he could have held it | Caught by |
|---|---|
| Bought it (any size) | Wallet transaction → log |
| Sold it | Selling needs his signature → wallet transaction → log |
| Someone created his token account for it | Account creation includes his wallet address → log |
| Received tokens, still has the account | Check C (account already exists) |
| Received tokens, then sent them away or closed the account | Sending or closing needs his signature → log |

**Known limit (very small):** someone creates a *non-standard* token account for Decu and sends tokens into it, Decu never touches it, and it happens while the live token-account stream was not running. This is purely passive spam; it is accepted.

Scope: this is strict for **this wallet**. Other wallets Decu might own are not tracked.

### Restarts and gaps

- On startup, or after a stream disconnect, the log state is `CATCHING_UP`. All signals are `SKIP_UNVERIFIED` until it's `LIVE`.
- Catch-up fetches only the **downtime**: Decu's wallet transactions since `last_processed_slot`. This is usually a few calls.
  - Success → the log stays continuous (`coverage_start_slot` unchanged) → `LIVE`.
  - Failure → set `coverage_start_slot` to the current slot → `LIVE`. Older tokens then go through the 5-second gap fetch.
- Gaps in the token-account stream aren't refetched. Their only effect is the known limit above.

### Cost

| Item | Cost |
|---|---|
| Live log | A few events per Decu transaction |
| Creation events | One small event per new pump.fun token |
| Signal check, token created after bot start | 0 RPC calls (almost every signal) |
| Signal check, older token | Tens of calls, capped at 5 s, cached afterwards |
| Restart catch-up | Only the downtime |

A full download of his entire history is **not required**. It can still be run once later to push `coverage_start_slot` back to his first transaction, but the strategy doesn't depend on it.

---

## 4. Signal checks (in order)

| # | Check | Fail → |
|---|---|---|
| 1 | Transaction is a buy by Decu's wallet | ignore |
| 2 | SOL spent on tokens > 1.9 (excludes network fee, priority fee, tips, account rent) | `SKIP_SIZE` |
| 3 | First-ever (Section 3) | `SKIP_NOT_FIRST` / `SKIP_UNVERIFIED` |
| 4 | Reference mcap > $10,000 USD | `SKIP_MCAP` |
| 5 | No position or entry in flight (Section 9) | `SKIP_POSITION_OPEN` |

Note: the $10k filter applies to the **reference** mcap, not to the dip. A $12,000 reference qualifies, even though the entry level is then $9,600. This is intended.

---

## 5. Price measure

Every price comparison uses **one measure: the post-trade price, in SOL per token**.

| Venue | Post-trade price |
|---|---|
| pump.fun bonding curve | `virtual_sol_reserves / virtual_token_reserves` after the trade |
| AMM pool (after migration) | `sol_reserve / token_reserve` after the trade |

- **Reference** = post-trade price after Decu's buy.
- **Dip** = the post-trade price after any later trade is ≤ the entry level.
- **TP / SL** = the post-trade price after any trade crosses the level.
- **Our average fill** = SOL paid into the swap, including the protocol fee but excluding network and priority fees, ÷ tokens received.
- USD is used only for the reference-mcap filter: `reference_price × total_supply × SOL/USD at t0`.

The average fill price of a trade is never used as a trigger.

---

## 6. Dip window and timing

| Item | Definition |
|---|---|
| `t0` | Block time of Decu's transaction. Whole seconds and an estimate; we accept about ±1 s of imprecision |
| Deadline | Our buy must be **submitted** at local time ≤ `t0 + 30.000 s` (local clock NTP-synced) |
| Exactly 30.000 s | Counts |
| Submitted ≤ 30 s, lands later | Counts |
| Dip happened ≤ 30 s but bot saw it after 30 s | Skip (`SKIP_NO_DIP`) |
| Backfilled trades (Section 11) | Never trigger an entry |

Slot counts are logged, but they aren't used for the deadline. 75 slots is not exactly 30 s.

Time fields logged for every event:

| Event | On-chain | Local |
|---|---|---|
| Decu buy | slot, block time | received at |
| Dip trade | slot, block time | received at |
| Our buy | slot, block time | built at, submitted at, confirmation received at |
| Our sell (each attempt) | slot, block time | submitted at, confirmation received at |

---

## 7. Entry execution

- **Size:** 0.1 SOL.
- **Slippage:** 15%, measured against the **quote at submission**: the expected average price for 0.1 SOL given the current reserves. The highest price we accept is `quote × 1.15`.
  - pump.fun curve: `max_sol_cost = quoted_sol × 1.15`
  - Venues with a minimum-output setting: `min_tokens_out = quoted_tokens / 1.15`, which is the same 15% price limit, not "15% fewer tokens"
- **Priority fee:** 0.001 SOL.
- **One attempt only.** No retry on entry.

Example: reference $20,000, entry level $16,000. The bot quotes $16,050 and submits. The worst price we accept is about **$18,460**, which is still below the reference.

Entry outcomes:

| Outcome | Meaning | Slot |
|---|---|---|
| Confirmed success | Position `OPEN`; the 3-minute timer starts when we receive the confirmation | Kept |
| `ENTRY_FAILED_SLIPPAGE` | Landed on chain, failed on the price limit | Released |
| `ENTRY_FAILED_OTHER` | Landed on chain, failed for another reason | Released |
| `ENTRY_EXPIRED` | Blockhash expired without landing (checked against `lastValidBlockHeight`) | Released |
| `ENTRY_SEND_ERROR` | Rejected before reaching the network | Released |
| `ENTRY_UNKNOWN` | Status can't be determined | **Kept.** Check the wallet's token balance: tokens present → treat as `OPEN`; zero after expiry → `ENTRY_EXPIRED` |

An RPC timeout **never** counts as a failure on its own.

---

## 8. Exits

### Triggers

```
tp_price   = avg_fill × 1.55
sl_price   = avg_fill × 0.60
exit_start = min(entry_confirmation_received + 3 min, t0 + 4 min)
```

| Trigger | Reason code |
|---|---|
| Post-trade price ≥ `tp_price` | `TAKE_PROFIT` |
| Post-trade price ≤ `sl_price` | `STOP_LOSS` |
| `exit_start` reached | `TIME_EXIT` |

- The **first** trigger wins and fixes the reason. The position moves to `EXITING`, and later triggers are ignored.
- Only **one exit** runs at a time.
- The deadlines are when the exit **starts**. The position stays open until a sell is confirmed.

### Retry policy (recommended values; change if you want)

Each attempt is built with a fresh blockhash and sells the **current** token balance. If an earlier attempt lands, later ones fail on zero balance and can't oversell.

| Attempt | Sent at | Slippage | Priority fee |
|---|---|---|---|
| 1 | 0 s | 15% | 0.001 SOL |
| 2 | 3 s | 15% | 0.0015 |
| 3 | 6 s | 15% | 0.00225 |
| 4–6 | every 3 s | 25% | 0.003 (cap) |
| 7+ | every 3 s | 40% | 0.003 |

- Before each attempt, check the token balance. If it's zero, an earlier attempt landed: find that transaction, record it, and stop.
- Only fees for transactions that **land** cost money. Expired attempts are free.
- **Active phase:** 60 s, or until paid fees reach 0.02 SOL, whichever comes first.

### Unresolved positions

If the active phase ends and the position still isn't sold:

1. Mark the position `UNRESOLVED`.
2. **Alert** on Telegram with the token, reason, attempts and last error.
3. **Stop all new entries.** New signals are `SKIP_POSITION_OPEN`.
4. Keep retrying every 30 s at 40% slippage and 0.002 SOL priority fee until it sells.
5. When it sells, record the original reason with `resolvedLate: true`. New entries resume automatically once the wallet holds 0 of that token.

---

## 9. The position slot

- There is **one slot**. A watch has to acquire it **before building** the buy transaction.
- The acquire must be atomic (a lock or compare-and-set), so only one watch can win.
- The slot stays taken while the entry is being prepared, submitted, or awaiting confirmation, and through `OPEN`, `EXITING` and `UNRESOLVED`.
- It is released only on a final entry failure (Section 7) or on `CLOSED`.
- If two tokens dip at the same moment, the first to acquire the slot trades. The other watch ends as `SKIP_POSITION_OPEN`.

---

## 10. Migration

If the token migrates from the pump.fun curve to a pool **while watching or holding**:

- Keep the reference, entry level, fill, TP, SL and all deadlines unchanged.
- Switch the price stream and the trade route to the new pool.
- **Price gap** (no data from the new venue yet): no TP, SL or dip triggers can fire during the gap. Mark it as a coverage gap.
- **Watching:** if the dip window ends during the gap, `SKIP_NO_DIP`.
- **Holding:** if a deadline passes during the gap, start the exit as soon as a sell route works. If selling isn't possible, follow the Section 8 retry policy and `UNRESOLVED` path.

This is rare: entries happen at about $8k–16k mcap, well below where pump.fun tokens migrate.

---

## 11. Market recording (4-minute capture)

This is separate from the ownership log in Section 3.

- **Starts** when the bot sees a Decu buy > 1.9 SOL, even if a later check skips the signal.
- **Window:** `t0` → `t0 + 4 min`, **extended** until the position is `CLOSED` if an entry or exit is still in progress.
- **Backfill:** if the bot sees Decu's buy late, fetch the trades between `t0` and detection (signatures on the curve or pool account), merge them with the live stream, and dedupe by signature + instruction index.
- **Coverage flag:** `COMPLETE`, or `INCOMPLETE` with the missing time ranges.
- Backfilled trades are for **replay only**. They never trigger a live entry.

### Log row per signal

| Field |
|---|
| Mint, venue, Decu tx signature |
| Decu SOL spent on tokens; history-check path (log only / gap fetch) and log state |
| Reference price (SOL/token), reference mcap (USD), SOL/USD rate |
| Outcome: `SKIP_SIZE` · `SKIP_NOT_FIRST` · `SKIP_UNVERIFIED` · `SKIP_MCAP` · `SKIP_POSITION_OPEN` · `SKIP_NO_DIP` · `ENTRY_FAILED_SLIPPAGE` · `ENTRY_FAILED_OTHER` · `ENTRY_EXPIRED` · `ENTRY_SEND_ERROR` · `ENTRY_UNKNOWN` · `TAKE_PROFIT` · `STOP_LOSS` · `TIME_EXIT` · `UNRESOLVED` |
| All timing fields from Section 6 |
| Entry: quote, max price, average fill, tokens, fees |
| Exit: each attempt (slippage, fee, result), final fill, `resolvedLate` |
| Net PnL in SOL and USD, after all fees |
| Coverage flag and gaps |

---

## 12. Judging results

- Outcomes aren't just +55% / −40%. Time exits, slippage, fees and failed transactions all create other results.
- The 42% break-even win rate applies only to the simplified ±55/40 case, before costs. It is **not** the test for this strategy.
- Judge the strategy on **net PnL across every signal**, including skips, failed entries and unresolved positions.

### Rollout (before real funds)

1. **Replay:** run v2 on recorded data, with 15% slippage and real fees modelled.
2. **Paper live:** run on live data without sending transactions, and check whether the dips are still reachable when we detect them.
3. **Live:** 0.1 SOL, one position.

---

## 13. Worked example

Decu (log `LIVE`, token created after bot start, mint never touched) buys **2.5 SOL**. Post-trade mcap is **$20,000**.

| Step | Value |
|---|---|
| Checks | > 1.9 SOL ✅, first-ever ✅, $20k > $10k ✅, slot free ✅ |
| Entry level | $16,000 |
| Dip trade (post-trade $15,900) at 12 s | Slot acquired, quote $16,050, submitted at 12.6 s |
| Max price (15%) | ~$18,460 |
| Average fill | $16,120, confirmation received at 13.4 s |
| TP / SL | $24,986 / $9,672 |
| `exit_start` | min(13.4 s + 3 min, 4 min) = **3 min 13.4 s** after `t0` |
| Price hits $25,100 at 1 min 50 s | `EXITING` (`TAKE_PROFIT`), attempt 1 lands, `CLOSED` |
