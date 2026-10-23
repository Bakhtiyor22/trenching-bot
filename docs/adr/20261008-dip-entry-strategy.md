# Dip-entry strategy

Accepted 2026-10-08 with the [dip-entry strategy plan](../plans/archive/20261008-dip-entry-strategy.md). The user gave the rules and chose replacement, a 30-minute horizon, rates stored in the dataset, fills at the next trade, and every recommended answer to the plan's open questions.

It supersedes the strategy, fill and exit rules of [ADR 0001](0001-fixture-simulation-semantics.md), and the offset, wait and holding parameters of the [configurable runs decision](20261002-configurable-replay-runs.md). Their remaining decisions (fees, slippage, quantities, rounding, idempotency, persistence) stand.

2026-10-09: the execution part of this decision (the flat `feeBps` and `slippageBps`, zero price impact and priority fee, and the fill at the next later slot) is superseded by the [bonding-curve execution decision](20261009-bonding-curve-execution.md), engine `dip-paper-v2`. The triggers below (dip, market cap, take profit, stop loss, migration, horizon) are unchanged; the hand-checked figures below are those of `dip-paper-v1`.

2026-10-23: the market-cap part of the dip trigger is superseded by the [reference market cap decision](20261023-reference-market-cap.md), engine `dip-paper-v3`: the wallet's buy must be valued strictly above the minimum, and the dip trade is no longer checked.

## Context

Replay copied each tracked-wallet buy at source slot +1, +2 and +3 and exited after a fixed number of slots. The user replaced this with a dip-entry strategy, in their words:

```text
WAIT_FIRST_BUY  → Deku's first buy (record his price P0)
WAIT_DIP        → price ≤ P0 × 0.85 AND mcap ≥ $10k AND not migrated → BUY at Pe
IN_POSITION     → price ≥ Pe × 1.30 → SELL (TP)
                → price ≤ Pe × 0.85 → SELL (SL)
CLOSED
```

## Decision

Engine version `dip-paper-v1`; strategy `dip-entry` v1. The default configuration is the user's: dip 1500 bps, take profit 3000 bps, stop loss 1500 bps, minimum market cap $10,000, horizon 4,500 slots, notional 0.1 SOL, fee 10 bps, slippage 50 bps, maximum observation age 2 slots. All are editable per run.

- **One cycle per mint.** The tracked wallet's first accepted buy of a mint, in canonical order, starts a cycle. Later buys of that mint are ingested as `LATER_BUY_SAME_MINT` and start nothing.
- **P0** is the wallet's own price on that buy: SOL amount ÷ token amount, fees excluded, at scale 38 HALF_EVEN. Dip level = P0 × (1 − dip); take profit = Pe × (1 + TP); stop loss = Pe × (1 − SL); all at scale 38.
- **Watched trades.** Only eligible observations count: positive price, valid quality, confirmed, price no older than `maxAgeSlots`. They must be of the cycle's pair, in a slot after the buy, and no later than buy slot + horizon.
- **Dip trigger.** The first such trade at or below the dip level whose market cap is at least the minimum, before the bonding curve completes. Market cap = price × 1,000,000,000 × the dataset's SOL/USDT rate for the trade's minute, at scale 12. The supply is Pump.fun's fixed bonding-curve supply, so only `pumpfun-bonding-curve-buy/v1` buys of 6-decimal tokens qualify; others are `UNSUPPORTED_SUPPLY`.
- **Fills use the next slot.** An entry or exit fills at the first eligible trade in a slot after its trigger's, with slippage and fees as before. A trigger is never its own fill, and a trade in the same slot never fills: the dataset does not record on-chain order within a slot. A trade that gaps past the stop loss fills at its own, worse price.
- **Pe** is the entry's execution price, including slippage.
- **Migration.** A trade that leaves the curve's real token reserves at 0 is `curveComplete`. Before an entry, it ends the cycle (`MIGRATED_BEFORE_ENTRY`), and between trigger and fill it leaves `NO_FILL_AFTER_TRIGGER`. In a position, the position stays open as `MIGRATED`, because no post-migration price is captured.
- **Outcomes.**
  - Without an entry: `NO_DIP`, `DIP_BELOW_MIN_MARKET_CAP`, `MIGRATED_BEFORE_ENTRY`, `NO_FILL_AFTER_TRIGGER` or `UNSUPPORTED_SUPPLY`.
  - Open positions: `HORIZON_REACHED` (no trigger), `NO_EXIT_FILL` (trigger, no later trade) or `MIGRATED`.
  - Closed positions: `TAKE_PROFIT` or `STOP_LOSS`.
