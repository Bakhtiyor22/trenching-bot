# Pump.fun exact-SOL-in (V3) buy decoding

> Decode Decu's current Pump.fun buys (`buy_exact_quote_in_v3`) alongside the legacy `buy`, keep non-SOL-quoted curves explicitly unsupported, calibrate the V3 curve rule exactly, and make "no supported buy" and deadline stops self-explanatory.

Status: Delivered (approved 2026-10-10; user: "approved, implement it and do question 2 too")
Date: 2026-10-10

## Context

The [bonding-curve buy decoder](../adr/20261003-pumpfun-bonding-curve-buy-decoder.md) supports exactly one route: a Pump.fun instruction with discriminator `66063d1201daebea` (Anchor `global:buy`, exact tokens out). Its "Revisit when" trigger has now fired. The user's console capture of 2026-10-10 (`build/captures/console/08aacc68-…/wallet`, scope `pumpfun-user-buys`, slots 454,995,178–455,115,333) selected 100 transactions and decoded **0** buys. All 100 were `UNSUPPORTED_ROUTE`, so the wallet stage is `UNUSABLE / NO_SOURCE_BUYS` and nothing downstream can start. The earlier capture `decu-pumpfun-buys` (slots ~454.42M, 10 legacy `buy`s) still decodes.

Offline analysis of the captured artifacts (public chain data; no provider calls):

| Finding | Evidence |
|---|---|
| Buys now use `buy_exact_quote_in_v3`, discriminator `e1f7501ed5b38488` = sha256(`global:buy_exact_quote_in_v3`)[0..8] | 81 of 100 transactions |
| Sells use `sell_v3`, `1c92de7726c469d5` | 19 of 100 |
| Every V3 buy is a CPI from router `FLASHX8DrLbgeR8FcfNV1F5krxYcYMUdBkrP1EPBtxB9` | 81/81; `PumpFunInstructionReader` already reads inner instructions |
| Instruction data is 25 bytes: discriminator, `spendable_quote_in` u64, `min_base_amount_out` u64, one trailing byte (observed `01`, not interpreted) | 81/81 |
| The `TradeEvent` prefix through `creator_fee` (offset 233) is **unchanged**. The 405-byte length (vs 390) is only the longer trailing `ix_name` string, `buy_exact_quote_in` vs `buy`; the 117 bytes after it keep their length | All events in both captures |
| 15 buys are on **non-SOL-quoted curves**: `sol_amount` = 0, virtual and real SOL reserves = 0, USDC moves through the wallet, often after a CLMM `SwapV2` (`CAMMCzo5…`) | 15/81 |
| For the 66 SOL-quoted buys: `spendable_quote_in` = `sol_amount` + `fee` + `creator_fee` exactly; `token_amount` ≥ `min_base_amount_out`; the wallet's token change = `token_amount`; the fee and creator fee equal ceil(`sol_amount` × bps / 10000) | 66/66 |
| **V3 curve rule differs by one lamport.** With pre-trade reserves backed out by `sol_amount` (as today), `token_amount` = floor((`sol_amount` − 1) × vT / (vS + `sol_amount` − 1)). The legacy formula over-predicts by about 5–11k raw tokens (~4 ppb) | 66/66 exact under this rule; legacy buys remain exact under the existing rule (10/10) |
| The 66 SOL buys cover 15 distinct mints | Planning would produce 15 market windows |

Market extraction (`PumpFunTradeObservationExtractor`) reads every `TradeEvent` regardless of the instruction. It already handles V3 trades in a window, provided the prefix layout holds, which it does. Non-SOL-quoted events would fail `CurveReserves.isValid` and count as `MALFORMED_EVENT`. A non-SOL mint never gets a window, because its buy is rejected first.

Two smaller usability problems surfaced during the same session:
- The console shows only `UNSUPPORTED_ROUTE ×100`, which does not tell the user *which* instructions were found.
- `MarketHistoryCaptureService` and `WalletHistoryCaptureService` log `category=BUDGET` for every `CaptureBudgetException`. A run that stopped at the 10-minute deadline therefore looks like a request/byte budget failure. `CaptureBudgetException` does not expose its category.

