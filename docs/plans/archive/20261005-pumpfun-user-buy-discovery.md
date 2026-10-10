# Pump.fun user-buy discovery

> Find a wallet's own Pump.fun bonding-curve buys by listing the per-user Pump.fun account they write to, instead of every transaction that mentions the wallet.

Status: Approved (2026-10-06, in chat)

## Context

Wallet capture (`walletactivity`, [`WalletHistoryCaptureService`](../../../src/main/kotlin/com/trenching/bot/walletactivity/application/WalletHistoryCaptureService.kt)) selects the latest N successful transactions referencing the wallet, from `getSignaturesForAddress(wallet)` ([successful-reference discovery](../../adr/20261003-successful-reference-discovery.md)). For Decu's wallet `4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9` this finds almost nothing to replay:

| Capture | Scanned | Failed (skipped) | Selected | Signer | Decoded buys |
|---|---|---|---|---|---|
| 2026-10-03 `decu-mainnet-successful` | 6,911 | 6,811 | 100 | 1 | 1 |
| 2026-10-05 `wallet-2` | 5,449 | 5,349 | 100 | 0 | 0 |

Offline analysis of the user's own artifacts (no provider calls) shows why. Every non-signer transaction lists the wallet as a read-only, non-signer account, in programs of other fee payers. 83 of 100 and 96 of 100 call the same third-party program (`DhpyNWkd…`), and one fee payer (`BuekUk…`) pays for 67 in each capture. These are bots watching Decu's wallet, not Decu trading. The ADR's revisit trigger ("successful copy-bot traffic dominates the selection") has fired.

**The anchor.** The one decoded buy (`35Vnpu9D…`, slot 453004996, routed through `FLASHX8D…`) passes Pump.fun's `buy` 18 accounts.
- Account 12 is the `global_volume_accumulator`; account 13 is `Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D`.
- Re-deriving offline gives `Hj2hjN1…` = PDA(["user_volume_accumulator", wallet], Pump.fun) with bump 255. It is the per-user volume account that Pump.fun's `buy` writes for its user.
- No other transaction in either capture (199 others) contains it.

Its signature listing is therefore the wallet's own Pump.fun activity, without the bot noise.

## Scope

### In scope

- A second wallet-capture scope, `PUMPFUN_USER_VOLUME_ACCUMULATOR`. Discovery lists the wallet's derived Pump.fun user volume accumulator instead of the wallet. Selection, verification, artifacts, the decoder, market capture and export are otherwise unchanged.
- Offline derivation of Solana program-derived addresses (SHA-256 + ed25519 curve check, JDK only).
- Wallet manifest schema version 3, recording scope and listed address. Version 2 directories stay readable as the existing scope.
- A resolved `WALLET_ABSENT` outcome for an accumulator-listed transaction that does not contain the wallet.
- A `--scope` option on `captureWalletHistory`; scope and listed address in both commands' output.

### Out of scope

- Buys from before the accumulator existed, and buys on other routes (PumpSwap, Raydium, `buy_exact_sol_in`). The decoder still supports only `buy`.
- Any change to the decoder, market capture, export, replay schema v1, simulation semantics or the console.
- Dropping a source buy from an export when its market window is unusable. That is a separate decision; see Open Questions.
- Helius-specific APIs, token-account enumeration and time/slot windows.

## Key Decisions

1. **List the user volume accumulator, derived from the wallet.**
   - Its listing contains only transactions that pass that account, which in practice means the user's own Pump.fun calls.
   - It is derived offline from the wallet and the Pump.fun program id, so the user still supplies only the wallet.
   - It is standard RPC (`getSignaturesForAddress`), within the existing provider decision ([bounded capture](../../adr/20261003-bounded-wallet-history-capture.md)).

   Rejected alternatives:
   - **A deeper wallet-reference scan:** about 1 own buy per 7,000 references. Ten buys would need about 70 pages (above the 50 maximum) and about 1,300 detail requests, nearly all spent on bot noise.
   - **Token accounts (`getTokenAccountsByOwner`):** lists only open accounts, and Pump.fun traders usually close them after selling. It also needs one listing per mint.
   - **Helius filtered history:** a vendor contract, already rejected in the [discovery ADR](../../adr/20261003-successful-reference-discovery.md).