- **Determinism and identity.** No clock or network is used in a run. The configuration hash covers every parameter plus the supply and the fill rule. Starting the same run twice returns the stored run, as before. Each intent keeps P0, the dip level, the horizon end and its trigger (trade, slot, price, market cap, rate). Each position keeps its levels and exit trigger.

## Hand-checked example

The bundled `dip-sample-v2` dataset has one mint per outcome. Its take-profit cycle:

- buy at slot 1000 for P0 = 0.0000001 SOL;
- dip trigger at 1002 at 0.000000084, market cap $16,800 at 200 USDT per SOL;
- fill at 1003: 0.000000086 × 1.005 = Pe 0.00000008643, quantity 1157005.669327779706, cost 0.1001;
- take profit at 0.000000112359, triggered at 1005 by 0.000000113;
- exit at 1006: 0.000000115 × 0.995 = 0.000000114425, gross 0.132390373713, fee 0.000132390374, P&L **0.032157983339**.

Its stop-loss cycle triggers at 0.00000006 and fills at 0.00000005 × 0.995 = 0.00000004975, below the 0.00000006834 stop, for a P&L of **−0.038283768656**.

2026-10-09: the default notional was lowered from 100 to 0.1 SOL, and these figures recomputed. On a Pump.fun curve near a $10,000 market cap (about 40 SOL of virtual reserves), 100 SOL would move the price about twelvefold and exceed the curve, while the model has no price impact yet. A bonding-curve execution model is planned separately.

## Alternatives

- **Keep the copy strategy beside it:** the user chose replacement.
- **A fill on the triggering trade, or exactly at the level:** both optimistic. They assume a trade in the same instant as the observation, or no gap.
- **A same-slot next trade:** needs the transaction index within the slot, which the dataset does not carry.
- **A fixed SOL market-cap threshold:** simpler, but not $10,000 when SOL moves. See the [reference-rates decision](20261008-reference-rates-in-dataset.md).
- **An exit at the last curve price on migration:** optimistic, since the PumpSwap price can differ.

## Consequences

Old copy-strategy runs cannot be shown by the new run page. The local database is reset after delivery, and datasets re-import from their files. Schema v1 datasets no longer load. Market captures need the 30-minute window, which costs thousands of transactions per busy token. Results are paper fills on observed curve prices with a modelled fee and slippage, not executable quotes.

## Revisit when

- Re-entries or more than one cycle per token are wanted;
- a post-migration (PumpSwap) price is captured;
- within-slot order is recorded;
- a horizon longer than 4,500 slots is needed.

## Amendment 2026-10-24: block-time clocks and the time exit

Engine `dip-paper-v4` adds the spec's optional clocks ([decision](20261024-timed-dip-window-and-exit.md)). `dipWindowMs` lets only a trade at most that long after the buy's block time `t0` trigger, inclusive; the first later trade ends the watch as `NO_DIP`. `maxHoldMs` and `hardExitStartMs` add a third exit, `TIME_EXIT`, at `min(fill time + maxHoldMs, t0 + hardExitStartMs)`: take profit and stop loss are checked only on trades before it, and the position is then sold against the curve standing at that moment. Unset, the triggers above are unchanged. A take profit or stop loss sells into the next trade only when it lands within 60 s of the trigger, and otherwise against the standing curve once the recording proves those 60 s passed; this replaces the outcome `NO_EXIT_FILL`, and a sale the curve cannot pay leaves the position open as `UNRESOLVED`. Each intent now keeps `t0`, and each position its exit start and its exit trigger's time.
