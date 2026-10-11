# Wallet + token capture and qualification

> A third wallet-capture scope that takes a wallet and one mint. It finds the earliest recognised buy through the wallet's token account, verifies separately whether that is the wallet's actual first Pump.fun bonding-curve buy of the mint, reuses the existing market, rates and export stages, and ends with an offline verdict: whether capture is complete, whether the dip condition qualified, and what the replay did.

Status: Delivered (approved 2026-10-10, in chat, conditional on the research below giving a usable scope). Revision 3 adds the historical coverage evidence, the creation boundary, the second accumulator and the cap reporting. The review fixes of 2026-10-10 (before release) replace the accumulator anchor with head-start scans, require the Pump scan to list B (`FIRST_BUY_NOT_LISTED`), let a buy that creates its mint be its own creation, block step 1 on a same-slot buy (`SAME_SLOT_BUY`), let the earliest outside buy decide first, and add `QUALIFICATION_PENDING`, `FIRST_BUY_MISMATCH` and `EXPORT_CHANGED`; this plan states what shipped.

## Context

Today a console capture session ([capture console ADR](../adr/20261010-capture-console.md)) runs three acquisition stages and offline steps:

- **Wallet** ([`WalletHistoryCaptureService`](../../src/main/kotlin/com/trenching/bot/walletactivity/application/WalletHistoryCaptureService.kt)) lists one address newest-first with `getSignaturesForAddress` (`until = null`). The address is the wallet itself or its derived Pump.fun user volume accumulator ([`WalletHistoryListing`](../../src/main/kotlin/com/trenching/bot/walletactivity/infrastructure/capture/WalletHistoryListing.kt)). Discovery stops at `LIMIT_REACHED` (1–100), `SCAN_BOUND` (≤ 50 pages) or `HISTORY_END`. There is no token filter.
- **Market** ([`ReplayMarketWindowPlanner`](../../src/main/kotlin/com/trenching/bot/replay/application/ReplayMarketWindowPlanner.kt), [`MarketHistoryCaptureService`](../../src/main/kotlin/com/trenching/bot/marketdata/application/MarketHistoryCaptureService.kt)) gives each mint one window [S+1, S+4500] after the first *decoded* buy in the sample. It lists the mint newest-first from now back to that buy.
- **Rates**, **export**, **calibration**: unchanged by this plan.

The user's decisions (in chat, 2026-10-10):

1. **Qualifying condition C**, the existing dip-entry rules ([dip-entry ADR](../adr/20261008-dip-entry-strategy.md)). After replay, also report whether an entry and an exit happened, and their outcome. Negative outcomes are kept distinct from qualifying dips and from incomplete captures.
2. **Wallet + mint supplied by the user.** The tool determines and explains whether the case qualifies. Chart screening and automatic discovery are later features.
3. **Historical first.** Live mode comes later, with Telegram alerts when C is met for the wallet's buy.
4. **Standard RPC only**, with documented limits and request usage.
5. **Full strategy window**, existing coverage and export rules.
6. **Console.**
7. **Review answers:**
   - Qualification runs automatically after export. A replay failure is separate from capture state and never a negative strategy case.
   - More than 100 records is a reported limitation.
   - There is no override.
   - An earlier buy found in another account is reported, and capture stops.
   - Public research on Pump.fun's IDL, docs and history is authorised; local `decu-pumpfun-buys` may be inspected offline. No provider calls.
   - Caps: 50 pages per listing and 500 transaction fetches. A capped scan is `FIRST_BUY_UNPROVEN`, with the limit reached and how much was scanned. It is never reported as complete or as a negative case.

**What this mode does and does not do.** It narrows capture to one wallet and one mint, proves (or refuses) the buy the strategy starts from, and explains the outcome afterwards. It **does not** tell you whether a token qualifies before the market capture. Condition C needs the trades of the 4,500-slot window, and those are the expensive part.

Existing capture modes and their behaviour stay intact.

## Which first buy the strategy requires

The dip-entry strategy starts one cycle per mint at "the tracked wallet's first accepted buy of a mint" ([dip-entry ADR](../adr/20261008-dip-entry-strategy.md)). P0 is that buy's price. "Accepted" means a decoded Pump.fun bonding-curve buy (`pumpfun-bonding-curve-buy/v1`): confirmed, successful, wallet-signed, SOL-quoted, with the `TradeEvent` user equal to the wallet ([decoder ADR](../adr/20261003-pumpfun-bonding-curve-buy-decoder.md), [V3 ADR](../adr/20261010-pumpfun-exact-quote-in-v3-buys.md)). The strategy defines no purchase on any other venue, and before migration the bonding curve is the token's market. So:

- **The required first buy** is the wallet's **first Pump.fun bonding-curve buy of the mint, by any curve route**, whether or not the decoder supports that route. If it used a route the decoder cannot price, the case is `FIRST_BUY_UNPROVEN`. A later recognised buy is never substituted.
- **Purchases on non-Pump.fun venues** (a third-party pool of the token before migration) are outside the strategy's definition. The verification below does not look for them, and the docs say so.