## Scope

### In scope

- Decode SOL-quoted `buy_exact_quote_in_v3` buys signed by the tracked wallet, top-level or by CPI, under the existing evidence rules. The legacy `buy` keeps working unchanged.
- An explicit, counted rejection for non-SOL-quoted curves (an unsupported route, not ambiguous).
- Carry the buy instruction kind into `PumpFunBuyFacts`, and make calibration's curve-math and latency checks apply the right rule per kind.
- A bounded per-capture summary of which Pump.fun instructions the unsupported records contained, shown in the console and the CLI decode output.
- Correct the budget-category logging. Report `stopReason` in CLI output and in the console's operation result.
- An ADR amending the decoder decision.

### Out of scope

- Decoding sells (`sell`, `sell_v3`), PumpSwap/AMM, non-SOL-quoted curves, `buy_exact_quote_in` (non-V3, `c62e1552b4d9e870`) and `buy_v3` (`07051dc4f5176550`). These have no observed evidence in Decu's captures and stay unsupported, but are named in the instruction summary.
- Changing the paper engine's fill formula (see Open Question 1).
- Parsing the `TradeEvent` bytes after `creator_fee`, including the quote-mint fields. Non-SOL quotes are detected from the prefix alone.
- Replay format, dataset schema or database changes.
- Any capture or recapture by the agent. Existing artifacts already contain the full transactions, and decoding is offline.

## Key Decisions

1. **One decoder, two instruction kinds.** `PumpFunBuyDecoder` accepts exactly one Pump.fun buy instruction of either kind, plus exactly one `TradeEvent`. All existing rules 1, 3, 5 and 6 of the decoder ADR still apply. Rule 4 (amount match) becomes per-kind:
   - **legacy:** buy token-amount argument = event `token_amount` (unchanged)
   - **V3:** `spendable_quote_in` = `sol_amount` + `fee` + `creator_fee`, and `token_amount` ≥ `min_base_amount_out`

   *Rationale:* both are the same economic event, a wallet paying SOL into a bonding curve. The V3 check binds the instruction to the event as tightly as the legacy one does.
2. **Same `source`, new decoder version.** Decoded V3 buys keep `source = pumpfun-bonding-curve-buy/v1` and the same `WalletTransaction` representation (`quoteDelta` = −`sol_amount`). `DipEntryStrategy` keys on that source and treats a source buy as a signal. Replay datasets do not depend on the instruction kind, so datasets stay schema v3 and existing ones are unaffected. `WalletHistoryDecoderVersion` gains `PUMPFUN_BONDING_CURVE_BUY_V2` (legacy + V3) and decode results report it. *Rejected:* a new source string. It would silently drop V3 buys from the strategy, or force a strategy and format change for no simulation difference.
3. **Non-SOL quote is an unsupported route.** If `sol_amount` = 0 **and** virtual SOL reserves = 0, the decoder rejects with a new reason, `NON_SOL_QUOTE`, mapped to outcome `UNSUPPORTED_ROUTE`. This check comes before the zero-amount check, so these buys are not misfiled as `AMBIGUOUS`. A zero `sol_amount` with non-zero SOL reserves stays `AMBIGUOUS` (`ZERO_AMOUNT`).
4. **Instruction kind is a fact.** `PumpFunBuyFacts` gains `instruction: PumpFunBuyInstruction` (`BUY`, `BUY_EXACT_QUOTE_IN_V3`). Calibration computes tokens with the kind's rule:
   - legacy: floor(L × vT / (vS + L))
   - V3: the same with L − 1

   In both cases the pre-trade reserves are backed out by `sol_amount`. Calibration reports the kind per buy and counts it in the summary. Measured math can therefore stay "exact" for both kinds; it does not report a 4-ppb mismatch on every V3 buy.
