# Wallet + token capture and first-buy verification

Accepted 2026-10-10 with the approved [wallet + token capture plan](../plans/archive/20261010-wallet-token-capture.md) (revision 3). It adds a third scope to [Pump.fun user-accumulator discovery](20261006-pumpfun-user-accumulator-discovery.md) and does not change the existing scopes. Amended 2026-10-10 before release with the review fixes: accumulator scans start at the head of their own listings, the Pump scan must list B, a buy that creates its mint is its own creation, a same-slot buy blocks step 1, the earliest outside buy decides first, and the qualification gains a pending state, a first-buy check and an export checksum check.

## Context

The [dip-entry strategy](20261008-dip-entry-strategy.md) starts one cycle per mint at the tracked wallet's first accepted buy, and P0 is that buy's price. "Accepted" means a decoded Pump.fun bonding-curve buy ([decoder ADR](20261003-pumpfun-bonding-curve-buy-decoder.md), [V3 ADR](20261010-pumpfun-exact-quote-in-v3-buys.md)). The existing scopes give each mint a market window at the first decoded buy *inside the sample*, so a later buy can stand in for the real first one.

The user wants to supply a wallet and one mint and learn whether that case qualifies under the dip rules: historical only, standard RPC only, from the console, with no override. A capped scan must be reported as unproven, never as complete or as a negative case.

## Research findings (2026-10-10)

No provider was called. Sources:

- the 18 published `idl/pump.json` versions in `pump-fun/pump-public-docs` (2025-04-04 to 2026-10-08), its README and instruction docs;
- the official developer channel `t.me/pump_tech_updates`, with post timestamps;
- the local `build/captures/decu-pumpfun-buys` evidence.

**Current routes.** Every route that buys on a Pump.fun curve passes a user volume accumulator, PDA(["user_volume_accumulator", user]), where `user` is the signer and the `TradeEvent` user:

| Route | Accumulator |
|---|---|
| `buy`, `buy_exact_sol_in` | Pump program's, account 13 |
| `buy_v2`, `buy_exact_quote_in_v2` | Pump program's, account 20 |
| `buy_v3`, `buy_exact_quote_in_v3` | Pump program's, account 11 |
| PumpSwap `multi_hop_swap` curve hop (Pump `multi_hop_curve_swap`) | **PumpSwap's** (`pAMMBay6…`), not Pump's |

The README states the accumulator "is mandatory for all buys and sells". A multi-hop curve hop emits a Pump `TradeEvent` "as if the user had traded there directly", so it is a curve buy the Pump accumulator listing does not contain. Coins are created only by `create` (`181ec828…`) and `create_v2` (`d6904cec…`); in both, account 0 is the mint they initialise.

**History.**

- Before 2025-07-26, `buy` had 12 accounts and no accumulator.
- Post 15 (2025-07-28 22:16 UTC) announced the two new required buy accounts for 2025-08-01 10:00 UTC.
- Post 16 (2025-08-01 11:31 UTC): "We updated both Pump and PumpSwap programs on Mainnet to require the 2 new additional accounts on buy instructions." This is the earliest confirmed go-live.
- Post 17 (2025-08-04): `buy` "still requires and re-creates the `user_volume_accumulator`", so closing it does not let a buy skip it.
- The 2025-08-28 revert (posts 18–20) concerned fee accounts, not the accumulator. No post or IDL removes the accumulator from any buy.
- Later buy routes carried it from their first IDL: `buy_exact_sol_in` (2025-10-17), `buy_v2`/`buy_exact_quote_in_v2` (2026-05-07), `buy_v3`/`buy_exact_quote_in_v3` and `multi_hop_swap` (2026-10-08). `create_v2` exists from 2025-11-12.

**Local evidence.** All 11 wallet-signed buys use `buy` through `FLASHX8D…`, on 4 Token-2022 mints. Each credited the wallet's derived Token-2022 ATA and passed its Pump accumulator `Hj2hjN1…`; the derivation reproduces Pump.fun's own `associated_bonding_curve`. This is current behaviour for one route only.

