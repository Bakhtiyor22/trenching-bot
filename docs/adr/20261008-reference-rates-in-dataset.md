# SOL/USDT reference rates in replay datasets

Accepted 2026-10-08 with the [dip-entry strategy plan](../plans/archive/20261008-dip-entry-strategy.md). The user chose "rate stored in dataset" for the $10,000 market-cap rule. This amends the [SOL/USDT display conversion decision](20261005-sol-usdt-display-conversion.md): its revisit trigger, "USDT values are needed in ... datasets or simulation", fired.

## Decision

- **Rates join the dataset.** Replay dataset schema v2 carries `referenceRates`: one `{minute, rate, source}` per UTC minute in which an observation trades, plus `curveComplete` on each observation.
  - The checksum covers both.
  - The loader refuses v1 (`UNSUPPORTED_SCHEMA`), and refuses an observation whose minute has no rate (`REFERENCE_RATE_MISSING`).
  - A run reads rates only from the dataset and never calls a rate source.
- **The rate.** It is the Binance SOLUSDT 1-minute kline open, as in the display decision. Only the dip-trigger market cap uses it, and nothing else in the simulation.
- **Capture is user-run.**
  - `captureReferenceRates` reads a verified market capture, collects every in-window trade's minute, and asks the display rate source for the missing ones. It uses the same requests, timeouts, rate-limit and region-block handling.
  - It writes a hash-enveloped `rates-manifest.json` of minute, rate and source, or the reason a minute has none.
  - A rerun asks only for minutes still without a rate.
  - `inspectReferenceRates` verifies it offline. The agent never runs the capture.
- **Export joins rates offline.** `exportRecordedReplay` takes `-PratesCaptureDirectory` and refuses `REFERENCE_RATES_INCOMPLETE` if any trade's minute lacks a rate.
- **Evidence kept.** The manifest keeps the parsed open of each minute, not Binance's raw response body. This deviates from the plan's raw-artifact wording, because the open is the only field used.

## Alternatives

- **A fixed SOL threshold per run:** offline and simple, but not $10,000 when SOL moves.
- **Fetching rates at run time:** a rerun could differ, and it breaks the no-network-in-a-run rule.
- **Raw kline bodies as artifacts:** more evidence, but it duplicates the tested rate source's HTTP handling for fields that are never read.

## Consequences

A dataset is only complete with its rates. Re-exporting an old capture needs a rate capture first. The console's USDT display view still asks Binance on request; it could read the dataset's rates instead later.

## Revisit when

- Pyth or another source replaces Binance (for example, if Binance is region-blocked for the user);
- rates are needed for anything beyond the market-cap rule.
