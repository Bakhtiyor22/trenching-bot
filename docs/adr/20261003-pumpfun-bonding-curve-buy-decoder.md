# Pump.fun bonding-curve buy decoder

Accepted 2026-10-03. The user chose the quote representation, the price basis and the route scope in chat. This is unit 2 of the [real wallet replay plan](../plans/20261003-real-wallet-replay.md).

## Context

A user-run capture of the latest 100 successful references to wallet `4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9` found exactly one transaction the wallet signed, `35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA`. It is a version 1 transaction at slot 453004996. The other 99 references were transactions signed by other accounts, with the wallet included read-only. This decoder is built only from the one buy. The failed sample from an earlier capture is the failure/unsupported example.

What the buy contains:

- **Outer instructions:** 0 is System `advanceNonce`, with the wallet as nonce authority. 1 is a router program `FLASHX8DrLbgeR8FcfNV1F5krxYcYMUdBkrP1EPBtxB9`. 2 is a System transfer of 10,000,000 lamports (a tip).
- **Inside outer instruction 1** (stack height 2 or deeper):
  - Inner 0: the Pump.fun program `6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P` `buy`. The data is 24 bytes: discriminator `66063d1201daebea` (Anchor `global:buy`), then token amount u64, then maximum SOL cost u64.
  - Inner 1: the Pump fee program `pfeeUxB6jkeY1Hxd7CsFCAjcbHA9rWtchMGdZ6VojVZ`.
  - Inner 2: a Token-2022 (`TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb`) `transferChecked` from the curve's token account to the wallet's.
  - System transfers for the protocol fee (two recipients) and the creator fee.
  - Inner 7: Pump.fun's self-invoked event instruction through its event authority `Ce6TQqeHC9p8KetsN6JsjHK7UTZk7nasjjnr7XxXp9F1`. Its data starts with Anchor's event-CPI tag `e445a52e51cb9a1d`, then the `TradeEvent` discriminator `bddb7fd34ee661ee`.
- **The `TradeEvent` prefix**, little-endian: `mint` (32 bytes), `sol_amount` u64, `token_amount` u64, `is_buy` u8, `user` (32 bytes), `timestamp` i64, virtual/real SOL and token reserves (4 × u64), `fee_recipient` (32 bytes), `fee_basis_points` u64, `fee` u64, `creator` (32 bytes), `creator_fee_basis_points` u64, `creator_fee` u64. More bytes follow; they are ignored.
- **Values:**
  - mint `5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs`, 6 decimals, Token-2022
  - `sol_amount` 244,444,444
  - `token_amount` 1,238,520,682,656
  - `is_buy` 1, `user` = the wallet
  - fee 2,322,223 (95 bps), creator fee 733,334 (30 bps)
- **Cross-checks:**
  - The wallet-owned token account for the mint rose by exactly `token_amount` (pre and post token balances).
  - The wallet spent 265,005,001 lamports in total: curve payment 244,444,444, Pump fees 3,055,557, router fee 2,500,000, tip 10,000,000, and network fee 5,005,000 (priority 5,000,000 plus base 5,000).
- **Not verified:** a marker account `jitodontfrontB1111111TradeWithAxiomDotTrade` suggests the Axiom terminal.

## Decision

Support exactly one route: **a successful Pump.fun bonding-curve `buy` signed by the tracked wallet**. It may be called top-level or from any router program. A transaction is supported only when all of these hold:

1. Its capture outcome is `UNCLASSIFIED`: successful, the wallet signed, structure valid.
2. It contains exactly one Pump.fun instruction whose data is ≥ 24 bytes and starts with `66063d1201daebea`.
3. It contains exactly one Pump.fun event instruction: invoked by Pump.fun, data starting `e445a52e51cb9a1d` then `bddb7fd34ee661ee`, and long enough for the prefix above. Its `is_buy` is 1 and its `user` is the tracked wallet.
4. The buy and the event sit under the same top-level instruction, and the buy's token-amount argument equals the event's `token_amount`.
5. `token_amount` and `sol_amount` are both positive and within u64.
6. Summed over the wallet-owned token accounts for the event mint, the change in token balance equals exactly `token_amount`. All those balances report the same decimals, which become the base decimals.