2. **Everything after discovery is shared.** Successful-only selection, the cursor and resume, budgets, transaction checks, unsafe-text withholding, artifacts and decoding are the same code for both scopes. Only the listed address and one outcome differ. A buy found this way is still accepted only if the decoder proves it is a `buy` signed by the wallet (decoder ADR).
3. **`WALLET_ABSENT` instead of `INVALID_TRANSACTION`** for an accumulator-listed transaction whose account keys do not contain the wallet. Anyone can pass any account in a transaction, so this is possible noise, not a contradiction of the listing. An `INVALID_TRANSACTION` would leave the capture incomplete on every rerun. In the reference scope, a missing wallet still contradicts the listing and stays `INVALID_TRANSACTION`.
4. **Manifest version 3, version 2 read as the reference scope.** The new fields are `scope` and `listingAddress`. A loaded version 2 manifest becomes scope `WALLET_REFERENCES` with `listingAddress` = wallet, and any save writes version 3. `decu-mainnet-successful` and its v1 dataset export keep working. Version 1 stays refused.
5. **The listed address is re-derived on every load.** A manifest whose `listingAddress` does not match its scope and wallet is refused as tampered (`MANIFEST`).
6. **PDA derivation lives in a new `protocol.solana.ProgramAddress`.** It is generic Solana, not Pump.fun, and not a value type, so it does not belong in `common`. Pump.fun's seed stays in `PumpFunLayout`. No new dependency: the JDK `MessageDigest` (SHA-256) and `BigInteger` arithmetic (ed25519 decompression test) suffice.

This changes discovery scope, which feeds which source buys reach a replay, so an ADR records it (Documentation Sync).

## Module Contracts

All changes are **additive**. No port signature changes, no replay schema change, no contract break.

- `walletactivity` (public):
  - `enum class WalletHistoryScope { WALLET_REFERENCES, PUMPFUN_USER_VOLUME_ACCUMULATOR }`
  - `WalletHistoryCaptureRequest` gains `scope: WalletHistoryScope = WALLET_REFERENCES`.
  - `WalletHistoryManifest` gains `scope: WalletHistoryScope` and `listingAddress: String`.
  - `WalletHistoryOutcome` and `WalletHistoryDecodeOutcome` gain `WALLET_ABSENT`, a resolved pass-through with no artifact.
- `walletactivity` (internal):
  - `WalletCaptureFormat.VERSION = 3`, plus `READABLE_VERSIONS = {2, 3}`.
  - A `WalletHistoryListing.address(scope, wallet): String` that both the service and the store use.
- `protocol.solana` (new): `object ProgramAddress { fun find(seeds: List<ByteArray>, programId: String): Derived; fun isOnCurve(publicKey: ByteArray): Boolean }`, with `data class Derived(val address: String, val bump: Int)`.
- `protocol.pumpfun.PumpFunLayout`: `USER_VOLUME_ACCUMULATOR_SEED = "user_volume_accumulator"`.
- `replay.api` (thin adapter):
  - `--scope wallet-references|pumpfun-user-buys` on `CaptureWalletHistoryCommand` (default `wallet-references`), and Gradle `-Pscope=`.
  - Capture and inspection print `scope=LATEST_SUCCESSFUL_WALLET_REFERENCES` or `scope=LATEST_SUCCESSFUL_PUMPFUN_USER_ACCUMULATOR_REFERENCES`, with `listingAddress=<address>`.

## Domain Rules