**What remains unestablished.**

- **G1, enforcement versus publication.** IDLs document the interface. That buys after 2025-08-01 could not omit the accumulator rests on post 16, Anchor's required accounts, and current local buys.
- **G2, unpublished instructions.** The README lists `buy_exact_quote_in`, which no published IDL contains (the exact-in buy is `buy_exact_sol_in`). An instruction absent from every published IDL that bypassed both accumulators would be missed.
- **G3, non-Pump.fun venues** are outside the strategy's definition and are not searched.
- **G4, provider index completeness.** `HISTORY_END` trusts the provider's signature index, as every scope does.

So "proven" means exactly: **actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC.**

## Decision

1. **A wallet-capture scope, not a pipeline.** `WALLET_TOKEN_ACCOUNT` (console `wallet-token`) takes a wallet and a mint and lists the wallet's associated token account, PDA([wallet, tokenProgram, mint], ATA program). Market, rates and export are reused unchanged.
2. **Token program by `getAccountInfo`.** One read with a zero-length data slice, before any listing. An owner other than Token or Token-2022 is `MINT_PROGRAM_UNSUPPORTED`, a missing account `MINT_NOT_FOUND`. Inspection re-derives the ATA and refuses a captured balance of the mint under another program.
3. **Two separate results.**
   - **TA-first (step 1):** the earliest decoded buy B among the ATA's transactions, accepted only when the listing was exhausted, no other decoded buy of the mint shares B's slot (`SAME_SLOT_BUY`, since within-slot order is not relied on), B credited the ATA, and no record in B's slot or earlier could be an unrecognised acquisition.
   - **Actual first buy (step 2):** whether B is the wallet's first Pump.fun curve buy of the mint by any route, whichever account received it. Only `Proven` unlocks market capture; neither result ever stands in for the other.
4. **Step 2 runs only after TA-first is found**, as part of the wallet stage:
   - **Identified creation boundary.** When B's own token-account artifact runs a successful `create`/`create_v2` of the mint (top-level or inner, account 0 the mint), the wallet created the token in its first buy: C is B's slot, the coverage date is checked against B's block time, and the mint is not listed at all. Otherwise list the mint before B to its end, then fetch its oldest successful entries, oldest first, until one runs a successful `create`/`create_v2` of the mint. C is that transaction's slot. Older entries (such as transfers to the address) are ignored, but an older successful `TradeEvent` of the mint contradicts the boundary. An older candidate without an artifact is skipped: `create` initialises the mint account and can succeed only once per mint, so it cannot be a second creation before the one found. The oldest signature is never assumed to be the creation.
   - **Coverage date.** A creation with no block time, or one before 2025-08-01T11:31:28Z, is `PRE_ACCUMULATOR_MINT`.
   - **Two accumulator scans, each from the head of its own listing.** The Pump and PumpSwap accumulators, derived from the wallet, are each listed newest first from the head of the listing to the first entry below C. No scan starts from a `before` cursor taken from another address's listing: how a provider resolves a signature that is not in the listed address's history is not specified, and a misread cursor could skip entries silently. The cost is that pages cover all of the wallet's accumulator activity from now back to C, still at most 50 pages per listing.
   - **The Pump scan must list B.** Every decoded route (legacy `buy`, `buy_exact_quote_in_v3`) passes the Pump accumulator, so B is in that listing. Whether the scan listed B is stored and recomputed offline. A scan that reaches below C without listing B means the provider's index or `before` handling cannot be trusted for this case: `FIRST_BUY_NOT_LISTED` (blocking B), decided as soon as the Pump scan is complete.
   - **Classification.** Entries are fetched only after both listings are complete, oldest first by slot then signature. Each successful entry in [C, B.slot] other than B is fetched once, or reuses an artifact the capture already holds. Mint absent or wallet not the signer: irrelevant. A decoded wallet buy of the mint: `OutsideTokenAccount`, reported and never adopted. Mint present, undecoded, balance up or unmeasurable: `EARLIER_UNDECODED_ACQUISITION`. No artifact: `EARLIER_UNKNOWN_RECORD`. Same-slot entries block. A listed entry without its listing's address is `INVALID_TRANSACTION` and is retried, as in discovery.
   - **Precedence.** The earliest decoded wallet buy decides `OutsideTokenAccount` as soon as no unresolved entry precedes it or shares its slot, ahead of the undecoded and unknown gaps and of the fetch cap, and fetching stops there: an earlier buy into another account is a fact whatever later entries hold.
