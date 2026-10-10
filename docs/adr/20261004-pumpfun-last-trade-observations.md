# Pump.fun bonding-curve LAST_TRADE observations

Accepted 2026-10-04 with [Amendment B](../plans/archive/20261003-real-wallet-replay.md) of the real wallet replay plan, approved by the user in chat. This decision implements the plan's key decision 4 for the first evidenced route. It builds on the [buy decoder ADR](20261003-pumpfun-bonding-curve-buy-decoder.md), whose quote representation and price basis the user chose.

## Context

The replay engine fills +1/+2/+3 entries and exits from same-pair `MarketObservation`s within slot windows ([ADR 0001](0001-fixture-simulation-semantics.md)). The one decoded real buy is a Pump.fun bonding-curve buy of mint `5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs` at slot 453004996. The wallet's own execution price cannot fill later slots, so prices must come from other traders' trades on the same bonding curve at those slots. Every such trade emits a Pump.fun `TradeEvent` through its self-invoked event instruction, with the layout recorded in the buy decoder ADR.

## Decision

Each valid Pump.fun `TradeEvent` event instruction in a captured, verified, successful transaction yields exactly one observation. Buys and sells both count, by any user.

- **Pair:** base = event mint; quote = `MintAddress.WRAPPED_SOL`. The bonding curve's native SOL is represented as wrapped SOL, as for the buy decoder.
- **Price** = (`sol_amount` × 10^baseDecimals) ÷ (`token_amount` × 10^9), computed from exact integers and rounded once to scale 38, HALF_EVEN. Like the decoder, it is the curve amount with all fees excluded: Pump protocol and creator fees, router fees, tips and network fees. The simulator applies its own fee and slippage model. Worked example from the real buy: 244,444,444 × 10^6 ÷ (1,238,520,682,656 × 10^9) = `0.00000019736807582073671197813450558296`.
- **Base decimals:** read from the same transaction's token balances (pre or post) for the event mint. All of them must agree. If they are absent or conflict, the event is excluded with reason `DECIMALS_UNPROVEN`.
- **Slot** = **price slot** = the transaction's slot, so age is 0. **Observed time** = the transaction's block time. A null block time excludes the event (`BLOCK_TIME_MISSING`); capture time is never substituted.
- **Quality** `LAST_TRADE`; **commitment** confirmed. **Liquidity** is absent, because depth is unknown. Reserve fields in the event are not turned into depth or spot prices.
- **Identity:** `sourceEventId` = `<signature>:<topLevelIndex>:<innerIndex>`; `id` = `pumpfun-bonding-curve-trade/v1:<sourceEventId>:<mint>`. **Source** = `pumpfun-bonding-curve-trade/v1`. Several events in one transaction have distinct ids. Same-slot ordering uses the existing stable-id tie-break; no intra-slot execution order is claimed.
- **Exclusions, each counted by reason and never turned into an observation:**
  - `ZERO_AMOUNT`: zero `sol_amount` or `token_amount`.
  - `MALFORMED_EVENT`: an `is_buy` other than 0/1, or truncated data.
  - `QUOTE_MINT`: the event mint equals the quote mint.
  - `DECIMALS_UNPROVEN` and `BLOCK_TIME_MISSING`, as above.
  - `OUT_OF_WINDOW`: a slot outside every usable window.
  - `EXCEEDS_REPLAY_BOUNDS`: a value the replay format cannot hold exactly. That is a price that rounds to zero at scale 38, a price beyond `numeric(78,38)`, a block time outside 0..9999-12-31, or an id longer than 255 characters. Values are never truncated.

  Events that exist only as `Program data:` log lines are ignored.

  Reasons are checked in this order: `QUOTE_MINT`, then `OUT_OF_WINDOW`, then the rest. Every reason after `OUT_OF_WINDOW` therefore concerns a trade a replay window needs. A truncated event cannot name its mint, so `MALFORMED_EVENT` for one is always counted.
- **Export (unit 4, user decision 2026-10-04):** a replay export is refused with `MARKET_CAPTURE_EXCLUSIONS` when any reason other than `OUT_OF_WINDOW` or `QUOTE_MINT` is counted. A lost in-window trade could change which observation an entry or exit fills on, the same reason a detail gap blocks a window. Counts are not attributed to windows, so any such exclusion refuses the whole export.
- **Coverage:** observations are produced only from windows whose coverage is complete and that have no detail gap. A detail gap is an in-window signature recorded as `UNAVAILABLE`, `UNSUPPORTED_VERSION` or `WITHHELD_UNSAFE_TEXT`; the missing trade could change which observation is first eligible. Such a window reports its gaps and yields none. A window that is `COVERAGE_INCOMPLETE` or `OVER_CAP` yields none and is reported, so a partial window can never look like an absence of trades. Missing observations then follow the engine's existing rules: rejected entries, and open positions with an explicit reason.

## Alternatives

- **Reserve-derived spot price from the event's virtual reserves:** a model price, not an observed trade.
- **Price including Pump fees:** double-counts fees once the simulator applies its own.
- **Block-wide capture:** too large per response and mostly unrelated transactions; see Amendment B.
- **Deriving depth from reserves:** the plan requires depth to stay unknown unless proven executable.

## Consequences

Observations are observed bonding-curve trade prices, not executable quotes. Fills modelled from them carry the simulator's assumed costs, not real price impact. Graduated tokens (PumpSwap) and other routes produce no observations until another evidenced extractor exists. Results stay modelled paper P&L, not Decu's actual profit.

## Revisit when

Pump.fun changes the `TradeEvent` layout or its event mechanism. Also revisit when tokens in scope graduate to PumpSwap, when depth becomes needed for liquidity checks, or when the user chooses a different price basis or quote representation.