The event is read from the event instruction, not from `Program data:` log lines. Logs can be truncated and can be written by any program. The instruction is attributable to the Pump.fun program. Rule 3's "invoked by Pump.fun" needs no stack-height check. Anchor's event instruction requires Pump.fun's event-authority PDA as a signer, which only Pump.fun can provide through a signed self-call. In a successful transaction, any Pump.fun instruction carrying the event tag must therefore be that self-call.

**Representation.** The decoder emits one `WalletTransaction` per supported transaction:
- `instructionIndex`: the top-level instruction that contains the buy.
- `pair`: base = event mint, quote = `MintAddress.WRAPPED_SOL`.
- `baseDelta` = +`token_amount`; `quoteDelta` = −`sol_amount`.
- `baseDecimals` from the token balances; `quoteDecimals` = 9.
- `successful` = true, `signer` = true, `supportedRoute` = true.
- `source` = `pumpfun-bonding-curve-buy/v1`; `commitment` = confirmed.
- slot and block time come from the capture.

One supported trade per transaction makes signature plus top-level instruction index a collision-free identity.

**Quote asset.** Native SOL paid to the bonding curve is represented as the wrapped SOL mint. Lamports and wrapped SOL units are 1:1 with 9 decimals. This is a representation choice; no on-chain wrapping happens. It keeps the accepted default quote and simulation configuration unchanged.

**Price basis.** The observed trade is the curve payment against the tokens received, so the implied price is `sol_amount / token_amount`, scaled by decimals (the existing scale 38, HALF_EVEN where a price is derived). Pump protocol and creator fees, router fees, tips and network fees are excluded from the trade amounts. The simulator applies its own fee/slippage model per [ADR 0001](0001-fixture-simulation-semantics.md) and [configurable runs](20261002-configurable-replay-runs.md). Fees are not double-counted, and the result is not Decu's actual all-in cost.

**Every other transaction** keeps an explicit, non-trade decode outcome. Nothing is fabricated:
- `NOT_SIGNER`: capture outcomes `NON_SIGNER`, `FAILED`, `UNAVAILABLE`, `UNSUPPORTED_VERSION` and `WITHHELD_UNSAFE_TEXT` map through to their own reasons.
- `UNSUPPORTED_ROUTE`: no Pump.fun buy or event, a Pump.fun `sell`/other instruction, or `is_buy` = 0.
- `AMBIGUOUS`: more than one buy or event, a different event user, an amount mismatch, mismatched balances or decimals, or a mismatched top-level instruction.

## Alternatives

- **Opposing balance changes:** inferring swaps from them is prohibited by the plan; this decoder still requires them to agree, as a cross-check.
- **`Program data:` log events:** weaker attribution, and truncatable.
- **Accepting only the observed router:** too narrow, since the same Pump.fun buy can come through other terminals.
- **A separate native SOL quote:** would need replay format and console changes.
- **Pricing including Pump fees or all-in wallet cost:** double-counts fees once the simulator applies its own model, and is not a market price for other traders' observations.

## Consequences

Only Pump.fun bonding-curve buys from this wallet become strategy inputs. Pump.fun sells, graduated-pool (PumpSwap/AMM) trades, other DEXes and multi-trade bundles stay explicit unsupported or ambiguous gaps until new evidence justifies another decoder. Historical market observations (unit 3) can reuse the same event identity to extract same-mint bonding-curve trades by any user. Discovery evidence shows the wallet's own trades are rare among its references (1 of 6,911 in about 40 seconds), so a dataset with several entries likely needs a different history scope. That is a separate user decision.

## Revisit when

Pump.fun changes its `buy` or `TradeEvent` layout, or begins requiring a different instruction such as `buy_exact_sol_in` (`38fc74089edfcd5f`), which is not supported. Also revisit when evidence shows Decu trading through sells, PumpSwap or another route, or when a different quote or price basis is chosen.