Two results are kept apart and never presented as each other:

- **Token-account first buy (TA-first):** the earliest decoded buy among the transactions listed for the wallet's associated token account (ATA) for the mint. It proves only what happened through that account.
- **Actual first buy:** the required first buy above, whichever token account received the tokens.

Market capture is offered only when the actual first buy is **proven** and equals TA-first.

## Research findings (2026-10-10)

All sources are public or local; no provider was called. Sources:

- the 18 published versions of Pump.fun's `idl/pump.json` in `pump-fun/pump-public-docs`, from 2025-04-04 to 2026-10-08;
- that repository's current README and instruction docs;
- the official developer channel `t.me/pump_tech_updates`, with its posts' timestamps;
- the local `build/captures/decu-pumpfun-buys` evidence.

### Current instruction set (IDL of 2026-10-08, README, instruction docs)

| Route that buys on a Pump.fun curve | Accumulator it passes |
|---|---|
| `buy` (`66063d12…`) | Pump program user volume accumulator, account 13 |
| `buy_exact_sol_in` (`38fc7408…`) | Pump accumulator, account 13 |
| `buy_v2`, `buy_exact_quote_in_v2` | Pump accumulator, account 20 |
| `buy_v3`, `buy_exact_quote_in_v3` | Pump accumulator, account 11 |
| PumpSwap `multi_hop_swap`, curve hop (Pump `multi_hop_curve_swap`, callable only by PumpSwap) | **PumpSwap's** user volume accumulator, PDA(["user_volume_accumulator", user], `pAMMBay6…`), *not* the Pump program's |

- In all of them the accumulator is a required account with seeds `["user_volume_accumulator", user]`, where `user` is the signer and the `TradeEvent` user.
- The README states "`user_volume_accumulator` account is mandatory for all buys and sells".
- The multi-hop doc states that each curve hop emits a Pump `TradeEvent` "as if the user had traded there directly" (`ix_name` `multi_hop_swap`). Such a buy is a Pump.fun curve buy that the Pump accumulator listing does not contain.
- Coin creation is only `create` (`181ec828…`) and `create_v2` (`d6904cec…`). In both, account 0 is the mint, a signer that the instruction initialises.

### Historical instruction set (IDL history and official posts)

- **Before 2025-07-26** (IDLs of 2025-04-04 and 2025-05-08): `buy` had 12 accounts and **no accumulator**.
- **2025-07-28 22:16 UTC, post 15:** "BREAKING update ... on August 1 at 10:00 AM UTC. buy instructions ... will require 2 new additional writable accounts", namely Pump `global_volume_accumulator` (12) and `user_volume_accumulator` (13), plus the PumpSwap equivalents. The IDL of 2025-07-26 already lists account 13.
- **2025-08-01 11:31 UTC, post 16:** "We updated both Pump and PumpSwap programs on Mainnet to require the 2 new additional accounts on buy instructions." This is the earliest time the requirement is confirmed live.
- **2025-08-04, post 17:** "buy instruction ... still requires and re-creates the `user_volume_accumulator` if it does not exist already." So closing the account does not let a buy skip it.
- **2025-08-28, posts 18–20:** a revert ("Changes have been fully reverted"). It concerned the new fee accounts (posts 18, 21, 22 and 24), not the accumulator. No post and no IDL version removes the accumulator from any buy.
- **Later buy variants carried the accumulator from their first published IDL:**
  - `buy_exact_sol_in`: IDL of 2025-10-17, with the program upgrade at 2025-10-17 16:47 UTC (post 26);
  - `buy_v2` / `buy_exact_quote_in_v2`: 2026-05-07 (post 41);
  - `buy_v3` / `buy_exact_quote_in_v3`: 2026-10-07 IDL, released 2026-10-08 (post 46);
  - PumpSwap `multi_hop_swap`: released 2026-10-08 (post 46).
- **`create_v2`:** from 2025-11-12 (posts 29–30). `create` exists in every version.

### Local evidence (decu-pumpfun-buys and decu-mainnet-successful)

- All 11 wallet-signed buys use `buy` through `FLASHX8D…`.
- All 4 mints use Token-2022.
- All 11 buys credited the wallet's derived Token-2022 ATA and passed the wallet's Pump accumulator `Hj2hjN1…`.
- The derivation reproduces Pump.fun's own `associated_bonding_curve` in the same transactions.
- This confirms current behaviour for one route and one router. It is not evidence about earlier buys or other accounts.

### What this establishes, and what it does not

- **Established (documentary):**
  - Every *documented* Pump.fun curve buy route since **2025-08-01 11:31:28 UTC** passes a user volume accumulator derived from the buyer: the Pump program's, or PumpSwap's for a multi-hop curve hop.
  - Every route introduced later required it from its introduction.
  - A Pump.fun mint cannot be bought on its curve before its `create`/`create_v2` transaction.
