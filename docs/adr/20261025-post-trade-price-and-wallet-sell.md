# Post-trade prices and the tracked wallet's sells

Accepted 2026-10-24 with the approved [post-trade price and wallet-sell plan](../plans/archive/20261024-post-trade-price-and-wallet-sell.md), with the user's answers to its four questions. Engine `dip-paper-v5`. It amends the [dip-entry decision](20261008-dip-entry-strategy.md), the [reference market cap decision](20261023-reference-market-cap.md), the [agreed-strategy preset](20261016-agreed-strategy-run-preset.md) and the [timed dip window and exit](20261024-timed-dip-window-and-exit.md).

## Context

Run `84ff…a72a` (dataset `06a9…ec41`) showed two gaps against the agreed `decu-dip` v2 spec ([docs/strategy/decu-dip-v2-spec.md](../strategy/decu-dip-v2-spec.md)).

- **The reference sat in the middle of the wallet's own move.** Engine `dip-paper-v4` took P0 as the wallet buy's average fill price. A 1.96 SOL buy moved the curve from about 7.64e-8 to 8.26e-8 SOL per token, and P0 was about 7.94e-8. The spec's P4 is the post-trade price right after the buy, and Section 5 makes the post-trade price (`virtual_sol / virtual_token` after the trade) the one measure for the reference, the dip and TP/SL.
- **The wallet's sells were ignored.** The wallet sold 2.54 SOL of the token about 2.5 minutes before the price reached the old dip level. Without the 30 s window the engine would have bought after the wallet left. The user's rule: never buy after Decu sells, and sell everything if he sells while we hold.

What the data offers:

- Every observation carries the post-trade curve reserves, so any recorded trade's post-trade price is `BondingCurve.spotPrice(curve.reserves)`.
- Since the [market window lead](20261022-market-window-lead.md), a window starts 50 slots before the first buy, so the wallet's buy is itself a recorded observation. Older captures start one slot after it.
- Since dataset schema v4, each observation records its trade's trader and side. Wallet captures of scope `pumpfun-user-buys` record buys only, so the market data is the one place the wallet's sells appear. A v3 observation records no trade.

## Decision

1. **Both rules are opt-in, off by default.** `SimulationConfig` gains `priceMeasure` (`TRADE_AVERAGE` by default, `POST_TRADE`) and `followWalletSells` (`false` by default). With both off, a `dip-paper-v5` run's outcomes, prices and decision slots equal `dip-paper-v4`'s. The configuration hash names a field only when it is not the default, so `mvp()`'s hash is unchanged. `SimulationConfig.agreed()` sets `POST_TRADE` and `true`, so the console preset, the `DECIDED` cutoff and wallet + token qualification use both.
2. **The post-trade reference is read from the wallet buy's own observation, with no fallback.** The observation must have the buy's pair and slot, the buy's signature, the buy's top-level instruction index in its `sourceEventId`, and, when it records a trade, the buy's wallet as trader and side `BUY`. Exactly one must match, and as a price the run uses it must pass `ObservationEligibility` (quality, commitment and age), like every trigger, fill and exit price. With none, several, or one ineligible match the cycle ends `REFERENCE_UNAVAILABLE`, decided at the buy's slot; the intent's reference and dip prices then keep the buy's average for the record only, and it names no observation. The buy-time checks run in this order: `UNSUPPORTED_SUPPLY`, `REFERENCE_UNAVAILABLE`, `REFERENCE_BELOW_MIN_MARKET_CAP`, closed window (`NO_DIP`), then the watch. Each intent stores `referenceObservationId`.
3. **Post-trade prices for the dip and for TP/SL.** Under `POST_TRADE` the minimum market cap is judged at the post-trade reference (strictly greater, as before), the dip trigger compares each later trade's post-trade price with the dip level, and TP/SL compare each later trade's post-trade price with levels that still come from our own average fill (spec Section 5, "Our average fill", and Section 8). `PriceMeasure.of(observation)` is the one place that chooses the price. Our own fill is still priced by curve execution.
4. **The wallet's sells come from the market data only.** A wallet sell is an observation of the pair whose trade has the tracked wallet as trader and side `SELL`. An accepted wallet trade with side `SELL` is not read: wallet captures may hold buys only, and an exit trigger must name an observation. A sell counts whatever its observation's eligibility: it is a fact about the wallet, not a price the run uses.
5. **Same-slot conservatism at entry.** A wallet sell from the buy's slot through the dip trigger's slot, inside the dip window, ends the cycle `WALLET_SOLD`, decided at the sell's slot, and the intent stores `walletSell = {eventId, slot}`. Each slot is checked for a sell before a trigger in it, so a sell in the trigger's own slot blocks the entry: within a slot the canonical order is by observation id, not chain order, and a live bot sees a slot's transactions together. A sell after the trigger slot is something the decision could not have seen and does not stop the entry. Any sell amount counts.
6. **The `WALLET_SOLD` exit.** Under `followWalletSells`, a wallet sell after the trigger slot while we hold is an exit trigger with `CloseReason.WALLET_SOLD`:
   - it sells 100% of our tokens, however little the wallet sold;
   - it joins TP and SL: the earliest slot with a trigger fixes the reason, and within a slot, a wallet sell is read first, so it wins over any TP/SL crossing in its slot, the same trade's included, whatever their observation ids (as at entry, the outcome never depends on the canonical order inside a slot);
   - the sale follows the TP/SL path unchanged, into the first eligible trade at or after `trigger.slot + latencySlots` within the 60 s sale window, otherwise against the standing curve once the deadline is proven;
   - a sell landing after our trigger but no later than our fill's slot triggers the exit as soon as we hold: the trigger names the sell's observation, but its slot and time are the fill's, and the sale is into the first eligible trade after the fill slot;
   - a sell at or after a timed position's `exitStart` changes nothing.