1. `ProgramAddress.find(["user_volume_accumulator", 4vw54…], 6EF8…)` = `Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D`, bump 255 (on-chain evidence).
2. More golden vectors, re-derived offline. The first three match accounts in the user's captured buy; the bump-253 vector uses a key from the artifacts and is cross-checked with an independent algorithm, not seen on chain:
   - `["global_volume_accumulator"]` → `Hq2wp8uJ9jCPsYgNHex8RtqdvMPfVGoYwjvF1ATiwn2Y` (255);
   - `["__event_authority"]` → `Ce6TQqeHC9p8KetsN6JsjHK7UTZk7nasjjnr7XxXp9F1` (255);
   - `["bonding-curve", 5bYUihk…]` → `Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye` (255);
   - `["user_volume_accumulator", 2AZhVvG43iccBRntR4Z5wyzd4tHJpuqFwaavYyt3k5ZR]` → `CZhuXtooUDxFt1G1xcSngPFW1PMxnofXMxxiy61UBQaT`, **bump 253**. This vector proves on-curve candidates are skipped.
3. Derivation edge cases:
   - `isOnCurve` is true for a wallet public key (`4vw54…`) and false for every derived address above.
   - A seed longer than 32 bytes or more than 16 seeds is refused (`IllegalArgumentException`).
   - No bump found is refused with a `CaptureValidationException("PROGRAM_ADDRESS")` before any provider request.
4. In `PUMPFUN_USER_VOLUME_ACCUMULATOR` scope, every listing request uses the derived address. The wallet itself is never listed. The genesis check, page size, `confirmed` commitment, `before` cursor and budgets are unchanged.
5. Resume compares wallet, limit, scan pages **and scope**. Any difference → `RESUME_IDENTITY`, before any request.
6. An accumulator-scope transaction:
   - without the wallet in its account keys is `WALLET_ABSENT`, with no artifact, and counts as resolved;
   - with the wallet as a non-signer is `NON_SIGNER`;
   - with the wallet as a signer is `UNCLASSIFIED`, then decoded as today;
   - with a sell, `buy_exact_sol_in` or claim instruction decodes `UNSUPPORTED_ROUTE`.
7. In `WALLET_REFERENCES` scope, a missing wallet stays `INVALID_TRANSACTION`, so existing behaviour and tests are unchanged.
8. Loading a manifest:
   - A version 2 manifest loads as `WALLET_REFERENCES` / wallet.
   - A version 3 manifest whose `listingAddress` ≠ `WalletHistoryListing.address(scope, wallet)` is refused (`MANIFEST`).
   - Unknown scope text is refused (`MANIFEST`), and version 1 is refused (`MANIFEST_VERSION`), as today.
9. Decoding, market-window planning and export depend only on the decoded transactions and the wallet, never on scope. Exporting the existing captures gives the same bytes and checksum `3a477117…` as before.
10. Determinism: a given listing and set of transaction responses produce the same manifest bytes in both scopes. `capturedAt` comes from the injected `Clock`, as today.
11. No numbers are calculated in this change; amounts stay as the decoder produces them.

## Data Model

No schema change. No entities or tables are involved. The wallet capture manifest JSON goes from version 2 to version 3 (local research artifact, not a database record).

## Transactions & Integration

- No database and no `@Transactional`. Commands run outside Spring Boot, as today.
- Provider calls are unchanged: `getGenesisHash`, `getSignaturesForAddress` (on a different address), `getTransaction`. Timeouts, retries, rate limit, credential handling and the response-echo checks are the existing ones. Only the user runs the capture.
- Cost, per run: at most 1 + `scanPages` + `limit` requests, the same as today (121 with defaults).
- **Advice for market capture** (docs only): every decoded buy becomes a window.
  - Recommend `-Plimit=10` for a first run, then run market capture straight away.
  - On busy mints, the window listing grows with every later trade, and both captures share the 1,000-request ceiling.
- No configuration properties are added; scope is a command option.

## What To Implement

1. **Program addresses** (`protocol.solana.ProgramAddress` + test): the golden vectors, on-curve checks and seed bounds. Independent of everything else.
2. **Accumulator scope in capture** (`walletactivity`). Depends on 1.
   - The scope enum, request field and `WalletHistoryListing`.
   - Discovery lists the derived address.
   - `WALLET_ABSENT` in `WalletTransactionEvidence`, outcome sets and decode pass-through.
   - Resume identity includes scope.