5. **The one-lamport rule is an observed rule, not a program-source fact.** The ADR records it as "observed exact on 66/66 buys; revisit if calibration reports a V3 math mismatch". Calibration is the standing check.
6. **Instruction summary from the existing reader.** `PumpFunInstructionReader` already classifies data; it gains named kinds for the known discriminators listed under Out of scope. The decode result keeps a bounded count map from instruction name to the number of unsupported records containing it (at most 9 keys: the known names, `OTHER_PUMPFUN` and `NO_PUMPFUN`). It also keeps a per-rejection-reason count, so `NON_SOL_QUOTE` is visible. No raw bytes are stored.
7. **Budget categories are surfaced, not reinterpreted.** `CaptureBudgetException` exposes `val category` (existing values: `DEADLINE`, `REQUESTS`, `BYTES`, `RESPONSE_BYTES`). The four `category=BUDGET` log lines log it instead. CLI results print `stopReason=<category>`. A deadline stop also prints that re-running the same command resumes. The console's operation code keeps `BUDGET_EXHAUSTED` but adds the category to the safe message. No limit, deadline or retry behaviour changes.

## Module Contracts

Additive except where marked.

- `protocol.pumpfun.PumpFunInstruction` (public sealed interface):
  - add `BuyExactQuoteInV3(topLevelIndex: Int, spendableQuoteIn: BigInteger, minBaseAmountOut: BigInteger)`
  - add `Known(topLevelIndex: Int, name: PumpFunInstructionName)` for recognised but unsupported instructions (`SELL`, `SELL_V3`, `BUY_EXACT_QUOTE_IN`, `BUY_V3`)
  - `Other` stays for unknown data

  Exhaustive `when`s in `PumpFunTradeObservationExtractor` and the decoder must handle the new subtypes. This is a source change in two modules, not a behaviour change for market extraction (both new subtypes map to "no observation").