5. **Caps are verdicts, not interruptions.** 50 pages per listing (mint, Pump accumulator, PumpSwap accumulator), 500 transaction fetches across step 2 (10 for the creation search), reused artifacts free. A cap reached before its bound is `VERIFICATION_BOUND` with the limit and the pages, entries, fetches and oldest slot per listing. Budgets, deadlines and provider failures still interrupt and resume from cursors saved after every page and fetch.
6. **Manifest version 4, for this scope only.** It adds `targetMint`, `tokenProgram` and `verification`: B, the creation search, both scans (each with whether it listed B), the verification records with hashed artifacts under `verification/`, the fetch count and the stored verdict. Existing scopes keep writing version 3, byte for byte. Verification records never become wallet records, so they never reach export, market planning or datasets.
7. **The verdict is re-derived offline.** A pure rule computes it from the stored evidence. Inspection decodes the artifacts, recomputes TA-first and the verdict, and refuses a manifest whose target, creation, scan completeness or verdict differs (`VERIFICATION_TARGET`, `VERIFICATION_RESULT`).
8. **`OTHER_MINT`.** In this scope, a decoded buy of another mint in the ATA listing is `UNSUPPORTED_ROUTE`.
9. **Qualification** replays the exported dataset with the default `SimulationConfig.mvp()` automatically after export; a replay failure is its own state, never a negative case.
   - A cycle that starts from another buy than the proven B is `REPLAY_FAILED` with category `FIRST_BUY_MISMATCH`: its P0 would not be the buy the strategy requires.
   - The console checks the export's recorded checksum against the dataset file before qualifying; a changed or missing file is refused with `409 EXPORT_CHANGED`.
   - Between a successful export and its qualification, the verdict says so: capture `COMPLETE`, stage `EXPORT`, reason `QUALIFICATION_PENDING`, replay `NOT_RUN`, never `NOT_EXPORTED`. An automatic qualification that cannot be queued or fails leaves that pending verdict, and Qualify runs it again.
   - The inspection-only verdict is part of the public `CaptureWorkflow` port, so the console never reaches into replay internals.

## Alternatives

- **V-mint: fetch every mint transaction before B.** Exhaustive for the wallet, but costs one fetch per trade of the token, thousands within an hour of launch on a busy token.
- **The oldest signature as the creation.** The mint address can receive transactions before it exists, so the oldest entry is not reliably the creation.
- **One accumulator.** The Pump accumulator alone misses PumpSwap multi-hop curve hops. Scanning PumpSwap's only after the multi-hop release would add an unverified time boundary for a small saving.
- **Helius filtered history.** A vendor contract, already rejected by the discovery ADR.

## Consequences

- A case is proven only within the stated coverage claim; G1–G4 are documented limits, not hidden assumptions.
- A wallet that created the token and bought in the creation transaction can be proven: B is its own creation, its slot is C, and the mint listing costs nothing. The accumulator scans must still reach below B's slot, and any other entry in B's slot still blocks.
- Cost is small for buys near launch and grows with the token's activity before B; a buy hours into a busy token hits the mint page cap. The accumulator scans also pay for the wallet's activity since B, because they start at the head: a very active wallet checked for an old buy can reach the 50-page cap (`VERIFICATION_BOUND`) before reaching B.
- Manifests can reach tens of megabytes only in the pathological case of two full accumulator scans of in-range entries.

## Revisit when

- An instruction is found that buys on a curve without either accumulator.
- Pump.fun changes the accumulator seeds or the create instructions.
- Caps are routinely hit for the cases the user cares about.
- Adopting an `OutsideTokenAccount` buy as the strategy's first buy is wanted.