3. **Manifest v3** (`WalletHistoryArtifactStore`, `WalletCaptureFormat`): write v3; read v2 as `WALLET_REFERENCES`; re-derive and refuse a mismatched listed address. Same files as 2, so the same owner, after 2.
4. **Commands and docs** (`replay.api`, `build.gradle.kts`, docs): the `--scope` / `-Pscope` option, the printed scope and listed address, and the `WALLET_ABSENT` line. Depends on 2 and 3.

Units 1 and 2–3 have no shared files. Unit 4 follows them.

## Verification

- `ProgramAddressTest`: domain rules 1–3.
- `WalletHistoryCaptureTest`, with a fake `SolanaReadRpc` as today:
  - In accumulator scope, every `getSignaturesForAddress` call's first parameter is `Hj2hjN1…`, never the wallet.
  - A listing of one signer buy, one non-signer and one wallet-absent transaction gives `UNCLASSIFIED` / `NON_SIGNER` / `WALLET_ABSENT`, `complete=true`, and artifacts only for the first two.
  - Changing scope on resume → `RESUME_IDENTITY` with zero requests.
  - The existing reference-scope tests pass unchanged, and a missing wallet is still `INVALID_TRANSACTION` there.
- `WalletHistoryPaginationTest`: cursor, `HISTORY_END`, `SCAN_BOUND` and `LIMIT_REACHED` behave identically in accumulator scope.
- `RecordedWalletHistoryDecodeTest`:
  - An accumulator-scope capture of the real buy artifact decodes the same `WalletTransaction` as the reference scope.
  - `WALLET_ABSENT` passes through.
- Artifact store tests:
  - A version 2 manifest fixture loads as `WALLET_REFERENCES` and saves as version 3.
  - A version 3 manifest with a wrong `listingAddress` and one with an unknown scope are both refused (`MANIFEST`).
  - Version 1 is still refused.
- Command tests: `--scope` parsing (default, both values, invalid → usage exit), and the printed `scope=` / `listingAddress=` lines.
- The existing export test still produces checksum `3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d`.
- Commands: `./gradlew test` (one Gradle at a time; Docker is needed only for the existing Testcontainers suites).
- User-run, after delivery (the agent never runs this):
  ```
  .\gradlew.bat captureWalletHistory -Pwallet=4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9 -Pscope=pumpfun-user-buys -Plimit=10 -PscanPages=20 -PcaptureDirectory=build/captures/decu-pumpfun-buys -PrequestBudget=400 -PbyteBudget=104857600
  ```
  then `inspectWalletHistory`, `captureReplayMarkets`, `inspectReplayMarkets` and `exportRecordedReplay` into new directories and a new output file.

## Documentation Sync

- New ADR [`docs/adr/20261006-pumpfun-user-accumulator-discovery.md`](../../adr/20261006-pumpfun-user-accumulator-discovery.md), dated on acceptance. It covers the evidence above, the decision and rejected alternatives, and this consequence: only buys after the accumulator was introduced, and only Pump.fun. It is revisited when the wallet trades elsewhere or Pump.fun changes the account.
- The [successful-reference discovery ADR](../../adr/20261003-successful-reference-discovery.md): a note under "Revisit when" that its trigger fired and links the new ADR.
- `docs/REAL_DATA_CAPTURE.md`:
  - the scope option, the new output lines and `WALLET_ABSENT`;
  - manifest v3 and reading v2;
  - the recommended limit;
  - the end-to-end commands for several buys;
  - the "more than one source buy" paragraph, updated.
- README: the capture task option, in the capture section.
- `docs/IMPLEMENTATION_STATUS.md` and `docs/PROVIDER_SPIKE.md` (scope line).

## Acceptance Criteria

