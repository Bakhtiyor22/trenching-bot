# Pump.fun exact-SOL-in (V3) buys

Accepted 2026-10-10 with the approved [V3 decoder plan](../plans/archive/20261010-pumpfun-exact-quote-in-v3-decoder.md). This amends the [bonding-curve buy decoder](20261003-pumpfun-bonding-curve-buy-decoder.md); its "Revisit when" trigger fired.

## Context

The user's console capture of Decu's wallet on 2026-10-10 (scope `pumpfun-user-buys`, 100 transactions, slots 454,995,178–455,115,333) decoded no buy. Offline analysis of its artifacts:

- 81 transactions call Pump.fun `buy_exact_quote_in_v3`, discriminator `e1f7501ed5b38488` (Anchor `global:buy_exact_quote_in_v3`), always by CPI from the router `FLASHX8DrLbgeR8FcfNV1F5krxYcYMUdBkrP1EPBtxB9`. Its data is the discriminator, `spendable_quote_in` u64, `min_base_amount_out` u64 and one trailing byte (observed `01`, not interpreted).
- 19 call `sell_v3` (`1c92de7726c469d5`).
- The `TradeEvent` prefix read by the decoder and the market extractor, through `creator_fee` at byte 233, is unchanged. Events are 405 bytes for these buys and 391 for sells, instead of 390, only because the trailing `ix_name` string is longer (`buy_exact_quote_in`, `sell`). The 117 bytes after it keep their length.
- 15 buys are on curves quoted in another token. Their event `sol_amount`, virtual SOL reserves and real SOL reserves are zero, and USDC moves through the wallet, often after a CLMM `SwapV2`.
- In each of the 66 SOL-quoted buys:
  - `spendable_quote_in` = `sol_amount` + `fee` + `creator_fee` exactly;
  - `token_amount` ≥ `min_base_amount_out`;
  - the wallet-owned token change equals `token_amount`;
  - both fees equal ceil(`sol_amount` × bps / 10,000), as for legacy buys.
- With pre-trade reserves backed out by `sol_amount`, as for legacy buys, every one of the 66 receives exactly floor((`sol_amount` − 1) × vT / (vS + `sol_amount` − 1)) tokens. That is about 4 parts per billion fewer than the legacy formula on the full `sol_amount`. The 10 legacy buys in `decu-pumpfun-buys` still match the legacy formula exactly.

## Decision

1. **Decode both buy instructions.** A supported transaction contains exactly one Pump.fun buy, either a legacy `buy` or a `buy_exact_quote_in_v3`, plus exactly one `TradeEvent`. All other rules of the decoder ADR still apply. The amount binding depends on the instruction:
   - legacy: the token-amount argument equals the event `token_amount`;
   - V3: `spendable_quote_in` equals `sol_amount` + `fee` + `creator_fee`, and `token_amount` is at least `min_base_amount_out`.
2. **Non-SOL quotes are an unsupported route.** An event with `sol_amount` = 0 and virtual SOL reserves = 0 is rejected as `NON_SOL_QUOTE` with outcome `UNSUPPORTED_ROUTE`, before the amount checks. A zero `sol_amount` with non-zero SOL reserves stays `AMBIGUOUS` (`ZERO_AMOUNT`). The market extractor already excludes such events (`ZERO_AMOUNT`), so they are never priced as SOL.
3. **Same representation and source.** A decoded V3 buy is the same `WalletTransaction` as a legacy one, with `source` `pumpfun-bonding-curve-buy/v1` and quote = −`sol_amount`. The dip-entry strategy, replay datasets (schema v3) and existing datasets are unaffected. The decoder reports version `PUMPFUN_BONDING_CURVE_BUY_V2`.
4. **The instruction kind is a buy fact.** `PumpFunBuyFacts.instruction` is `BUY` or `BUY_EXACT_QUOTE_IN_V3`. Calibration's math and latency checks apply each instruction's rule:
   - legacy: `BondingCurve.buy` on `sol_amount`;
   - V3: `BondingCurve.buyExactQuoteIn`, the same formula on `sol_amount` − 1.

   Reports name the instruction per buy and count buys per instruction.
5. **Rejections are explained.** Decode results count rejection reasons and the Pump.fun instructions found in rejected transactions:
   - named: `BUY`, `BUY_EXACT_QUOTE_IN_V3`, `SELL`, `SELL_V3`, `BUY_EXACT_QUOTE_IN`, `BUY_V3`;
   - `OTHER_PUMPFUN` for unrecognised Pump.fun data;
   - `NO_PUMPFUN` when no Pump.fun instruction is present.

   The CLI and the console show these counts.

The paper engine keeps the legacy formula for its own fills (curve-execution ADR). The one-lamport difference is below any P&L precision of interest, and calibration keeps reporting it.

## Alternatives

- **A new `source` for V3 buys:** the strategy keys on the source, so V3 buys would silently stop being signals, or the strategy and replay format would have to change for no simulation difference.
- **Reading the quote mint from the event's trailing fields:** the zero SOL fields identify non-SOL curves from the stable prefix alone, without depending on a layout that has already changed once.
- **Modelling the one-lamport rule in paper fills:** a new engine version for a difference of about 4 ppb.

## Consequences

Decu's current buys decode. The 2026-10-10 sample yields 66 buys across 15 tokens, so planned market capture is large. Legacy captures decode unchanged. Calibration reports math exact for both instructions while the observed rules hold.

## Revisit when

- Calibration reports a math mismatch for a V3 buy, which would mean the one-lamport rule no longer holds.
- Pump.fun changes the `TradeEvent` prefix.
- Decu trades through `buy_exact_quote_in`, `buy_v3`, sells, PumpSwap, or curves quoted in another token that should be simulated.
