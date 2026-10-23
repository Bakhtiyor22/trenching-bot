# Bonding-curve execution

Accepted 2026-10-09 with the [bonding-curve execution plan](../plans/archive/20261009-bonding-curve-execution.md). The user accepted every recommendation of the plan's open questions: fills against the fill trade's post-trade curve, a default transaction cost of 0.001 SOL, slippage tolerance and failed transactions deferred, a default latency of 1 slot, a local database reset after delivery, and token-account rent documented but not charged.

It supersedes the execution part of the [dip-entry decision](20261008-dip-entry-strategy.md): the flat `feeBps` and `slippageBps`, the zero price impact and priority fee, and the hard-coded "next later slot". The dip-entry triggers (dip, market cap, take profit, stop loss, migration, horizon) are unchanged. It also supersedes the fee and slippage parts of [ADR 0001](0001-fixture-simulation-semantics.md) and the [configurable runs decision](20261002-configurable-replay-runs.md).

## Context

Engine `dip-paper-v1` filled at a captured trade's price plus 50 bps of slippage and charged 10 bps of fees, with price impact and priority fee recorded as zero. A Pump.fun bonding curve does not trade like that:

- **Impact.** The average price paid rises with the order against the curve's virtual SOL. Near a $10,000 market cap (about 40 SOL of virtual reserves) a 0.1 SOL buy moves it about 0.25%, 1 SOL about 2.5%, and 100 SOL would exceed the curve.
- **Fees.** The program charged about 1.25% (a protocol fee plus a creator fee), not 0.1%.
- **Fixed costs.** The network fee plus a priority fee or tip is several percent of a 0.1 SOL round trip.

Every captured trade's `TradeEvent` carries the curve's reserves after the trade and both fee basis points, so dataset v3 stores them on each observation (`curve`).

## Decision

Engine version `dip-paper-v2`; strategy `dip-entry` stays v1, since its rules do not change.

- **Curve math in raw units.** `protocol/pumpfun/BondingCurve` is the only place curve arithmetic exists: `tokensOut = floor(lamportsIn × vToken / (vSol + lamportsIn))` and `lamportsOut = floor(tokensIn × vSol / (vToken + tokensIn))` on `BigInteger`, like the program. Raw amounts convert to decimals once: lamports ÷ 10^9 and raw tokens ÷ 10^6.
- **Post-trade state.** A fill trades against the post-trade reserves of its fill trade: our transaction lands behind the first trade we see in that slot. This keeps the next-trade fill rule and adds our own impact on top of that trade's.
- **Latency.** `latencySlots` (1–150, default 1) replaces the hard-coded later slot. An entry fills at the first eligible trade with slot ≥ trigger slot + `latencySlots`, within the horizon and before the curve completes; an exit the same, from its exit trigger.
- **Entry.** `lamportsIn = notional × 10^9`, exact because a notional is a whole number of lamports (at most 9 decimals, at least 1 lamport). If `tokensOut` exceeds the curve's real token reserves the entry is rejected as `EXCEEDS_CURVE`: never partially filled, never moved to a later trade. The fill records `executionPrice` = lamports in ÷ tokens out (SOL per whole token, scale 38 HALF_EVEN), `quantity` = tokens out, `fee` = both fees in SOL, `feeBps` = their sum, `slippageBps` = 0, `priorityFee` = `transactionCostSol`, and `costBasis` = notional + fees + transaction cost.
- **Exit.** The whole position sells into the exit trade's post-trade curve. A trade whose curve cannot pay the sale (`lamportsOut` above its real SOL reserves) is skipped, and if none can, the position stays open as `NO_EXIT_FILL`. Net = `lamportsOut` − fees − `transactionCostSol`; P&L = net − cost basis, at scale 12. A net at or below zero still closes with its loss. The close records the fee basis points, the transaction cost and the price impact.
- **Fees come from the events.** Each fee is `ceil(lamports × bps / 10^4)` at the fill trade's own protocol and creator basis points: on the SOL paid in for a buy and on the SOL received for a sale. Fees are paid on top of the notional on entry and out of the proceeds on exit.
- **Price impact** = `round((executionPrice − spot) × 10^4 ÷ spot)` HALF_EVEN, where spot is the fill state's `vSol ÷ vToken`: positive for a buy, negative for a sale.
- **Transaction cost.** `transactionCostSol` (0–0.1 SOL at scale 12, default 0.001) is charged once per entry (into the cost basis) and once per exit (out of the net proceeds), for the network fee plus a priority fee or tip.
- **Pe** is the curve execution price: it includes our own impact and excludes fees. Take profit and stop loss are measured from it, as before.
- **The curve caps replace the liquidity check.** `EXCEEDS_CURVE` on entry and the real-SOL cap on exit take the place of the `liquidityQuote` checks, which are removed. The field stays in datasets and in the dataset checksum, unused by the engine.
- **Determinism.** No clock or network is used in a run. The configuration hash covers latency, transaction cost and the rule text `curve-post-trade-floor-fees-ceil`, and no longer fee or slippage. The result checksum adds, per fill, the fee basis points, transaction cost and impact, and per close the fee basis points, transaction cost and impact.