- `ProgramAddress.find` returns `Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D`/255 for Decu, and `CZhuXtoo…`/253 for the second vector.
- An accumulator-scope capture never lists the wallet address. Its manifest has `schemaVersion=3`, `scope=PUMPFUN_USER_VOLUME_ACCUMULATOR` and `listingAddress=Hj2hjN1…`.
- Capture and inspection print `scope=LATEST_SUCCESSFUL_PUMPFUN_USER_ACCUMULATOR_REFERENCES … listingAddress=Hj2hjN1…`.
- A wallet-absent transaction is `WALLET_ABSENT`, the capture completes, and no artifact is written for it.
- `inspectWalletHistory` on `build/captures/decu-mainnet-successful` (version 2) prints the same counts and decoded trade as before.
- Re-exporting the v1 dataset gives checksum `3a477117…`, with `written=false`.
- All existing tests pass, and the new tests above pass.
- After the user's run: `decode.DECODED` > 1 for Decu, and the export carries more than one source buy. This depends on Decu's real activity; the plan cannot guarantee it.

## Progress

- **2026-10-06, units 1–4 implemented.**
  - `protocol.solana.ProgramAddress` matches all golden vectors.
  - `WalletHistoryScope`, `WalletHistoryListing`, `WALLET_ABSENT`, manifest version 3 (version 2 is read) and `--scope` / `-Pscope` are in place.
  - The docs are synced.
- **Deviations from the contract text:**
  - `ProgramAddress.find` returns `Derived?`; `WalletHistoryListing` turns null into `PROGRAM_ADDRESS`, so `protocol` does not depend on `chaincapture`.
  - The manifest's `scope` and `listingAddress` default to the reference scope and the wallet.
  - A forged listed address is refused as `LISTING_ADDRESS`. A version 2 manifest that carries either new field, or an unknown scope, is refused as `MANIFEST_FIELDS`. `WALLET_ABSENT` under the reference scope is refused as `RECORD_OUTCOME`.
  - In both scopes, a transaction without the listed address is `INVALID_TRANSACTION`. For the reference scope this is the existing missing-wallet rule.
  - The capture log line carries `scope=`.
- **Review (2026-10-06):** the `backend-reviewer` found no blockers. Fixed:
  - a version 2 export fixture and an accumulator-scope export of the real buy now give byte-identical datasets, and the same decoded transaction, as version 3;
  - accumulator-scope pagination is tested across a resumed page (`LIMIT_REACHED` mid-page) and at `SCAN_BOUND`;
  - the bump loop stops at 1, like Solana's `find_program_address`;
  - the listed address is a required parameter of the evidence check;
  - the store always saves the current version;
  - a test label and the bump-253 vector's provenance are corrected.
  - Kept as planned: `WALLET_ABSENT` stores no artifact, like `UNAVAILABLE`.
- **Verification:**
  - `./gradlew test`: 379 tests pass.
  - Offline, on the user's real version 2 capture: `inspectWalletHistory` on `decu-mainnet-successful` prints the same counts and decoded trade as before, with `listingAddress` = the wallet.
  - `exportRecordedReplay` to `recorded-replay-v1.json` gives checksum `3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d` with `written=false`.
- **Open:** the user's first live run with `-Pscope=pumpfun-user-buys`.

## Open Questions

1. **Recommended default for `-Plimit` in accumulator scope.** Keep the shared default (100) and recommend 10 in the docs, or default to 10 in this scope? *Recommendation:* keep 100 and document 10 for the first run. One default per option is simpler, and the user sets it explicitly anyway.
2. **What happens when one buy's market window is unusable.** Export refuses the whole dataset: a token that graduated in its window, a gap, or an exclusion is enough. With several buys this becomes more likely. *Recommendation:* not in this plan. Run it with 10 first. If the export is refused, decide then between narrowing the capture and a "drop the source buy, record why" export rule (a fidelity decision that needs its own ADR).
3. **Whether Pump.fun sells also pass the user accumulator.** It is unknown from our evidence. If they do, sells fill part of `limit` and show as `UNSUPPORTED_ROUTE`. Nothing breaks. *Recommendation:* accept, and read it off the user's first inspection.


## Delivered

2026-10-10: Pump.fun user-buy discovery shipped with accumulator-scoped capture and manifest v3; the user capture contains 10 buys across three tokens. Implementation, deviations, tests and review are recorded above; docs include REAL_DATA_CAPTURE and the accumulator-discovery ADR.