7. **A dataset whose observations record no trades (schema v3) is refused for the sell rule.** `ReplayRunner` throws `SellCheckNeedsTradesException` (code `SELL_CHECK_NEEDS_TRADES`) before any scenario runs, and nothing is persisted. The console answers 409, as for `MARKET_COVERAGE_ENDED`, and logs the refusal at `WARN`. The rule would otherwise read the absence of recorded trades as no sells.
8. **Qualification.** `WALLET_SOLD` before the dip is a negative case. `REFERENCE_UNAVAILABLE` fails the verdict with category `REFERENCE_UNAVAILABLE`, and a v3 dataset with `SELL_CHECK_NEEDS_TRADES`: neither can say whether the token qualified.
9. **Persistence.** `trade_intents` gains the nullable `wallet`, `reference_observation_id`, `wallet_sell_event_id` and `wallet_sell_slot`, and the enum columns gain `WALLET_SOLD` and `REFERENCE_UNAVAILABLE` ([V20261025120000](../schema/V20261025120000__post_trade_price_and_wallet_sell.sql)). An intent stored before them has no wallet; it reads the wallet of the run's accepted trade it was made from, which is the buy's wallet by construction, so no placeholder address is ever invented.

## Alternatives

- **Falling back to the buy's average when its observation is missing:** a silent price fallback, which the trading-domain rules forbid, and it would mix two measures in one run.
- **Adding post-trade reserves to wallet transactions:** a new dataset schema, and old datasets would still lack them. Reading the buy's market observation needs no format change.
- **Reading sells from the wallet capture too:** buy-only captures would see none, and the two sources could disagree; a missing sell in the market data means the market capture itself is incomplete.
- **Letting a sell in the trigger's slot count only when its observation id sorts first:** that order is not chain order, so the outcome would depend on an arbitrary tie-break.
- **Selling only the share the wallet sold:** the user wants out when Decu leaves (spec P14: exits are 100%).
- **Making the rules the default:** every stored run and fixture would change meaning, and `mvp()` stays the API and CLI default.

## Consequences

- Every run id changes once with the engine version; `mvp()`'s configuration hash `be93…a329` does not. `dip-paper-v4` cutoffs reopen on resume.
- A `POST_TRADE` run on a capture made before the market window lead ends `REFERENCE_UNAVAILABLE` on every cycle; such captures must be re-captured to qualify under `agreed()`. A run with the sell rule on a v3 dataset is refused.
- Each intent carries its provenance: `referenceObservationId`, `walletSell` and the buy's `wallet`; the result checksum covers the first two.
- The preset lists `priceMeasure` (P4 / Section 5) and `followWalletSells` (Section 6 / Section 8) as agreed, and the unsupported spec rules drop from 6 to 5 (`POST_TRADE_PRICE` is applied).
- The spec gained the wallet-sell rule (Sections 2, 6 and 8) on 2026-10-24.
- Production applies the reference SQL by hand before deploying; a local database Hibernate created needs it once for its enum checks, or a reset.

## Revisit when

- within-slot chain order is recorded, so a same-slot sell could be placed before or after the dip;
- the pool after migration is priced, so post-trade prices exist after the curve completes;
- the wallet capture records sells, so the two sources can be cross-checked;
- the `decu-dip` v2 engine ships and takes over these rules.
