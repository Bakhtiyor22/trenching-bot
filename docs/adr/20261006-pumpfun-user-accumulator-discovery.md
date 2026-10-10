# Pump.fun user-accumulator wallet discovery

Accepted 2026-10-06 with the [Pump.fun user-buy discovery plan](../plans/archive/20261005-pumpfun-user-buy-discovery.md), approved by the user in chat. It adds a scope to [successful-reference discovery](20261003-successful-reference-discovery.md) and does not replace it.

## Context

Successful-reference discovery lists `getSignaturesForAddress(wallet)`. For Decu's wallet two user-run captures found:

- 2026-10-03: 6,911 entries listed, 100 successful references selected, and 1 wallet-signed buy;
- 2026-10-05: 5,449 entries listed, 100 selected, and 0 signer transactions (`NON_SIGNER=100`).

Offline analysis of those artifacts showed why. In every non-signer transaction, the wallet is a read-only, non-signer account in another fee payer's program. One third-party program accounts for 83 and 96 of the 100 transactions, and one fee payer for 67 in each. These are bots watching the wallet. The discovery ADR's revisit trigger ("successful copy-bot traffic dominates the selection") fired. No agent contacted the provider; the evidence is the user's capture.

The one decoded buy passes Pump.fun's `buy` its user volume accumulator as account 13: `Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D`. Re-derived offline, it is PDA(["user_volume_accumulator", wallet], `6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P`), bump 255. None of the other 199 captured transactions contains it.

## Decision

- **A second wallet-capture scope.** `PUMPFUN_USER_VOLUME_ACCUMULATOR` (command `--scope pumpfun-user-buys`) lists the wallet's derived user volume accumulator instead of the wallet. The user still supplies only the wallet. The address is derived offline, with SHA-256 and an ed25519 curve check (JDK only, `protocol.solana.ProgramAddress`).
- **The rest of the capture is unchanged.**
  - Discovery still selects only successful entries.
  - Bounds, cursor, resume, budgets, transaction checks, unsafe-text withholding and artifacts work as before.
  - A buy is still accepted only when the [decoder](20261003-pumpfun-bonding-curve-buy-decoder.md) proves a Pump.fun `buy` signed by the wallet.
  - Market capture and export are scope-agnostic.
- **Contradictions and gaps.**
  - A listed transaction without the listed address among its accounts contradicts the listing and is `INVALID_TRANSACTION`, in both scopes.
  - A transaction listed under the accumulator but without the wallet is the resolved gap `WALLET_ABSENT`, with no artifact. Anyone can pass any account, so this is noise rather than a contradiction, and it must not leave the capture incomplete on every rerun.
- **Manifest schema version 3.** It adds `scope` and `listingAddress`, which inspection re-derives and checks; a mismatch is refused with `LISTING_ADDRESS`.
  - Version 2 is still read, as `WALLET_REFERENCES` with the wallet as listed address; a version 2 manifest carrying either field is refused.
  - A resumed version 2 capture is saved as version 3.
  - Version 1 stays refused.
  - Resume also requires the same scope.

## Alternatives

- **A deeper wallet-reference scan:** about 1 own buy per 7,000 references here. Ten buys would need about 70 pages, above the 50-page maximum, and about 1,300 detail requests on bot noise.
- **Token accounts (`getTokenAccountsByOwner`):** lists only open accounts, and Pump.fun traders usually close them after selling. It also needs a listing per mint.
- **Helius filtered history:** a vendor contract, already rejected by the discovery ADR.
- **A fixed accumulator address supplied by the user:** error-prone and unverifiable. Deriving it ties it to the wallet.

## Consequences

- **Coverage.** The scope finds only Pump.fun activity that passes the accumulator, so only buys made after Pump.fun introduced it. PumpSwap, other DEXes and `buy_exact_sol_in` are listed or not as the program decides, and they do not decode.
- **Sells and claims.** If Pump.fun sells or incentive claims also pass the accumulator, they fill part of the limit as `UNSUPPORTED_ROUTE`. The audit shows it.
- **Market capture cost.** Every decoded buy becomes a market window. Market capture cost grows with each mint's later activity: one day-old busy window took 216 requests. So the capture docs recommend `-Plimit=10` and running market capture straight after.
- **Export.** It still refuses the whole dataset if any window is unusable.
- **Existing captures and datasets.** They are unaffected: the version 2 capture re-exports to the same dataset checksum.

## Revisit when

- The wallet's buys move to another route;
- Pump.fun stops passing the accumulator to `buy`, or changes its seeds;
- `WALLET_ABSENT` or sells dominate a capture;
- one unusable window keeps blocking multi-buy exports, which needs a separate export decision.
