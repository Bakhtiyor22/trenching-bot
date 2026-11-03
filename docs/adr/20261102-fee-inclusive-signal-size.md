# Fee-inclusive minimum signal size

Accepted 2026-11-02 with the approved [automated capture and sweep plan](../plans/archive/20261102-decu-capture-sweep-automation.md) (Key Decision 7, Domain Rules 1–4). Engine `dip-paper-v6`. It amends the [dip-entry decision](20261008-dip-entry-strategy.md), the [agreed-strategy preset](20261016-agreed-strategy-run-preset.md) and the [post-trade price and wallet-sell decision](20261025-post-trade-price-and-wallet-sell.md).

## Context

Spec P3 ([decu-dip v2](../strategy/decu-dip-v2-spec.md)) makes a Decu buy a signal only when it is strictly more than 1.9 SOL. No engine applied it: the console preset listed it as unsupported, and `overSignalThreshold` on wallet activity was only a hint that compares the curve payment alone. Automation will sweep the size as a strategy variation and filter captures by it, so it has to be a rule of the engine, with one exact formula.

The user settled the measure: the curve payment plus the protocol fee plus the creator fee. Network fees, priority fees, tips and account rent are excluded, as P3 says.

What the data offers:

- A schema v4 or later observation records its trade's trader, side and `solLamports`, the SOL paid into the curve before fees, and every observation records the protocol and creator fee basis points the program charged.
- The wallet buy's own observation is the one the post-trade reference is read from (`POST_TRADE`), matched by pair, slot, signature, top-level instruction, and the wallet as buyer.
- The program's fee is `ceil(amount × bps / 10,000)` per component; paper execution already charged it, in `BondingCurve.fee`.

## Decision

1. **The signal is fee inclusive, in whole lamports.** `SignalSize.lamports = curvePayment + PumpFunFees.lamports(curvePayment, feeBps) + PumpFunFees.lamports(curvePayment, creatorFeeBps)`, all `BigInteger`. The fee formula moves unchanged from `BondingCurve.fee` to `protocol.pumpfun.PumpFunFees`, which paper execution, execution calibration and the signal rule share.
2. **It is read from the wallet buy's own observation, with no fallback.** The curve payment is that observation's trade `solLamports`, and the fee rates are its curve state's. The observation is the one `POST_TRADE` resolves: exactly one match, and it must be an eligible price. Without it, or when it records no trade, the cycle ends `SIGNAL_SIZE_UNAVAILABLE`. The wallet transaction's own `quoteDelta` is never used: it includes the network fee, tips and rent.
3. **Strictly greater, exactly.** `minSignalSol` converts to lamports with scale 9 and `RoundingMode.UNNECESSARY`; a buy of exactly 1.9 SOL ends `SIGNAL_BELOW_MIN_SIZE`, 1.9 SOL plus one lamport is a signal. A curve payment of 1.88 SOL at 95 + 30 bps pays 1.9035 SOL and passes.
4. **Check order.** At the buy: `UNSUPPORTED_SUPPLY`, then `REFERENCE_UNAVAILABLE` (under `POST_TRADE`), then the size (`SIGNAL_SIZE_UNAVAILABLE`, `SIGNAL_BELOW_MIN_SIZE`), then `REFERENCE_BELOW_MIN_MARKET_CAP`, the closed window and the watch. Both size outcomes are decided at the buy's slot and are entry rejections with the same names. The rule applies under either price measure.
5. **Optional, and set in `agreed()`.** `SimulationConfig.minSignalSol` is null by default (no rule, the `dip-paper-v5` behaviour), whole lamports from one lamport to 1,000 SOL, stored at scale 12. `SimulationConfig.agreed()` sets 1.9 SOL. The console sends and returns it as a decimal string.
6. **Hash, checksum and provenance.** The configuration hash appends `minSignalSol=<plain string>` only when it is set. Each intent carries `signalLamports`, set whenever a minimum is set and the buy's observation records its trade, and the result checksum appends `signalLamports=<lamports>` for each such cycle.
7. **A dataset whose observations record no trades (schema v3) is refused.** `ReplayRunner` throws `SignalSizeNeedsTradesException` (`SIGNAL_SIZE_NEEDS_TRADES`) after the wallet-sell check and before any scenario runs; the console answers 409 and logs a `WARN`. Both refusals share `TradesRequiredException`.
8. **Qualification.** `SIGNAL_BELOW_MIN_SIZE` is a negative case. `SIGNAL_SIZE_UNAVAILABLE` fails the verdict with category `SIGNAL_SIZE_UNAVAILABLE`, like `REFERENCE_UNAVAILABLE`: the data cannot say whether the token qualified.
9. **Persistence.** `trade_intents` gains the nullable `signal_lamports numeric(20,0)`, so a stored intent reads back exactly as replayed, and the enum columns `trade_intents.watch`, `paper_orders.rejection_reason` and `positions.exit_unavailable` gain the two values ([V20261102120000](../schema/V20261102120000__automation.sql)). An intent stored earlier has no signal size.

## Alternatives

- **The curve payment alone, as `overSignalThreshold` does:** not the user's measure; a 1.88 SOL curve payment pays 1.9035 SOL and is a signal.
- **The wallet transaction's SOL balance change:** it includes network and priority fees, tips and account rent, which P3 excludes, and it is not split per instruction.
- **Falling back to the wallet transaction when the observation is missing:** a silent fallback, mixing two measures in one run.
- **Checking the size before the post-trade reference:** under `POST_TRADE` both read the same observation, so a missing one would end `SIGNAL_SIZE_UNAVAILABLE` instead of `REFERENCE_UNAVAILABLE`, changing the outcome, the qualification category and the capture cutoff of every capture made before the market-window lead for no new information. See Revisit.
- **Keeping the size in the report snapshot only:** a stored intent would no longer read back as the replay made it.
- **A capture-time filter only:** automation's 1.5 SOL capture filter is a separate property, kept at or below the plan's smallest minimum; only an engine rule can be swept and scored.

## Consequences

- Every run id changes once with the engine version, as with `dip-paper-v5`. Without a minimum the results equal `dip-paper-v5`'s under the old run id, and the configuration hash of every configuration without it is unchanged: `mvp()` keeps `be93…a329`, and `dip-sample-v3` under `mvp()` now runs as `1ddd…9cfa` with checksum `c3d2…b78f`.
- **`agreed()` changes (flagged contract change).** Its configuration hash changes from `193a…772d`, so agreed run ids change. Wallet + token qualification and the `DECIDED` market cutoff replay with `agreed()`: a buy paying at most 1.9 SOL is now a negative case and its window is decided at the buy, and the cutoff identity changes, so `dip-paper-v5` cutoffs reopen on resume. Stored runs keep their ids and engine versions.
- The preset lists `minSignalSol` as agreed (P3, `CURVE_PAYMENT_FEES_ON_TOP`), and the unsupported spec rules drop from 5 to 4.
- A local database Hibernate created needs the reference SQL once for its enum checks, or a reset; production applies it by hand before deploying.
- Clients other than the console that read `RejectionReason` or `DipWatchOutcome` see two new values.

## Revisit when

- the check order between the post-trade reference and the size matters for scoring: automation counts `REFERENCE_UNAVAILABLE` as a signal with no P&L but not `SIGNAL_SIZE_UNAVAILABLE`;
- Pump.fun changes its fee formula or adds a fee component;
- the pool after migration is traded, so a first buy can be off the bonding curve;
- the `decu-dip` v2 engine ships and takes over the rule.
