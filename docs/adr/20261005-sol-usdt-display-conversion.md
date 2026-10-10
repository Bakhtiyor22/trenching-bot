# SOL/USDT display conversion

Accepted 2026-10-05 with the [USDT view and market links plan](../plans/archive/20261005-usdt-view-and-market-links.md). The user chose the rate basis (each trade's own time), the source (Binance public API) and the link targets in chat.

## Context

Runs over recorded Pump.fun data are quoted in wrapped SOL. Their prices are around 0.0000002 SOL and their P&L is in SOL, which is hard to read in dollars. The simulation, its datasets and its checksums are defined in the quote unit ([ADR 0001](0001-fixture-simulation-semantics.md), [configurable runs](20261002-configurable-replay-runs.md)). A dollar view must not change any of them.

## Decision

- **Display only.** The console converts a stored wrapped-SOL run to USDT on request (`GET /api/runs/{id}/usdt`). Nothing is stored, hashed or fed back. Run identity, the report, `resultChecksum`, the dataset checksum and every stored row are unchanged.
- **Wrapped SOL only.** A run with any other quote mint is `NOT_SOL_QUOTED`. So is an observation of another quote within a wrapped-SOL run's dataset: it is not converted, and its minute is not looked up.
- **Rate at each trade's own minute.** Each value uses the open price of Binance's `SOLUSDT` 1-minute kline for the minute containing the relevant block time:
  - an observation uses its own `observedTime`;
  - a fill uses its fill observation's time;
  - a close uses its close observation's time.

  The open is known at the start of the minute, so nothing later in that minute enters the conversion. Moves within a minute are not resolved, and the console says so.
- **Exact arithmetic.**
  - The rate is Binance's decimal string, parsed exactly.
  - Prices = SOL price × rate, at scale 38 (`DecimalPolicy.price`).
  - Amounts = SOL amount × rate, at scale 12 (`DecimalPolicy.scale`). Both round HALF_EVEN.
- **Per-leg P&L.** USDT P&L = net proceeds × close-minute rate − cost basis × fill-minute rate, each already at scale 12. It includes SOL's own move between entry and exit. The run total is the exact sum over closed positions.
- **Provenance.** Each converted value carries its rate and minute. Scenario cards, chart tooltips and observation prices show them, and the page says that moves within a minute are not resolved.
- **No substitution.** A missing rate leaves that value null with its reason: `NO_MARKET_DATA`, `OUT_OF_RANGE`, `RATE_LIMITED`, `REGION_BLOCKED`, `TIMEOUT`, `UNAVAILABLE`, `MALFORMED` or `REQUEST_LIMIT`.
  - Nothing is interpolated, taken from a neighbouring minute, or replaced by the current rate.
  - A position missing either leg has no USDT P&L, and the total is then null (`PARTIAL`) rather than a partial sum.
- **Source behaviour.**
  - Requests are read-only public REST, with no key, no retries, and timeouts of 5 s to connect and 10 s to read.
  - Contiguous minutes are grouped into one klines request of up to 1,000 minutes, with at most 20 requests per lookup.
  - Completed minutes are cached in memory. The current price comes from the ticker and is cached for 30 seconds.
  - After a rate limit (HTTP 429/418) or region block (451/403), every lookup returns that reason for the same 30 seconds without calling Binance. Binance bans IPs that keep calling after a 429.
  - The source is disabled unless `marketdata.reference-rates.enabled=true`; the local profile enables it. `prod` leaves it disabled and the console is off there.

## Alternatives

- **Today's rate for everything:** simple, but it shows past trades at a value they never had.
- **The kline close, or 1-second klines:** the close uses information from later in the minute. 1-second klines' historical retention was not verified.
- **SOL P&L × close rate:** hides the FX move between the legs.
- **Pyth or CoinGecko:** Pyth is SOL/USD rather than USDT, and CoinGecko's free tier has coarser history and rate limits. Pyth remains the fallback if Binance is blocked in the user's region.
- **Persisting rates:** not needed for display, and it would add schema.

## Consequences

USDT values are a reading aid, not simulation results. Two runs with identical SOL results show identical USDT values only when Binance returns the same klines. The SOL view never depends on Binance being reachable.

## Revisit when

- USDT values are needed in reports, datasets or simulation;
- a quote other than wrapped SOL needs conversion;
- intra-minute accuracy matters;
- Binance becomes unreachable for the user.

Update 2026-10-08: the trigger "USDT values are needed in ... datasets or simulation" fired. The [reference-rates decision](20261008-reference-rates-in-dataset.md) stores the same 1-minute opens in replay datasets for the dip-entry market-cap rule. The display view described here is unchanged.
