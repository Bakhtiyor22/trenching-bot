# Bonding-Curve Execution Model

> Paper fills that pay what a real Pump.fun trade would: our own price impact from the curve's reserves, the fees the program actually charged, a fixed SOL cost per transaction and a configurable latency, plus an offline check of the model against Decu's real buys.

Status: Approved (2026-10-09, with every Open Question's recommendation)

## Context

The [dip-entry strategy](../../adr/20261008-dip-entry-strategy.md) (engine `dip-paper-v1`) fills each entry and exit at a captured trade's price:
- **Entries:** [FillModel](../../../src/main/kotlin/com/trenching/bot/papertrading/application/FillModel.kt) fills at the trade's average price plus a flat `slippageBps` (default 50).
- **Exits:** [PositionService](../../../src/main/kotlin/com/trenching/bot/portfolio/application/PositionService.kt) sells at the price minus the same slippage.
- **Fees:** both charge a flat `feeBps` (default 10).
- **Recorded as zero:** price impact and priority fee. `PaperFill.priceImpactBps` and `PaperFill.priorityFee` exist, but both are always 0.
- **Liquidity:** captured Pump.fun observations have no `liquidityQuote`, so the liquidity check never applies to real data.

This misstates a bonding curve in three ways:
- **Impact.** The price moves with the square of the curve's virtual SOL. Near a $10,000 market cap (about 40 SOL of virtual reserves), the average price paid rises by roughly 0.25% at 0.1 SOL, 2.5% at 1 SOL, and 250% at 100 SOL. A 100 SOL buy would exceed the curve. The default notional was lowered to 0.1 SOL on 2026-10-09 for this reason.
- **Fees.** Pump.fun charged about 1% historically, with a protocol fee plus a creator fee. The model charges 0.1%.
- **Fixed costs.** At 0.1 SOL, the network fee plus priority fee or tip can be several percent per round trip. The model charges nothing.

Every captured trade's `TradeEvent` already carries what an exact model needs:
- the curve's virtual and real reserves after the trade;
- the protocol and creator fee basis points.

[PumpFunInstructionReader](../../../src/main/kotlin/com/trenching/bot/protocol/pumpfun/PumpFunInstructionReader.kt) reads only up to `real_token_reserves`. The event prefix it already requires (233 bytes, through `creator_fee`) contains the remaining fields. Captures store raw transactions, so **no recapture is needed**; a re-export picks the new fields up.

## Scope

### In scope

1. **Curve math.** Entry tokens out and exit SOL out are computed with Pump.fun's constant-product formula, in raw integer units, against the fill trade's post-trade reserves. Our own price impact follows from that. An entry that would buy more than the curve's real token reserves is rejected. An exit cannot take more SOL than the curve's real SOL reserves.
2. **Real fees.** The protocol and creator fee basis points come from the fill trade's `TradeEvent`. They replace `feeBps`.
3. **Fixed transaction cost.** A configurable SOL amount is charged on each entry and each exit, covering the network fee plus priority fee or tip.
4. **Latency.** A configurable `latencySlots` (default 1, today's behaviour): an entry or exit fills at the first eligible trade at least that many slots after its trigger.
5. **Calibration.** An offline command, `calibrateExecution`, measures the model against Decu's real buys and reports the error. It reads the wallet and market captures, with no network access.
6. **Dataset v3.** Observations carry their curve state. Engine `dip-paper-v2`. The console shows fees, price impact and the transaction cost per fill and close.

### Out of scope

- **Other traders' reactions to our trade.** These are counterfactual, so the replay cannot know them. At 0.1 SOL our trade moves the price about 0.5%; this is documented as an accepted limit.
- **Failed transactions and a slippage tolerance.** Open Question 3.
- **Token-account rent.** About 0.00204 SOL, refunded when the account closes after the sale. It is documented, not charged (Open Question 6).
- **PumpSwap (post-migration) execution.** Migration still ends the cycle, as in `dip-paper-v1`.
- **Live execution of any kind.** Solana stays read-only.

## Key Decisions

1. **Integer curve math in raw units, in a shared protocol object.**
   - `protocol/pumpfun/BondingCurve` computes on lamports and raw token units with `BigInteger`, rounding down (floor) like the program. It is the only place curve arithmetic exists.
   - Raw to decimal conversion happens once: lamports / 10^9, and raw tokens / 10^6. 6 is the Pump.fun token decimals already enforced by `UNSUPPORTED_SUPPLY`.
   - Rationale: exact reproduction of the program, with no rounding drift. Calibration check (a) verifies it to the raw unit.
2. **A fill executes against the post-trade state of its fill trade.**
   - The fill trade is the first eligible trade at slot ≥ trigger slot + `latencySlots`.
   - Meaning: our transaction lands behind the first trade we see in that slot.
   - Rationale: transaction order within a slot is not recorded (dip-entry ADR). This keeps the next-trade fill rule and adds our own impact on top of the other trade's.
   - The alternative, the pre-trade state, would assume we land first in the slot, which is optimistic. See Open Question 1.
3. **Fees are read from the fill trade's event, not configured.**
   - `feeBps` and `slippageBps` leave `SimulationConfig`. Impact comes from the curve, and fees from the program.
   - The fill still records the fee basis points it applied (protocol + creator), with `slippageBps = 0`, so provenance is unchanged in shape.
4. **Fixed cost per transaction, `transactionCostSol`.**
   - Charged on each entry (into the cost basis) and each exit (out of the net proceeds).
   - Recorded on the fill as `priorityFee` (the existing column) and on the close as `transactionCost` (new).
   - Recommended default: 0.001 SOL, until calibration measures Decu's real cost (Open Question 2).
5. **`latencySlots` replaces the hard-coded "later slot".**
   - Bounds 1–150 (about a minute); the default 1 keeps today's rule.
   - The same latency applies to the exit: the first eligible trade at slot ≥ exit trigger slot + `latencySlots`.
6. **The triggers are unchanged:** dip, take profit, stop loss, market cap and migration (dip-entry ADR).
   - They still compare each trade's observed price with the levels.
   - Take profit and stop loss are measured from Pe. Pe is now the curve execution price, which includes our impact and excludes fees.
7. **Dataset v3 replaces v2.**
   - Observations gain a required `curve` object, covered by the checksum. v2 is refused (`UNSUPPORTED_SCHEMA`), as v1 was.
   - **This is a contract break of the replay format.** It is cheap now: no real v2 dataset exists yet, and every capture re-exports.
8. **Engine `dip-paper-v2`.**
   - Strategy `dip-entry` stays v1, since its rules do not change; only execution does.
   - The configuration hash adds the curve rule, latency and transaction cost, and drops fee and slippage.
9. **Calibration lives in `replay`, beside `exportRecordedReplay`.** It already combines the wallet and market captures offline. `walletactivity` exposes the facts of each decoded buy additively (below).

## Module Contracts

**`protocol.pumpfun` (shared, additive)**
- `PumpFunLayout`: offsets for `virtual_sol_reserves` (105), `virtual_token_reserves` (113), `real_sol_reserves` (121), `fee_basis_points` (169), `fee` (177), `creator_fee_basis_points` (217) and `creator_fee` (225), all u64 and inside the existing 233-byte prefix.
- `PumpFunInstruction.TradeEvent` adds `virtualSolReserves`, `virtualTokenReserves`, `realSolReserves` and `feeLamports` / `creatorFeeLamports` (all `BigInteger`), plus `feeBasisPoints` / `creatorFeeBasisPoints` (`BigInteger`, validated to 0–10,000 by consumers).
- `BondingCurve` (object):
  - `buy(reserves: CurveReserves, lamportsIn: BigInteger): CurveBuy?`, which returns `tokensOut`, or null when `tokensOut` > real token reserves or the input is not positive;
  - `sell(reserves: CurveReserves, tokensIn: BigInteger): CurveSell?`, which returns `lamportsOut`, or null when `lamportsOut` > real SOL reserves or the input is not positive;
  - `fee(lamports: BigInteger, basisPoints: BigInteger): BigInteger`, rounded up;
  - `spotPrice(reserves): BigDecimal`, in SOL per whole token at `PRICE_SCALE` HALF_EVEN;
  - `CurveReserves(virtualSol, virtualToken, realSol, realToken)` as `BigInteger`, with invariants: virtual > 0, real ≥ 0, real ≤ virtual.

**`marketdata` (public contract, changed)**
- `domain/CurveState(reserves: CurveReserves, feeBps: Int, creatorFeeBps: Int)`.
- `MarketObservation.curve: CurveState` is required. `curveComplete` stays and must equal `realToken == 0`.
- `PumpFunTradeObservationExtractor` fills it from the event. A fee basis-point field outside 0–10,000 is excluded as `MALFORMED_EVENT`.

**`strategy` (public contract, changed: breaking for the console API)**
- `SimulationConfig` drops `feeBps` and `slippageBps`, and adds `latencySlots: Long` (1–150) and `transactionCostSol: BigDecimal` (0–0.1 SOL, scale 12).
- `notional` must be a whole number of lamports (at most 9 decimals).
- `mvp()` = notional 0.1, maximum age 2, dip 1500, take profit 3000, stop loss 1500, market cap 10,000, horizon 4500, latency 1, transaction cost 0.001.
- `SimulationConfigBounds` and `SimulationConfigLimits` follow.

**`papertrading` (changed)**
- `FillModel.fill(intent, source, config)` keeps its signature and switches to the curve.
- `PaperFill` semantics:
  - `executionPrice` = lamports in / tokens out;
  - `quantity` = tokens out;
  - `fee` = protocol + creator fees in SOL;
  - `feeBps` = the sum applied;
  - `slippageBps` = 0;
  - `priceImpactBps` = execution versus pre-fill spot;
  - `priorityFee` = `transactionCostSol`;
  - `costBasis` = notional + fee + transaction cost.
- `RejectionReason` adds `EXCEEDS_CURVE`: the entry would buy more than the curve's real token reserves. It belongs to `ENTRY`.

**`portfolio` (changed)**
- `PositionClose` adds `transactionCost: BigDecimal`, `priceImpactBps: Int` and `feeBps: Int`.
- A trade whose curve cannot pay the sale is skipped, like today's liquidity check. If none can, the position stays open as `NO_EXIT_FILL`.

**`walletactivity` (public contract, additive)**
- `WalletHistoryDecodeResult` adds `buyFacts: List<PumpFunBuyFacts>`, one per decoded buy, with:
  - `signature`, `slot`, `mint`;
  - `solAmount`, `tokenAmount`, `feeLamports`, `creatorFeeLamports`, `feeBps`, `creatorFeeBps`;
  - `postReserves: CurveReserves`;
  - `transactionFeeLamports` (`meta.fee`);
  - `walletLamportDelta` (the signer's post minus pre balance).
- The existing outputs are unchanged.

**`replay` (changed)**
- `ReplayDataset` v3 (`SCHEMA_VERSION = 3`), loader, writer and `DatasetFingerprint`.
- `ReplayRunner` engine `dip-paper-v2`.
- `ExecutionCalibrationService.calibrate(walletCaptureDirectory, marketCaptureDirectory): CalibrationReport`.
- Gradle task `calibrateExecution` (`-PwalletCaptureDirectory`, `-PmarketCaptureDirectory`, `-Preport`).
- `SampleDataset.DIP_SAMPLE_V3` replaces `DIP_SAMPLE_V2`.

**`console` (changed, breaking for the frontend in this repo)**
- The config request, defaults and limits drop `feeBps` and `slippageBps`, and add `latencySlots` and `transactionCostSol`.
- `FillResponse` already has `priorityFee` and `priceImpactBps`.
- `CloseResponse` adds `transactionCost`, `priceImpactBps` and `feeBps`.
- The USDT view converts `transactionCost` like the other amounts.

## Domain Rules

1. **Curve formulas (raw units, floor):**
   - `tokensOut = floor(lamportsIn × vToken / (vSol + lamportsIn))`;
   - `lamportsOut = floor(tokensIn × vSol / (vToken + tokensIn))`.
   - Products use `BigInteger`, so u64 × u64 never overflows.
   - Negative case: a non-positive input returns null; a reserve that breaks the invariants fails dataset load (`CURVE_STATE_INVALID`).
2. **Entry:**
   - `lamportsIn = notional × 10^9`, exact by rule 9.
   - Fees: `fee = ceil(lamportsIn × feeBps / 10^4)` and `creatorFee = ceil(lamportsIn × creatorFeeBps / 10^4)`.
   - The fees are paid on top of `lamportsIn`, as today ("fees are additional to entry notional").
   - `costBasis = notional + fees + transactionCostSol` at scale 12.
3. **Entry beyond the curve:** an entry whose `tokensOut` exceeds the fill trade's real token reserves is rejected with `EXCEEDS_CURVE`. It is never partially filled.
4. **Fill state:** the reserves used are the fill trade's post-trade reserves (Key Decision 2). The fill references that trade's observation id, so the fill can be reproduced by hand from the stored observation.
5. **Exit:**
   - `lamportsOut` comes from rule 1 with `tokensIn` = the position's raw quantity.
   - The fees use the exit trade's basis points, rounded up.
   - `net = lamportsOut − fees − transactionCostSol`, and P&L = `net − costBasis`, at scale 12.
   - A trade whose `lamportsOut` exceeds its real SOL reserves is skipped.
   - A net that is zero or negative still closes, with negative P&L; it is never dropped.
6. **Prices and impact:**
   - Execution prices are SOL per whole token at `PRICE_SCALE` HALF_EVEN.
   - `priceImpactBps = round((executionPrice / spotBefore − 1) × 10^4)`, HALF_EVEN. It is positive for a buy and negative for a sell, where `spotBefore` is the fill state's spot price.
7. **Latency:**
   - Entry: the fill trade is the first eligible trade with slot ≥ trigger slot + `latencySlots`, within the horizon and before the curve completes.
   - Exit: the same, from the exit trigger.
   - Negative case: no such trade gives `NO_FILL_AFTER_TRIGGER` for an entry and `NO_EXIT_FILL` for an exit, as today.
8. **Decimals:** curve math requires base decimals 6. Any other value gives `UNSUPPORTED_SUPPLY`, as today.
9. **Configuration bounds:**
   - Notional: at most 9 decimals, and at least 1 lamport.
   - `transactionCostSol`: 0–0.1 at scale 12.
   - `latencySlots`: 1–150.
   - Violations are per-field 400s in the console and `IllegalArgumentException` in `SimulationConfig.of`.
10. **Dataset v3:**
    - Every observation has `curve` with six raw integer strings: four reserves plus two fee basis points.
    - Load refusals:
      - `CURVE_STATE_INVALID`: missing, negative, real above virtual, virtual zero, or basis points above 10,000;
      - `CURVE_STATE_INCONSISTENT`: `curveComplete` differs from `realToken == 0`;
      - `UNSUPPORTED_SCHEMA`: version 1 or 2.
11. **Determinism:**
    - No clock or network is used in a run or a calibration.
    - The configuration hash includes latency, transaction cost and the rule text "curve-post-trade-floor-fees-ceil".
    - The result checksum adds impact, fees and transaction cost per fill and close.
    - Starting the same run twice returns the stored run, as today (run id = engine + dataset + configuration).
12. **Calibration (offline, deterministic):**
    - (a) **Math check, for every decoded buy.** The pre-trade reserves are the post reserves minus the buy's own deltas. From them and the buy's `solAmount`, `BondingCurve.buy` must reproduce `tokenAmount` exactly, and `fee` must reproduce `feeLamports` and `creatorFeeLamports`. Each mismatch is counted and listed.
    - (b) **Latency check, for buys with a captured trade of the same mint in an earlier slot.** The prediction uses the newest such trade's post reserves and the buy's `solAmount`. The report gives the error `(actual − predicted) / predicted` in bps at scale 2 HALF_EVEN. Decu's first buy of each mint has no earlier captured trade, since windows start at S+1, and is reported as `NO_PRIOR_STATE`.
    - (c) **Costs.** For each buy, the report gives `transactionFeeLamports` and an "other SOL" residual: −`walletLamportDelta` − `solAmount` − fees − `transactionFeeLamports`. The residual covers tips and account rent.
    - The summary gives counts, medians and maxima. Medians come from sorted `BigDecimal`s, deterministically.

## Data Model

The changes are additive and nullable; they come from entity changes plus a production SQL file, `docs/schema/V20261009120000__bonding_curve_execution.sql`.

- **`market_observations`:**
  - `virtual_sol_reserves`, `virtual_token_reserves`, `real_sol_reserves` and `real_token_reserves`, each `numeric(20,0)`;
  - `fee_bps` and `creator_fee_bps`, each `integer`.
  - All are nullable, like `curve_complete`. The mapper refuses a null row, since only rows from before the reset lack them.
  - The CHECK constraints in the SQL file cover non-negative values, real ≤ virtual, and bps 0–10,000.
- **`positions`:** `close_transaction_cost numeric(38,12)`, `close_price_impact_bps integer` and `close_fee_bps integer`, all nullable. The CHECK requires them to be non-null exactly when `close_slot` is set.
- **`paper_fills`:** no change. `priority_fee`, `price_impact_bps`, `fee_bps` and `slippage_bps` already exist.
- **`simulation_runs`:** no change. The configuration lives in `configuration_snapshot`.

Nothing is dropped or renamed. Old dip-paper-v1 snapshots name `feeBps` and `slippageBps`, and the new configuration reader would refuse them, so **the local database is reset again after delivery** (Open Question 5). Rollback: revert the code. The new columns are nullable and unused by v1.

## Transactions & Integration

- Transaction boundaries are unchanged: a run persists atomically, as today.
- There are no provider calls. Calibration and runs read local captures and datasets only. Nothing here touches Solana or Binance; the user's existing capture covers everything.
- No new configuration properties. The model parameters are per-run configuration, not application properties.

## What To Implement

1. **Curve reading and math** (`protocol.pumpfun`).
   - Layout offsets, the wider `TradeEvent` and `BondingCurve`.
   - Proven against the captured fixture `pumpfun-trade-453004997.json`: its pre-trade reserves and `solAmount` reproduce its `tokenAmount` and fees.
2. **Curve state on observations** (`marketdata`).
   - `CurveState`, the extractor, the entity columns and the mapper.
   - Depends on 1.
3. **Dataset v3** (`replay`).
   - Loader, writer, fingerprint, export and the new refusals.
   - `dip-sample-v3`, with synthetic reserves consistent with its prices and hand-checked P&L for the take-profit and stop-loss cycles.
   - Depends on 2.
4. **Curve execution** (`strategy`, `papertrading`, `portfolio`).
   - Configuration changes, the entry and exit math, latency, transaction cost, `EXCEEDS_CURVE`, the close fields, and engine `dip-paper-v2`.
   - Depends on 1 and 3.
5. **Buy facts and calibration** (`walletactivity`, `replay`).
   - `PumpFunBuyFacts`, `ExecutionCalibrationService`, the `calibrateExecution` command and its report.
   - Depends on 1. It can run in parallel with 3–4, since it touches different files.
6. **Console and frontend.**
   - DTOs, validator and limits; the run form (latency and transaction cost in, fee and slippage out); cycle cards and tables showing fees, impact and transaction cost; mocks.
   - Depends on 4.
7. **Docs:** an ADR, the README, `REPLAY_FORMAT`, `REAL_DATA_CAPTURE` (calibration command), `IMPLEMENTATION_STATUS` and the production SQL.

## Verification

- **`BondingCurveTest`:**
  - reproduces the fixture trade exactly (tokens and both fees);
  - floor rounding on buy and sell, and ceiling rounding on fees;
  - u64-maximum reserves without overflow;
  - null above the real reserves and for a zero input;
  - a buy then a sell of the same tokens returns less SOL (no free round trip).
- **`CurveStateExtractionTest`:** reserves and basis points come from the fixture event; an out-of-range basis point gives `MALFORMED_EVENT`.
- **`FillModelTest`:**
  - impact bps at 0.1 SOL against 40 SOL of virtual reserves;
  - the fees use the fill trade's basis points;
  - the cost basis includes the transaction cost;
  - latency 1 versus 3 picks different trades;
  - `EXCEEDS_CURVE`;
  - decimals other than 6 give `UNSUPPORTED_SUPPLY`.
- **`PositionServiceTest`:**
  - the exit uses the exit trade's reserves and basis points;
  - a trade that cannot pay the sale is skipped;
  - the transaction cost is deducted;
  - a net at or below zero still closes;
  - exit latency.
- **`ReplayDatasetLoaderTest`:** v2 is refused; `CURVE_STATE_INVALID` and `CURVE_STATE_INCONSISTENT` cases; the checksum changes when any curve field changes.
- **`ReplayRunnerTest` / `DipSample`:** the hand-checked v3 values for take profit and stop loss, and every other outcome as before.
- **`SimulationConfigTest`, `ConsoleConfigRequestTest`:** the new bounds; lamport precision; fee and slippage are rejected as unknown fields.
- **`ExecutionCalibrationTest`:**
  - on a decoder wallet fixture, the math check matches exactly;
  - on synthetic captures, the latency error bps and the cost residual are hand-checked;
  - `NO_PRIOR_STATE` for a first buy.
- **Integration tests** (Testcontainers):
  - console, run lifecycle and USDT tests on the v3 sample;
  - persisted fills and closes round-trip impact, fees and transaction cost;
  - unchanged row counts.
- **Frontend Vitest:** the form fields, the cycle card values and the mocks.
- **Commands:**
  - `./gradlew test`;
  - `npm run test`, `npm run lint` and `npm run build` in `frontend/`;
  - `.\gradlew.bat calibrateExecution ...` on the user's captures, run by the user.

## Documentation Sync

- **New ADR `20261009-bonding-curve-execution.md`:**
  - post-trade state, floor and ceiling rounding, fees from events, transaction cost, latency;
  - the accepted counterfactual limit;
  - rent not charged;
  - revisit triggers: calibration error above an agreed bound, or the program's fee formula changing.
- **Notes in the dip-entry ADR:** the execution part is superseded; the triggers are unchanged.
- **README:** the sample table and the model paragraph.
- **`REPLAY_FORMAT`:** v3 `curve`, refusals and the hash.
- **`REAL_DATA_CAPTURE`:** the calibration command and how to read its report.
- **`IMPLEMENTATION_STATUS`.**
- **`WEEKLY_EXECUTION_PLAN` §9:** the execution model is decided.

## Acceptance Criteria

- With the defaults, the bundled v3 sample's take-profit fill records:
  - an impact above 0 bps;
  - fee basis points equal to the fill trade's protocol + creator basis points;
  - `priorityFee` = 0.001.
- Its P&L equals the hand-checked value in `DipSample`.
- A synthetic 100 SOL entry on a curve near a $10,000 market cap is rejected with `EXCEEDS_CURVE`.
- Configuration bounds:
  - `latencySlots` = 3 fills at the first trade three or more slots after the trigger;
  - `latencySlots` = 0 or 151 is a 400 with a field error.
- Loading a v2 file fails with `UNSUPPORTED_SCHEMA`; a v3 file whose `curveComplete` contradicts its reserves fails with `CURVE_STATE_INCONSISTENT`.
- `calibrateExecution` on the fixtures prints `buys=<n> mathExact=<n> mathMismatches=0 latencyChecked=<n> medianErrorBps=<x> medianTxFeeLamports=<n> medianOtherLamports=<n>` and writes the report JSON.
- A closed position row has non-null `close_transaction_cost`, `close_price_impact_bps` and `close_fee_bps`; an open one has nulls.
- The backend and frontend suites pass, and the run page shows impact, fees and transaction cost on each fill and close.

## Open Questions

1. **Which curve state does a fill execute against?**
   - The post-trade state of the fill trade (we land behind the first trade in that slot).
   - Or its pre-trade state (we land first).
   - **Recommend post-trade:** it is never more optimistic than the observed sequence.
2. **Default `transactionCostSol`?** **Recommend 0.001 SOL per transaction** until `calibrateExecution` reports Decu's median network fee and residual; then set the default from that, in a small follow-up.
3. **Slippage tolerance and failed transactions?** A real bot sets a maximum slippage. When the price moves past it, the transaction fails but still pays the network fee. **Recommend deferring** to a later plan and dropping `slippageBps` now, so impact is modelled one way only.
4. **Default `latencySlots`?** **Recommend 1**, today's rule. Calibration check (b) shows how much the price moves within a slot, which informs raising it.
5. **Local database reset after delivery?** Old run snapshots carry `feeBps` and `slippageBps`. **Recommend `docker compose down -v` again**; no real runs exist yet.
6. **Token-account rent?** **Recommend not charging it.** It is refunded when the account is closed after the sale. Document it instead, and charge it only if we later model keeping the account open.

## Delivered

2026-10-10: all four parts shipped: curve state and dataset v3; engine dip-paper-v2 with event fees, price impact, latency and fixed transaction costs; console/frontend; offline calibrateExecution and deterministic JSON reports. Review fixed entry selection missing curve completion during latency (including the trigger slot), moved the shared pure execution helper into the public papertrading contract, and corrected the export task description to v3.

Calibration cost medians preserve exact half-lamport values rather than rounding to an integer. Per-buy errors are signed; summary errors use absolute magnitudes. NO_PRIOR_STATE and unpredictable prior curves are explicit; the report gives the unpredictable count and reasons. Execution defaults remain 0.1 SOL notional, 1-slot latency and 0.001 SOL transaction cost, pending the user's real-capture calibration.

Verification: final gradlew build --offline passed 496 tests, zero failures/errors/skips; frontend npm test passed 109 tests, npm run lint and npm run build passed. Two completion regressions and a fractional-median regression failed before their fixes and passed afterward. Documentation updated: README, REPLAY_FORMAT links, REAL_DATA_CAPTURE, IMPLEMENTATION_STATUS, execution ADR, production SQL reference path and HANDOFF.

Schema is unchanged from the approved additive entity changes and reference SQL. Production must manually apply all of docs/schema/V20261009120000__bonding_curve_execution.sql. The local reset/restart, sample import and real-capture calibration remain user-run; no live calls, infrastructure resets or Git state changes were made.

Residual limit: extreme synthetic curve reserves that satisfy only the stated invariants can yield priceImpactBps beyond Int range. The approved contract uses Int, so a wider representation or explicit rejection rule needs a follow-up decision; no clamping was introduced.