- **Not established by public evidence:**
  - **G1, enforcement versus publication.** IDLs and posts document the interface. Enforcement is confirmed only by the team's own posts, and by the local buys, which are current. That buys after 2025-08-01 *could not* omit the accumulator rests on post 16 and on the accounts being required in Anchor. No agent can observe historical program behaviour without provider calls or user-supplied transactions.
  - **G2, unpublished instructions.** The README's legacy SOL instruction list says `buy_exact_quote_in`. No published IDL version ever contains an instruction of that name: the exact-in buy is `buy_exact_sol_in`. This repository's decoder names its Anchor discriminator (`c62e1552…`) only for summaries. An instruction absent from every published, Anchor-generated IDL (post 17 says the IDLs are also published on chain) is unlikely to exist. If one did, and bypassed both accumulators, the verification would miss it.
  - **G3, non-Pump.fun venues** are outside the strategy definition (above).
  - **G4, provider index completeness:** `HISTORY_END` trusts the provider's signature index, as every existing scope does.

  None of these makes every result unproven. They limit what "proven" means, so the verdict states it precisely: **"actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC"**. A user-supplied transaction sample is not needed to start. One could only narrow G1/G2 later. Your existing market captures (thousands of other traders' buys, decu-dip-markets) could be checked offline for any buy event without the matching accumulator if you authorise it; see Open Question 1.

## How the actual first buy is verified

### Step 1: token-account first buy (cheap)

- **Source:** ATA = PDA([wallet, tokenProgram, mint], ATA program `ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL`). The listing is by address, so it answers after the account is closed, and a reopened ATA keeps its address.
- **Token program:** one `getAccountInfo(mint)` (`base64`, zero-length `dataSlice`, confirmed). The `owner` must be Token or Token-2022 and is recorded. Offline, inspection re-derives the ATA and checks every captured token balance of the mint reports that `programId`.
- **TA-first is found** when wallet capture is complete, discovery stopped at `HISTORY_END`, B (the earliest decoded buy of the mint) exists, no other decoded buy of the mint shares B's slot, and B credits the ATA. If no record in B's slot or earlier could be an unrecognised acquisition through this account, B is TA-first. That means no wallet-signed undecoded record where the wallet's balance of the mint increased or cannot be measured, and no record without an artifact.
- Results:
  - `Found(B)`;
  - `NoBuy`, which is complete and gives the valid answer "no buy of this token through this account";
  - `NotEstablished(reason, blocking)` with reason `HISTORY_NOT_EXHAUSTED` (more than 100 records or 50 pages), `SAME_SLOT_BUY` (blocking = the other decoded buys in B's slot), `EARLIER_UNDECODED_ACQUISITION`, `EARLIER_UNKNOWN_RECORD` or `NON_ATA_CREDIT`.

### Step 2: actual first buy (runs only after `Found(B)`)

1. **Creation boundary C, an identified creation transaction, not the oldest signature.**
   - If B's own token-account artifact runs a successful `create` or `create_v2` of the mint (top-level or inner, account 0 = mint), B is the creation: C = B's slot, the coverage date uses B's `blockTime`, and the mint is not listed (0 requests).
   - Otherwise list the mint with `before = B` down to `HISTORY_END` (signatures only, ≤ 50 pages).
   - Then fetch successful entries **oldest first** until one contains a successful Pump.fun `create` or `create_v2`, top-level or inner, whose account 0 is the target mint. At most 10 fetches are spent here. C is that transaction's slot.
   - Entries older than it (for example transfers to the address before it existed) are ignored. The token could not be bought on its curve before the curve existed. An unreadable older candidate is skipped: `create` succeeds only once per mint.
   - No creation found within those fetches, or `HISTORY_END` not reached → `FIRST_BUY_UNPROVEN (CREATION_NOT_IDENTIFIED)`, with what was scanned.
   - A successful Pump.fun `TradeEvent` for this mint in a transaction older than the creation contradicts the boundary and gives the same result.
2. **Accumulator coverage date.** If the creation transaction's `blockTime` is before 2025-08-01T11:31:28Z, or missing → `FIRST_BUY_UNPROVEN (PRE_ACCUMULATOR_MINT)`.
3. **Two accumulator range scans.** Scan the wallet's **Pump** accumulator and its **PumpSwap** accumulator, both derived offline from the wallet.
   - Each is listed newest-first from the **head of its own listing** (no `before`) down to the first entry in a slot below C. No cursor from another address's listing is used, because a provider's handling of a foreign `before` signature is not specified. Pages therefore cover the wallet's accumulator activity from now back to C, still ≤ 50 pages each.
   - Failed entries are counted and skipped.
   - Every decoded route passes the Pump accumulator, so the Pump scan must list B. Whether it did is stored and recomputed offline; a Pump scan that reaches below C without listing B → `FIRST_BUY_UNPROVEN (FIRST_BUY_NOT_LISTED)`, blocking B.
4. **Classify each successful entry in slots [C, B.slot], other than B.** Only after both listings are complete, fetch each once in ascending (slot, signature) order, with the existing structure, identity, credential-echo and unsafe-text checks, and a hashed artifact under `verification/`. ATA-listing artifacts are reused. The earliest decoded wallet buy decides `FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT` as soon as no unresolved entry precedes it or shares its slot, ahead of the gaps and caps below, and fetching stops there.
   - Target mint absent from its account keys → irrelevant. Every documented curve buy route passes the mint account.
   - Decodes as a wallet buy of the target mint → `FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT`, with its signature and the reason capture cannot proceed: the actual first buy differs from TA-first, and this version does not adopt it.
   - Target mint present, not decoded, and the wallet's balance of the mint increased or cannot be measured (for example a multi-hop curve hop, an unsupported buy instruction, a post-migration AMM buy) → `FIRST_BUY_UNPROVEN (EARLIER_UNDECODED_ACQUISITION)`.
   - No artifact (unavailable, unsupported version, withheld for unsafe text) → `FIRST_BUY_UNPROVEN (EARLIER_UNKNOWN_RECORD)`.
   - A listed transaction without the listed accumulator is `INVALID_TRANSACTION`: verification stays incomplete, as in discovery.
5. **Caps.** These are shared across step 2:
   - at most 50 pages per listing (mint, Pump accumulator, PumpSwap accumulator);
   - at most 500 transaction fetches in total, including creation fetches.

   Reaching one before its bound → `FIRST_BUY_UNPROVEN (VERIFICATION_BOUND)` with the limit reached and the scanned pages, entries, fetches and oldest slot reached per listing. It is never reported as complete or as a negative case.
6. **Proven** when the creation is identified, the coverage date holds, both scans reached below C, and every entry in range is irrelevant or B. The verdict text states the coverage claim exactly as above.

Verification transactions live in a separate manifest section. They never become wallet records, so they never reach decoding for export, market planning or the dataset.

### Cost (standard RPC)

| Part | Requests |
|---|---|
| Step 1 | 1 genesis + 1 `getAccountInfo` + 1 ATA page + 1 detail per successful ATA record. Typically about 5–25. |
| Creation boundary | ⌈mint entries before B, failed included / 1,000⌉ pages, plus about 1 fetch. 1 page for a buy at launch; a busy token adds roughly tens of thousands of entries per hour, so about 30–60 pages for a buy an hour after launch, and a buy several hours in on a busy token hits the cap. |
| Accumulator scans | ⌈wallet accumulator entries from now back to C / 1,000⌉ pages each (≤ 50), plus 1 fetch per wallet Pump/PumpSwap transaction between creation and B. For this wallet's captured rate (10 buys in about 1,000 slots), that is a handful per minute of token age at the buy; a very active wallet checked for an old buy can reach the page cap. |
| Market, rates | Unchanged; the market stage still dominates. |
| Qualification | 0. |

## Scope

### In scope

- Wallet scope `WALLET_TOKEN_ACCOUNT` (console `wallet-token`): steps 1 and 2, gating market planning.
- Reuse of the market, rates and export stages, unchanged.
- Automatic offline qualification after export, with the verdict.
- Console: the scope option, a mint field, the verdict panel and reason codes.
- Docs: capture guide section, ADR, README line.

### Out of scope

- Adopting an earlier buy found outside the token account; V-mint (fetching every mint transaction before B).
- Pre-market-capture screening; live/push mode and Telegram alerts; Helius-specific APIs.
- Early stop; any change to coverage, export or replay rules.
- A CLI option for the new scope. CLI inspect/market/export read such directories because they share services.
- Any change to the existing scopes, their manifests (they keep writing version 3), the decoder's supported routes or the console defaults.

## Key Decisions

1. **A wallet-capture scope, not a new pipeline**, following [ADR 20261006](../adr/20261006-pumpfun-user-accumulator-discovery.md).
2. **TA-first and the actual first buy are separate results.** Only a proven actual first buy equal to TA-first unlocks market capture.
3. **Two accumulators (Pump and PumpSwap)**, scanned for every case. Restricting the PumpSwap scan to dates after the multi-hop release would add an unverified time boundary for a small saving.
4. **The lower boundary is an identified creation transaction.** The oldest returned signature is not trusted as creation.
5. **Coverage date 2025-08-01T11:31:28Z**, the official go-live confirmation, compared against the creation transaction's `blockTime`.
6. **`getAccountInfo` for the token program**; the existing scopes are untouched; **manifest version 4 only for the new scope**, with `targetMint`, `tokenProgram` and `verification`.
7. **`OTHER_MINT`**: a decoded buy of another mint in the ATA listing is `UNSUPPORTED_ROUTE`.
8. **Qualification = replay of the exported dataset with `SimulationConfig.mvp()`**, run automatically after export and stored on the operation record; `REPLAY_FAILED` is its own state.

## Module Contracts

All additive. No port signature changes, no replay dataset schema change, no contract break.

**`walletactivity` (public)**
- `WalletHistoryScope.WALLET_TOKEN_ACCOUNT`.
- `WalletHistoryCaptureRequest.targetMint: MintAddress? = null`. Required for the new scope, refused otherwise (`REQUEST_BOUNDS`).
- `WalletHistoryManifest.targetMint`, `tokenProgram`, `verification` (nullable).
- `WalletHistoryDecodeResult.targetToken: TargetTokenEvidence? = null`:
  ```
  sealed interface TokenAccountFirstBuy { Found(signature, slot, instructionIndex) | NoBuy | NotEstablished(reason: TokenAccountGap, blocking) }
  enum class TokenAccountGap { HISTORY_NOT_EXHAUSTED, EARLIER_UNDECODED_ACQUISITION, EARLIER_UNKNOWN_RECORD, NON_ATA_CREDIT, SAME_SLOT_BUY }

  sealed interface ActualFirstBuy {
      Proven(signature, slot, instructionIndex, creationSignature, creationSlot, creationTime, scannedTo: Map<listing, Slot>)
      Unproven(reason: FirstBuyGap, blocking: List<TransactionSignature>, bound: VerificationBoundHit?)
      OutsideTokenAccount(signature, slot)
      NotVerified                                   // step 1 not Found, or step 2 incomplete
  }
  enum class FirstBuyGap { CREATION_NOT_IDENTIFIED, PRE_ACCUMULATOR_MINT, EARLIER_UNDECODED_ACQUISITION, EARLIER_UNKNOWN_RECORD, VERIFICATION_BOUND, FIRST_BUY_NOT_LISTED }
  data class VerificationBoundHit(limit: MINT_PAGES | PUMP_ACCUMULATOR_PAGES | PUMPSWAP_ACCUMULATOR_PAGES | FETCHES,
                                  pages: Map<listing, Int>, entries: Map<listing, Int>, fetches: Int, oldestSlot: Map<listing, Long?>)
  data class TargetTokenEvidence(mint, tokenProgram, tokenAccount, pumpAccumulator, pumpSwapAccumulator, tokenAccountFirstBuy, actualFirstBuy)
  ```

**`walletactivity` (internal)**
- `WalletCaptureFormat.TOKEN_SCOPE_VERSION = 4`, `READABLE_VERSIONS = {2, 3, 4}`.
- `WalletHistoryListing` derives the ATA and both accumulators.
- `TokenAccountFirstBuyRule`, `ActualFirstBuyRule` (domain, pure); `PumpFunBuyRejection.OTHER_MINT`.
- `PumpFunCoverage`: the coverage date, the create/create_v2 discriminators, and the PumpSwap program id, each with its source cited in KDoc.

**`chaincapture`**: `ChainCaptureReads.mintOwner(rpc, mint, budget)` (`getAccountInfo`); a null value gives `MINT_NOT_FOUND`.

**`protocol.solana` / `protocol.pumpfun`**
- Token, Token-2022, ATA and PumpSwap program ids.
- `CREATE` / `CREATE_V2` discriminators.

**`replay` (public)**
- `CaptureStepCommand.Qualify`, `CaptureStepResult.Qualified(verdict)`, `CaptureInspection.targetToken`.
- `CaptureWorkflow.verdict(inspection, datasetExported)`: the inspection-only verdict, so the console needs no replay internals.
- `TargetTokenVerdict`:
  ```
  capture:  COMPLETE | COMPLETE(EXPORT, QUALIFICATION_PENDING) | INCOMPLETE(stage, reason) | UNUSABLE(stage, reason)
  tokenAccountFirstBuy, actualFirstBuy (both always shown, with the coverage claim text for Proven)
  dip:      QUALIFIED(trigger) | NOT_QUALIFIED(DipWatchOutcome) | NOT_EVALUATED(reason)
  entry:    FILLED(...) | NOT_FILLED(RejectionReason) | null
  exit:     CLOSED(CloseReason, slot, realizedPnl) | OPEN(RejectionReason) | null
  replay:   RAN | FAILED(category) | NOT_RUN
  classification: QUALIFYING_DIP | NEGATIVE_CASE | INCOMPLETE_CAPTURE | FIRST_BUY_UNPROVEN | FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT | NO_TARGET_BUY | REPLAY_FAILED
  provenance: datasetChecksum, runId, configurationHash, engineVersion, P0, first-buy signature/slot
  ```

**`console` (HTTP, additive)**
- `CaptureWalletSettings.mint: String? = null`; scope `wallet-token`; `walletTokenDefaults`.
- `CaptureInspectionResponse.targetToken`, `CaptureSessionResponse.qualification`; step `QUALIFY`.

## Domain Rules

1. The ATA and both accumulators are pure functions of their seeds, derived in one place. A mismatching `listingAddress` is refused (`LISTING_ADDRESS`). A token balance of the mint whose `programId` ≠ `tokenProgram` is refused (`TOKEN_PROGRAM_MISMATCH`).
2. The mint owner ∉ {Token, Token-2022} gives `MINT_PROGRAM_UNSUPPORTED`. A missing mint account gives `MINT_NOT_FOUND`. Both happen before any listing and are non-retryable.
3. Resume requires the same wallet, scope, mint, limit and scan pages (`RESUME_IDENTITY`). The saved token program is reused. Every verification cursor is saved after each page and fetch checkpoint, and pages are never rescanned.
4. TA-first follows step 1; the actual first buy follows step 2.
   - Canonical order, with same-slot entries blocking: within-slot order is not relied on.
   - `NOT_SIGNER` and `WALLET_ABSENT` records are never wallet buys.
   - Failed entries are skipped.
5. Step 2 is an acquisition phase of the wallet stage. The stage is incomplete until verification reaches a result, and it stops and resumes on budgets and deadlines like discovery. A cap is a result (`VERIFICATION_BOUND`), not an interruption.
6. Market planning for this scope:
   - only `ActualFirstBuy.Proven` gives one window, [B.slot + 1, B.slot + horizon], with `untilSignature` B;
   - every other state leaves the wallet stage `UNUSABLE` with the verdict's reason.

   Existing scopes plan exactly as today.
7. Qualification requires a successful export (checksum-verified load) and runs `ReplayRunner` with `SimulationConfig.mvp()`. It makes no provider call, no clock read and no database write, and the same dataset gives the same `runId`.
8. Verdict mapping from the single target intent:
   - `TRIGGERED` → `QUALIFYING_DIP`;
   - any other watch outcome → `NEGATIVE_CASE`;
   - a fill rejection → `NOT_FILLED(reason)`;
   - a closed position → `CLOSED(reason, slot, pnl)`, an open one → `OPEN(reason)`;
   - no intent → `NOT_EVALUATED(<ingestion reason>)`, `NEGATIVE_CASE`.
9. A replay that throws, more than one intent, or a cycle whose source buy is not the proven B (`FIRST_BUY_MISMATCH`) gives `replay = FAILED(category)` and `REPLAY_FAILED`. `capture` keeps its state, and no strategy outcome is invented. It is logged at `ERROR` with the exception.
10. A capture that is not export-ready gets a verdict from inspection alone: `replay = NOT_RUN`, with classification `INCOMPLETE_CAPTURE`, `FIRST_BUY_UNPROVEN`, `FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT` or `NO_TARGET_BUY`. None of these is ever a negative case. An exported capture whose qualification has not run yet shows capture `COMPLETE` at stage `EXPORT` with reason `QUALIFICATION_PENDING`, never `NOT_EXPORTED`. Before qualifying, the console verifies the export's recorded checksum against the file (`409 EXPORT_CHANGED` otherwise).
11. Numbers come from the report unchanged (`BigDecimal`, existing scales) and are shown as exact decimal strings.
12. "First buy proven" wording is shown only for `ActualFirstBuy.Proven`, and always with its coverage claim. TA-first is labelled "earliest buy through token account <address>".

## Data Model

No database schema change. Everything is file-backed:

- Wallet manifest version 4 (new scope only): `targetMint`, `tokenProgram`, `listingAddress` = ATA, and `verification`:
  - `creation {listing cursor, pages, entries, oldestSlot, historyEnd, candidates fetched, signature, slot, blockTime}`;
  - `pumpAccumulator {address, cursor, pages, entries, skippedFailed, oldestSlot, listedFirstBuy, complete}`;
  - `pumpSwapAccumulator {…}`;
  - `records` (signature, slot, listing, outcome, artifact, sha256, bytes);
  - `fetches`;
  - `result`.

  Artifacts go under `verification/`, hashed like the others.
- Console session/operation records gain nullable `wallet.mint` and `qualification`. Existing files load unchanged, and the fingerprint includes the mint when present.

## Transactions & Integration

Standard RPC only (`SOLANA_RPC_URL`, already configured; no new variable). Methods: `getGenesisHash`, `getAccountInfo` (new, read-only), `getSignaturesForAddress`, `getTransaction`. Existing timeouts, retries, deadlines, budgets, the credential-echo check, unsafe-text rules and locks apply. The scope's default per-operation request budget is 1,000 (the console's existing wallet maximum). There is no database transaction and no new configuration property.

## What To Implement

1. **Addresses and the mint read** (`protocol.solana`, `protocol.pumpfun`, `chaincapture`): ATA and PumpSwap-accumulator derivation, program ids, create discriminators, `mintOwner`. Vectors come from local artifacts: `associated_user`, `associated_bonding_curve`, `Hj2hjN1…`.
2. **Step 1 scope** (`walletactivity`): request field, validation, resume identity, ATA listing, and manifest v4 for this scope only.
3. **Step 2 acquisition** (`walletactivity`): creation boundary, both accumulator scans, caps with scanned counts, verification artifacts, cursors and resume.
4. **Offline rules** (`walletactivity`): `TokenAccountFirstBuyRule`, `ActualFirstBuyRule`, `OTHER_MINT`, the `programId` check, and `targetToken`.
5. **Planning gate and inspection** (`replay`).
6. **Qualification and verdict** (`replay`), with `REPLAY_FAILED` and the inspection-only verdict.
7. **Console backend** (`console`): scope, mint, fingerprint, exhaustive scope mapping (today's two `if`s in `CaptureOperationService` become a `when`), qualification queued after export, verdict on the session.
8. **Console frontend** (`frontend/src/features/captures`).
   - The scope option and the mint input.
   - A verdict panel with rows for Capture, Earliest buy through token account, Actual first buy (with its coverage claim, or the gap, limit and scanned counts), Dip condition and Replay.
   - Reason labels and mocks.
9. **Documentation.**

Units 1→2→3→4→5→6→7 depend in order. Unit 8 can run beside 6–7.

## Verification

Test-first. Fixtures only, no live provider.

- `TokenAccountAddressTest`, `VolumeAccumulatorAddressTest`: local-artifact vectors; Token-2022 versus Token; the Pump and PumpSwap accumulators differ.
- `WalletHistoryCaptureServiceTest` (fake RPC):
  - call order: mint read, ATA listing, mint listing with `before = B`, creation fetch oldest-first, both accumulator scans from their heads to below C, then entry fetches oldest first;
  - a creating B lists no mint; a Pump scan without B is `FIRST_BUY_NOT_LISTED`; no fetch after the deciding outside buy;
  - a pre-creation transfer is ignored;
  - no creation in the fetch allowance, or no `HISTORY_END`, gives `CREATION_NOT_IDENTIFIED`;
  - each cap gives `VERIFICATION_BOUND` with the limit and scanned counts;
  - resume at every phase without rescanning;
  - unsupported owner or missing mint;
  - existing-scope tests unchanged.
- `WalletHistoryArtifactStoreTest`:
  - v4 round-trip with verification;
  - version and field rules;
  - tampered program, listing address, verification artifact or result refused;
  - an existing-scope save stays v3, byte-identical.
- `TokenAccountFirstBuyRuleTest`: every outcome and gap; a same-slot blocker; non-signer records ignored.
- `ActualFirstBuyRuleTest`:
  - proven, with the claim fields;
  - an earlier decoded buy into another account gives `OutsideTokenAccount`;
  - an earlier multi-hop curve hop (mint present, undecoded, balance up) gives `EARLIER_UNDECODED_ACQUISITION`;
  - an entry with no artifact;
  - mint absent is ignored;
  - a same-slot entry blocks;
  - creation before 2025-08-01T11:31:28Z, or a missing `blockTime`, gives `PRE_ACCUMULATOR_MINT`;
  - a trade older than the creation gives `CREATION_NOT_IDENTIFIED`.
- `PumpFunBuyDecoderTest`: `OTHER_MINT` only in the new scope.
- `RecordedWalletPlanningTest`, `CaptureInspectionServiceTest`:
  - only `Proven` plans one window at B;
  - every other state is `UNUSABLE` with its reason;
  - existing scopes unchanged.
- `TargetTokenQualificationServiceTest`:
  - single-mint datasets from the `dip-sample` fixtures cover every outcome mapping;
  - no intent;
  - a corrupted dataset gives `REPLAY_FAILED` with capture `COMPLETE`;
  - inspection-only verdicts;
  - determinism.
- Console:
  - `CaptureValidationTest`;
  - `CaptureSessionServiceTest` (fingerprint, legacy files);
  - `CaptureOperationServiceTest` (three scope mappings, qualification after export only for `wallet-token`, no provider call).
- Frontend: the mint field only for the new scope; the verdict panel renders each classification, the two first-buy rows, the coverage claim and a cap's scanned counts.
- Regression: existing fixture exports keep their checksums.

Commands (JAVA_HOME as in the capture guide):

```bash
./gradlew test
```

```bash
npm --prefix frontend test
```

```bash
npm --prefix frontend run lint
```

```bash
npm --prefix frontend run build
```

## Documentation Sync

- `docs/REAL_DATA_CAPTURE.md`: a "Wallet + token capture" section. It covers which first buy the strategy requires, the two results, steps 1–2 with caps and costs, the coverage claim and G1–G4, the verdict and its reasons, and the statement that qualification happens only after market capture.
- ADR `docs/adr/20261010-wallet-token-capture.md`: the decisions above and the research findings with their sources.
- README: one sentence in the capture paragraph. `docs/IMPLEMENTATION_STATUS.md` if it lists scopes.

## Acceptance Criteria

1. Starting wallet acquisition for a "Wallet + token" session writes a v4 manifest whose `listingAddress` is the ATA for the recorded `tokenProgram`. The fake-RPC test shows the step 1 and step 2 call order.
2. For a verifying fixture, inspection shows "Earliest buy through token account: <sig>" and "Actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC (creation <sig> at slot C; scanned Pump and PumpSwap accumulators to below C)". Market start offers one window from B + 1.
3. An earlier decoded buy into another token account shows `FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT`, its signature and why capture cannot proceed; market start is disabled.
4. A capped fixture shows `FIRST_BUY_UNPROVEN`, the limit reached and the scanned pages, entries, fetches and oldest slot; it is never shown as complete or as a negative case.
5. After a successful export, the verdict appears without a click. For the take-profit fixture: Capture `COMPLETE`, actual first buy proven, Dip `QUALIFIED`, entry `FILLED`, exit `CLOSED TAKE_PROFIT`, with P&L equal to a default Datasets run of the same file.
6. `NO_DIP` shows `NEGATIVE_CASE`. An incomplete market capture shows `INCOMPLETE_CAPTURE`. A corrupted export shows `REPLAY_FAILED` with Capture `COMPLETE`.
7. Existing-scope sessions and CLI commands are unchanged: v3 manifests, same export checksums.
8. No database, configuration-property or dependency change.

## Later: live mode (not in this plan)

Poll both accumulators for the wallet's new buy, then the mint over the horizon. Fetch each trade through the verified path, evaluate C as trades and rates arrive, and send one Telegram alert per cycle. It gets its own plan.

## Open Questions

1. **Narrowing G1/G2 with existing local evidence (optional).** Your market captures (for example `decu-dip-markets`) hold thousands of *other traders'* Pump.fun buys. An offline check could confirm that every buy `TradeEvent` there sits in a transaction containing the buyer's Pump or PumpSwap accumulator, across every route seen. Recommendation: authorise it if you want stronger evidence. It does not block implementation.

## Noticed, not changed

- In the existing scopes, a window starts at the first decoded buy *inside the sample*. If the wallet's real first buy of a mint is older than where discovery stopped, P0 comes from a later buy.
- `PumpFunLayout.UNSUPPORTED_INSTRUCTIONS` names `BUY_EXACT_QUOTE_IN` (`c62e1552…`). No published Pump.fun IDL contains that instruction (the exact-in SOL buy is `buy_exact_sol_in`, `38fc7408…`), and `buy_exact_sol_in`, `buy_v2`, `buy_exact_quote_in_v2` are not named. This only affects summary labels; fixing it is a separate change.

## Delivered

2026-10-10. Shipped units 1–9:

- **Wallet stage:** the `WALLET_TOKEN_ACCOUNT` wallet scope, the `getAccountInfo` token-program read, ATA listing, manifest v4 (new scope only; existing scopes still write byte-identical v3), and both first-buy results with offline recomputation.
- **Replay:** the market-planning gate, inspection evidence, `TargetTokenQualificationService` with the verdict types, and `CaptureWorkflow.verdict`.
- **Console:** the `wallet-token` scope and mint, automatic `QUALIFY` after export, and the `targetToken` / `qualification` JSON.
- **Frontend:** the create form and the "Token verdict" panel.
- **Docs:** ADR [20261010-wallet-token-capture](../../adr/20261010-wallet-token-capture.md), the "Wallet + token capture" section of [REAL_DATA_CAPTURE.md](../../REAL_DATA_CAPTURE.md), and a README sentence.

Differences from revision 3 came from the review fixes, all before release; the Status line lists them:

- Accumulator scans start at the head of their own listing instead of an anchor, so they no longer depend on how a provider resolves a foreign `before` signature.
- The Pump scan must list B (`FIRST_BUY_NOT_LISTED`).
- A buy inside its mint's creation transaction is its own creation boundary.
- A same-slot sibling buy blocks step 1 (`SAME_SLOT_BUY`).
- The earliest outside buy stops verification and wins over caps.
- Qualification reports `QUALIFICATION_PENDING`, refuses `FIRST_BUY_MISMATCH`, and the console refuses a changed export (`EXPORT_CHANGED`).
- `CaptureWorkflow.verdict` takes a `datasetExported` flag.

No database schema, configuration property or dependency change. v4 directories written before the review fixes fail strict validation and must be captured again; none were released.

Verification:

- `./gradlew build`: success (702 tests, fresh run).
- Frontend: tests (136), lint and build pass.
- Mocked console checked in the browser at desktop width.

Delivered 2026-10-10: the frontend follow-ups in [20261010-wallet-token-frontend-followups.md](20261010-wallet-token-frontend-followups.md): new code labels, pending-qualification wording, narrow-width layout of the verdict panel.