## Hand-checked example

`dip-sample-v3` with the defaults, computed independently of the engine. Every curve charges 95 + 30 bps.

- Take profit: 100,000,000 lamports buy 1,160,584,892,249 raw tokens from slot 1003's curve (Pe 0.0000000861635, impact +19 bps), fees 1,250,000 lamports, cost basis 0.10225. Triggered at 1005; the sale into slot 1006's curve returns 133,175,124 lamports (impact −22 bps) less 1,664,690 in fees and 0.001, net 0.130510434, P&L **0.028260434**.
- Stop loss: 1,247,541,616,895 raw tokens from slot 2002's curve (impact +20 bps), cost basis 0.10225. Triggered at 2004; the gap sale into slot 2005's curve returns 62,280,246 lamports (impact −16 bps) less 778,504 in fees and 0.001, net 0.060501742, P&L **−0.041748258**.

## Alternatives

- **The pre-trade state of the fill trade.** Assumes we land first in the slot, which is optimistic and needs within-slot order the dataset does not record.
- **A flat slippage on the observed price.** Ignores that impact grows with order size and curve depth; it cannot reject an order the curve cannot fill.
- **Configured fees.** The program's real basis points are in every event, and they have changed over time.
- **A slippage tolerance with failed transactions.** Deferred: a real bot sets a maximum slippage, and a failed transaction still pays the network fee. Dropping `slippageBps` now models impact one way only.

## Consequences

- Datasets of schema v2 are refused (`UNSUPPORTED_SCHEMA`); captures re-export as v3 without recapture.
- Run configurations from `dip-paper-v1` name `feeBps` and `slippageBps`, which the reader refuses, so the local database is reset after delivery. The console API drops both fields; a request that still sends them gets a per-field 400.
- Production applies all of `docs/schema/V20261009120000__bonding_curve_execution.sql` by hand: market-observation curve columns and checks, position close-cost columns and checks, and the relaxed position-state and fill-fee constraints.
- **Accepted counterfactual limit:** other traders' reactions to our trade cannot be known from a replay. At 0.1 SOL our own impact is about 0.2–0.5%.
- **Token-account rent** (about 0.00204 SOL) is not charged: it is refunded when the account closes after the sale. Charge it only if a strategy keeps the account open.
- Post-migration (PumpSwap) execution is still not modelled; migration still ends the cycle.

## Revisit when

- The calibration against the wallet's real buys (`calibrateExecution`) reports an error above an agreed bound;
- the program's fee formula changes (for example tiered or market-cap-dependent fees not captured by the two basis points);
- a slippage tolerance with failed transactions is wanted;
- within-slot transaction order is recorded.

## Amendment 2026-10-24: sales against the standing curve

Engine `dip-paper-v4` ([decision](20261024-timed-dip-window-and-exit.md)) changes the exit rule above. A take profit or stop loss sells into the next eligible trade at least `latencySlots` after the trigger only when that trade lands within 60 s of the trigger (spec Section 8's active phase; inclusive, by block time). When none does, the position sells against the curve standing at `trigger.slot + latencySlots`, the post-trade curve of the last eligible trade before that slot, once the recording proves that no unseen trade can land within the 60 s: a recorded trade at or after that slot later than the deadline, or a coverage end time later than it. Without that proof it stays open as `HORIZON_REACHED`. A time exit always sells against the curve standing at its exit start. The curve math, fees, rounding, transaction cost and price impact are the same as for a sale into a trade. Each close records `priceAssumption`: `NEXT_TRADE` for a sale into the trade's own curve, `STANDING_CURVE` otherwise; closes stored earlier read as `NEXT_TRADE`. The rule that skips a curve which cannot pay the sale now covers only the trades within the 60 s and ends in `UNRESOLVED` instead of `NO_EXIT_FILL`, decided once the 60 s are proven over. The result checksum adds each close's price assumption.