- `protocol.pumpfun.PumpFunLayout`: add `BUY_EXACT_QUOTE_IN_V3_DISCRIMINATOR`, `BUY_EXACT_QUOTE_IN_V3_DATA_BYTES = 24` (minimum; trailing bytes ignored) and the discriminators of the named unsupported instructions.
- `walletactivity.PumpFunBuyFacts`: add `instruction: PumpFunBuyInstruction` (new public enum in `walletactivity`). Constructors in tests and fixtures must pass it. This is a source break inside this repo only.
- `walletactivity.WalletHistoryDecoderVersion`: add `PUMPFUN_BONDING_CURVE_BUY_V2`. The decoder reports V2; V1 stays for reading existing reports.
- `walletactivity.WalletHistoryDecodeResult`: add `unsupportedInstructions: Map<String, Int>` and `rejections: Map<String, Int>` (bounded, sorted keys).
- `chaincapture.CaptureBudgetException`: `category` becomes a public `val`.
- `replay.domain` calibration report: add per-buy `instruction` and a summary `instructions: Map<String, Int>`. The calibration JSON gains two fields. It stays deterministic, and existing consumers (the console's `CalibrationResults`) must tolerate them.
- Console: `CaptureInspectionMapper` adds the two maps to `walletSummary` (prefixed `instruction.`, `rejection.`). The `NO_SOURCE_BUYS` guidance text names the found instructions. No API shape change: `walletSummary` is already a string map.

## Domain Rules

1. A transaction with exactly one SOL-quoted `buy_exact_quote_in_v3` by the tracked wallet decodes to one `WalletTransaction`. Its `instructionIndex` is the top-level index containing the CPI, `baseDelta` = +`token_amount`, `quoteDelta` = −`sol_amount`, `source = pumpfun-bonding-curve-buy/v1`. Fixture: a sanitized copy of one of the 66 captured transactions.
2. V3 data shorter than 24 bytes is `MALFORMED_EVIDENCE` (→ `AMBIGUOUS`).
3. `spendable_quote_in` ≠ `sol_amount` + `fee` + `creator_fee`, or `token_amount` < `min_base_amount_out`, is `AMOUNT_MISMATCH` (→ `AMBIGUOUS`). Arithmetic is BigInteger; no u64 overflow is possible.
4. A transaction with one legacy `buy` and one V3 buy, or two of either, is `MULTIPLE_BUYS`.
5. `sol_amount` = 0 with virtual SOL reserves = 0 is `NON_SOL_QUOTE` (→ `UNSUPPORTED_ROUTE`). `sol_amount` = 0 with non-zero reserves stays `ZERO_AMOUNT` (→ `AMBIGUOUS`).
6. V3 with `is_buy` = 0, another user, or a mismatched top-level instruction is rejected exactly as for legacy (`SELL_EVENT`, `OTHER_USER`, `INSTRUCTION_MISMATCH`).
7. `sell_v3`, `sell`, `buy_exact_quote_in` and `buy_v3` alone are `NO_BUY` → `UNSUPPORTED_ROUTE`, and their names are counted in `unsupportedInstructions`.
8. Legacy decoding is unchanged byte for byte. Re-decoding `decu-pumpfun-buys` yields the same 10 `WalletTransaction`s and facts (plus `instruction = BUY`), and the same exported dataset checksum.
9. Calibration, legacy: tokens = floor(L·vT/(vS+L)). V3: tokens = floor((L−1)·vT/(vS+L−1)). In both, L = `sol_amount` and the pre-trade reserves come from post-trade reserves backed out by `sol_amount` and `token_amount`, as today. Fee checks are unchanged: ceil, existing `BondingCurve.fee`. Latency prediction uses the same per-kind rule against the prior observation's reserves. Error bps keeps scale 2, HALF_EVEN.
10. For the 66 captured V3 buys (fixture subset), calibration reports math exact.
11. The instruction and rejection count maps are bounded (≤ 9 and ≤ 15 keys), sorted, and contain only enum names and integers.
12. Decoding stays offline and deterministic. No `Clock` or id generator is involved; the decoder version string is the only identity change.
13. `CaptureBudgetException.category` is logged as given. A deadline stop logs `category=DEADLINE`, and the CLI prints `stopReason=DEADLINE`. Request/byte stops print their own category.

## Data Model

No schema change. No entity, table or production SQL. Capture manifests and evidence hashes are untouched. Decoding is recomputed from artifacts, so no recapture is needed.

## Transactions & Integration

No provider calls, no new configuration and no transaction boundaries. Existing captures re-decode offline after delivery: the console's Inspect evidence button and the CLI `inspectWalletHistory` / `captureReplayMarkets`. The router fee and tips paid through FLASHX remain in calibration's "other lamports" residual, as the decoder ADR already documents.

## What To Implement

1. **Reader and layout** (`protocol.pumpfun`): V3 and named-instruction subtypes, discriminators, minimum lengths. Handle the subtypes in the market extractor's `when` with no behaviour change.
2. **Decoder** (`walletactivity`): per-kind amount checks, `NON_SOL_QUOTE`, facts `instruction`, decoder version V2, the instruction/rejection summaries in `WalletHistoryDecodeResult`, and `inspectWalletHistory` output lines. Depends on 1.
3. **Calibration** (`replay`): per-kind curve rule in the math and latency checks, the report fields, and summary counts. Depends on 2. Can run in parallel with 4.
4. **Console and logging**: inspection summary entries and guidance text, `CalibrationResults` instruction column, `CaptureBudgetException.category`, the four log lines, CLI `stopReason`, and the console operation message. Independent of 2–3 except for the summary keys.
5. **ADR, documentation and review.**

## Verification

- `PumpFunInstructionReaderTest`: V3 data parsed (u64 little-endian, trailing byte ignored); short V3 is `Malformed`; each named discriminator maps to its name; unknown data is `Other`.
- `PumpFunBuyDecoderTest` (fixture: one sanitized captured V3 SOL buy, one captured non-SOL V3 buy, one `sell_v3`):
  - V3 decodes to the expected values
  - `spendable_quote_in` off by 1 → `AMOUNT_MISMATCH`
  - `min_base_amount_out` > `token_amount` → `AMOUNT_MISMATCH`
  - legacy + V3 → `MULTIPLE_BUYS`
  - non-SOL → `NON_SOL_QUOTE` / `UNSUPPORTED_ROUTE`
  - zero SOL with reserves → `ZERO_AMOUNT`
  - `sell_v3` → `NO_BUY`, counted as `SELL_V3`
  - all legacy tests unchanged
- `RecordedWalletHistoryTest`: decode result reports V2, the instruction counts and the rejection counts. The legacy `decu`-style fixture re-decodes identically.
- `ExecutionCalibrationTest`: V3 hand-computed example where L−1 gives the event tokens and L over-predicts. The math check is exact for V3 and a deliberately wrong token amount is a mismatch. Legacy examples unchanged. The report JSON includes `instruction` and is byte-stable across runs.
- `MarketHistoryCaptureServiceTest` / `WalletHistoryCaptureServiceTest`: a deadline stop logs and returns category `DEADLINE` and the CLI prints `stopReason=DEADLINE`. A request-budget stop prints its own category.
- `PumpFunTradeObservationExtractorTest`: a V3 `TradeEvent` (405 bytes) yields the same observation as a legacy event with the same prefix.
- Frontend: `CapturesPage.test.tsx` shows the instruction summary and the `NO_SOURCE_BUYS` guidance naming `BUY_EXACT_QUOTE_IN_V3`/`SELL_V3`. `CalibrationResults` renders the instruction column.
- Commands: `JAVA_HOME=C:/Users/user/.jdks/ms-21.0.10 ./gradlew.bat build --offline`; `npm test`, `npm run lint`, `npm run build` in `frontend`.
- Fixtures are sanitized transaction JSON copied from the user's captures: public signatures and accounts, no keys. No live provider call in any test.

## Documentation Sync

- New ADR `docs/adr/<date>-pumpfun-exact-quote-in-v3-buys.md`, amending the decoder ADR. It covers the evidence table, the V3 rule, non-SOL rejection, the unchanged source, the one-lamport rule and revisit triggers. Add a "Superseded in part by" line to the decoder ADR.
- `REAL_DATA_CAPTURE.md`: the supported routes, the instruction summary lines, `stopReason`, and "a deadline stop is resumed by re-running the same command".
- `REPLAY_FORMAT.md`: note that `source` covers both buy instructions.
- `IMPLEMENTATION_STATUS.md` and `HANDOFF.md`.

## Acceptance Criteria

- Re-inspecting console session `08aacc68-…` (offline) reports wallet stage `READY`, decoder `PUMPFUN_BONDING_CURVE_BUY_V2`, `buys` 66, `rejection.NON_SOL_QUOTE` 15, `instruction.SELL_V3` 19, and 15 planned market windows.
- Re-inspecting `decu-pumpfun-buys` gives the same 10 buys. Re-exporting the existing complete dataset gives the identical checksum.
- `calibrateExecution` on a V3 fixture reports `mathExact` for every V3 buy and lists `instruction: BUY_EXACT_QUOTE_IN_V3`.
- A deadline-stopped `captureReplayMarkets` prints `stopReason=DEADLINE` and logs `category=DEADLINE`.
- No schema, replay-format, strategy-default or engine change; backend and frontend suites pass.

## Open Questions

1. **Should paper fills model the V3 one-lamport rule?** The engine computes entry tokens with the legacy formula (curve-execution ADR). Real V3 buys receive ~4 ppb fewer tokens, the value of one lamport. **Recommend no change:** it is far below any P&L precision of interest, and changing engine semantics would bump `dip-paper-v2`. Calibration will keep reporting it per buy.
2. **Should the capture deadline be configurable from the CLI/console?** Today each run stops at 10 minutes (the existing maximum is 30). Your 3-window market capture needs ~4 runs; the 15 windows from the new sample will need many more. **Recommend a separate small change:** an optional `-PoperationTimeoutMinutes` (1–30) and an Advanced console field, default unchanged at 10. It is not part of this plan, so the decoder ships independently.
3. **Smaller first sample for the V3 wallet?** 66 buys across 15 tokens means 15 windows of market capture, likely tens of thousands of transactions. **Recommend** a console session with sample size 10–20 for the first end-to-end run, then widen. This is a usage choice, not a code change.

## Amendment A: configurable operation deadline (approved with Open Question 2)

- Capture CLI commands (`captureWalletHistory`, `captureReplayMarkets`, `captureReferenceRates` where it uses the RPC transport) accept an optional `--operation-timeout-minutes` (Gradle `-PoperationTimeoutMinutes`), 1–30, default 10 (the existing `SolanaRpcSettings` default and maximum). Values outside 1–30 are refused before any provider call.
- The console operation request gains an optional `operationTimeoutMinutes` (same bounds, default 10). It is shown in Advanced settings with the request/byte allowances, and in the effective-settings line before Start. It is part of the operation's request fingerprint.
- The timeout applies per explicit invocation, like the request/byte allowance. It is not saved in manifests and does not change resume identity.
- Tests: bounds validation (CLI parser and console validation), and the configured duration reaching the transport settings. No deadline behaviour changes beyond its length.

## Delivered

2026-10-10: all five units and Amendment A shipped. The ADR is [exact-SOL-in (V3) buys](../../adr/20261010-pumpfun-exact-quote-in-v3-buys.md); the decoder ADR carries an amendment line.

- **Decoding:** `PumpFunInstructionReader` reads `buy_exact_quote_in_v3` and names `sell`, `sell_v3`, `buy_exact_quote_in` and `buy_v3`. `PumpFunBuyDecoder` applies the per-instruction amount binding and the `NON_SOL_QUOTE` rejection. `PumpFunBuyFacts.instruction` carries the buy kind, the decoder version is `PUMPFUN_BONDING_CURVE_BUY_V2`, and decode results count rejection reasons and rejected instructions (CLI `rejection.*` / `rejectedInstruction.*`, console wallet summary).
- **Calibration:** `BondingCurve.buyExactQuoteIn` prices on `sol_amount` − 1. Calibration applies each instruction's rule in its math and latency checks, and reports `instruction` per buy plus summary counts. The console labels each calibrated buy with its instruction.
- **Stops:** `CaptureBudgetException.category` is public. Capture logs give the real category, capture results carry `stopReason`, the CLI prints `stopReason` with a resume hint after `DEADLINE`, and console operations record it as their code and message.
- **Amendment A:** `-PoperationTimeoutMinutes` / `--operation-timeout-minutes` (1–30, default 10) and the console's "Operation deadline (minutes)" setting travel on the capture request into `CaptureBudget`, which overrides the transport's default deadline for that invocation only. Manifests and resume identity are unchanged.

Fixtures: three of the user's captured transactions (a SOL-quoted V3 buy, a non-SOL V3 buy and a `sell_v3`), public chain data only. `REPLAY_FORMAT.md` needed no change because it does not describe `source` values.

Verification:
- `gradlew build --offline` passed 553 tests with zero failures or errors.
- Frontend `npm test` passed 119 tests; `npm run lint` and `npm run build` passed.
- Offline `inspectWalletHistory` on the user's console capture `08aacc68-…/wallet` now reports `decoderStatus=PUMPFUN_BONDING_CURVE_BUY_V2`, `decode.DECODED=66`, `rejection.NON_SOL_QUOTE=15`, `rejection.NO_BUY=19`, `rejectedInstruction.SELL_V3=19` and `rejectedInstruction.BUY_EXACT_QUOTE_IN_V3=15`.
- `decu-pumpfun-buys` still decodes its 10 legacy buys.
- No provider call was made. The market capture directory the user is extending (`decu-dip-markets`) was not touched, so the re-export checksum comparison is left to the user once that capture and its rates are complete.

