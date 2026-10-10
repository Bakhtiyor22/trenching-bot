# USDT view and market links in the console

> Show a SOL-quoted run's prices and P&L in USDT at each trade's own time, using Binance's public SOL/USDT minute rates for display only, and link every real trade and token to Solscan, Solana Explorer, pump.fun and DexScreener.

Status: Approved (2026-10-05, user approved in chat, with the local enable line in `application-local.yml`)

## Context

The console shows a run's prices, fills, closes and P&L as exact decimal strings in the pair's quote units. For the real Decu dataset (`3a477117…`) the quote is wrapped SOL, so a price reads like `0.00000019870174082519143559411678139870` SOL and P&L like `-0.469035946332` SOL. The user wants to:

1. switch the run page and its price chart between SOL and USDT;
2. see the current SOL price;
3. open each real transaction and token on outside market sites.

The user has already decided three things:
- convert each value with the SOL/USDT rate **at that trade's time**, not today's rate;
- source the rate from the **Binance public API** (no key);
- show **all four** link types: Solscan and Solana Explorer transactions, pump.fun and DexScreener token pages.

Current owners:
- **Run detail and record endpoints:** `console` serves them (`RunController`, `ConsoleService`, `RunResponseMapper`, `ObservationResponseMapper`; DTOs in `console/api/dto`).
- **Observations:** they come from `marketdata`'s `MarketObservationStore`, and each carries `observedTime`, the block time.
- **Run page:** the frontend draws it in `features/runs/RunDetailPage.tsx`, `ScenarioCard.tsx`, `RunTables.tsx`, `timelineChart.ts` and the shared `components/ObservationTable.tsx` and `charts/priceChart.ts`.
- **Decimal handling:** `lib/decimal.ts` only formats exact decimal strings. It has no exact multiplication, so conversion belongs in the backend.
- **The existing outbound HTTP pattern is the read-only Solana probe** (`console/solana`): a `RestClient` over the JDK client with connect and read timeouts, no retries, URL-free failure categories and a short cache.

The [real wallet replay plan](20261003-real-wallet-replay.md) is delivered and archived.

## Scope

### In scope

- A `marketdata` port for SOL/USDT reference rates, with a Binance adapter (1-minute klines and the current ticker), a disabled adapter and a fake for tests.
- A `console` endpoint that returns a run's USDT view: every observation price, fill and close amount, and the realized P&L total. Each value is converted at its own minute, with rate provenance.
- A `console` endpoint for the current SOL/USDT price.
- Run page: a SOL/USDT switch for the totals, scenario cards, timeline chart and observation table, plus a current SOL price badge.
- Links:
  - Solscan and Solana Explorer transaction links for each real source buy, fill observation and exit observation (and observation table rows).
  - pump.fun and DexScreener links for each real token.

### Out of scope

- Any change to simulation, datasets, the v1 format, checksums, run identity, stored records or reports. USDT never feeds the engine.
- Persisting rates.
- USDT on the dataset detail page.
- Quote mints other than wrapped SOL.
- Other rate sources (Pyth, CoinGecko).
- Embedding outside charts, or fetching anything from the linked sites.
- Production enablement. The console is off in `prod`.

## Key Decisions

1. **Display-only conversion.** USDT values are derived on request from stored SOL values and are never stored, hashed or fed back. The run, report and checksums stay byte-identical. New ADR: `docs/adr/20261005-sol-usdt-display-conversion.md`.
2. **Rate at the trade's time, minute resolution.** Each value uses Binance `SOLUSDT` 1-minute kline data for the minute containing the relevant block time, taking that kline's **open** price.
   - The open is known at the start of the minute, so no later information enters the conversion.
   - It cannot resolve moves within a minute, which the UI says.
   - Rejected: the close (look-ahead within the minute), interpolation, and 1-second klines (unverified historical retention).
3. **Per-leg P&L conversion.** USDT P&L = (net proceeds × rate at the close observation's minute) − (cost basis × rate at the fill observation's minute). This is what the trade was worth in dollars at each leg, including SOL's own move between fill and close.
   - Rejected: SOL P&L × the close rate, which hides that FX move.
   - For the real run every leg falls in the same minute, so both agree there.
4. **Binance public REST, read-only, no key.**
   - Endpoints: `GET /api/v3/klines?symbol=SOLUSDT&interval=1m&startTime=…&endTime=…&limit=1000` and `GET /api/v3/ticker/price?symbol=SOLUSDT`.
   - Price strings are parsed to `BigDecimal` exactly.
   - USDT is treated as the dollar unit, not converted to USD.
5. **Ownership.**
   - `marketdata` owns the rate port and adapter, since it is market data.
   - `console` owns the endpoints and the conversion view, because it already joins runs with observations.
   - Links are built in the frontend only, from public signatures and mints.
6. **Links only for recorded mainnet captures.** Links appear only on records whose `source` is `pumpfun-bonding-curve-buy/v1` or `pumpfun-bonding-curve-trade/v1`. Those come from captures verified against the mainnet genesis.
   - The synthetic sample's zero signatures and other curated sources get no links, so no link points at a fake or devnet transaction.
   - The signature comes from the first segment of `sourceEventId`, as the observation ADR defines it, or from the event's own `signature` field.
   - It must decode from base58 to 64 bytes and the mint to 32 bytes, or no link is shown.
7. **Off unless enabled.** The rate source is disabled by default (`marketdata.reference-rates.enabled=false`). When disabled, the USDT view and the current price report `NOT_CONFIGURED` and the SOL view is unaffected. Enabling it for local use is an open question below.
8. **No new dependency.** It reuses spring-web `RestClient`, the JDK HTTP client and Jackson 3. The cache is a bounded in-memory map, not a new cache library.

## Module Contracts

All additive; no contract break.

**`marketdata` (public):**
- `interface SolUsdtRateSource`
  - `fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup>`. Keys are minute starts (UTC, seconds and nanos zero); every requested key is present.
  - `fun current(): RateLookup`
- `sealed interface RateLookup`
  - `data class Available(val rate: BigDecimal, val at: Instant, val source: String)` (`at` is the minute start, or the fetch time of a current price)
  - `data class Unavailable(val reason: RateUnavailableReason)`
- `enum class RateUnavailableReason { NOT_CONFIGURED, OUT_OF_RANGE, NO_MARKET_DATA, RATE_LIMITED, REGION_BLOCKED, TIMEOUT, UNAVAILABLE, MALFORMED, REQUEST_LIMIT }`
- `@ConfigurationProperties("marketdata.reference-rates") class ReferenceRateProperties`:
  - `enabled = false`
  - `baseUrl = "https://api.binance.com"`
  - `connectTimeout = 5s`
  - `readTimeout = 10s`
  - `tickerCacheTtl = 30s`
  - `maxCachedMinutes = 100_000`
  - `maxRequestsPerLookup = 20`

  These are validated in `init` like `SolanaRpcProperties`.
- Adapters:
  - `infrastructure/binance/BinanceSolUsdtRateSource`
  - `DisabledSolUsdtRateSource`
  - a test fake `FixedSolUsdtRateSource` (in tests)

**`console`:**
- `GET /api/runs/{id}/usdt` → `RunUsdtViewResponse`:
  - top-level fields:
    - `runId`
    - `rateSource` (`"binance:SOLUSDT:1m:open"`)
    - `available: Boolean`
    - `unavailableReason: String?` (whole view, e.g. `NOT_CONFIGURED`, `NOT_SOL_QUOTED`)
    - `observations: List<UsdtObservationResponse>`
    - `scenarios: List<UsdtScenarioResponse>`
    - `realizedPnlUsdt: String?`
  - `UsdtObservationResponse(id, minute, rate, priceUsdt, unavailableReason)`. The values are exact decimal strings or null.
  - `UsdtScenarioResponse(intentId, fill: UsdtFillResponse?, close: UsdtCloseResponse?)`:
    - the fill carries `minute`, `rate`, `executionPriceUsdt`, `costBasisUsdt`, `feeUsdt`, `unavailableReason`;
    - the close carries `minute`, `rate`, `executionPriceUsdt`, `grossProceedsUsdt`, `feeUsdt`, `netProceedsUsdt`, `realizedPnlUsdt`, `unavailableReason`.
  - Built in `RunUsdtViewService` (application) and mapped by a MapStruct `RunUsdtViewMapper`.
- `GET /api/market/sol-usdt` → `SolUsdtPriceResponse(rate: String?, asOf: Instant?, source, unavailableReason: String?)` via a new `MarketController`.
- Unknown run → the existing not-found error.

**Frontend:**
- `api.runUsdt(id)` and `api.solUsdt()` with types.
- `lib/marketLinks.ts`: `transactionLinks(signature)`, `tokenLinks(mint)`, `linkableSignature(record)`.
- A `QuoteCurrencySwitch` stored in the URL (`?quote=usdt`).

## Domain Rules

1. The USDT view never changes stored data. After any number of `/usdt` requests, the run's report bytes, `resultChecksum`, dataset checksum and every row count are unchanged.
2. The view is available only when the run's quote mint is wrapped SOL; otherwise `unavailableReason = NOT_SOL_QUOTED` and nothing is fetched.
3. **Which minute each value uses:**
   - observation price → `observedTime`'s minute;
   - fill amounts and execution price → the fill observation's `observedTime` minute;
   - close amounts → the close observation's `observedTime` minute.

   Minute = the block time truncated to the minute, in UTC. Wall-clock time and capture time are never substituted.
4. **Number types and rounding.**
   - The rate is the exact `BigDecimal` of Binance's string.
   - USDT prices = SOL price × rate, at scale 38, HALF_EVEN, the same as observation prices.
   - USDT amounts (cost basis, fee, proceeds, P&L) = SOL amount × rate, at scale 12, HALF_EVEN, the same as stored amounts.
   - USDT P&L = net proceeds USDT − cost basis USDT, each already at scale 12.
   - The run total is the exact sum of closed positions' USDT P&L.
   - No `Double` anywhere. The frontend only plots via `toChartNumber`.
5. **Hand-checked case.** With a fixed fake rate of 200 for every minute, the real run gives:

   | Entry | USDT P&L |
   |---|---|
   | +1 | −93.807189266400 |
   | +2 | −137.811670520200 |
   | +3 | −484.429355640000 |

   The total is −716.048215426600. The +1 fill execution price is 0.00003993904990586347855441747306113800 USDT.
6. **Missing rates are explicit, never filled in.**
   - Each value reports the reason when no rate applies. No interpolation, nearest-minute lookup or current-rate fallback.
   - A scenario's `realizedPnlUsdt` is null if either leg lacks a rate.
   - The run total is null with `PARTIAL` if any closed position lacks one; no partial sum is ever shown as a total.
7. **Rates that cannot exist:**
   - a minute before SOLUSDT's Binance history begins (before 2020-08-11), or a minute in the future → `OUT_OF_RANGE`, with no request;
   - a minute with no kline in Binance's answer → `NO_MARKET_DATA`;
   - the current, unfinished minute is never cached.
8. **Bounded fetching.**
   - Distinct minutes are grouped into contiguous ranges of at most 1,000 minutes, one klines request each.
   - At most `maxRequestsPerLookup` requests run per lookup; minutes beyond that are `REQUEST_LIMIT`.
   - Completed minutes are cached in memory, up to `maxCachedMinutes` with oldest-first eviction, so a repeat view makes no request.
   - The ticker is cached for `tickerCacheTtl`.
9. **Failures map to categories with no URL or message text, and no retries.**
   - HTTP 429/418 → `RATE_LIMITED`
   - 451/403 → `REGION_BLOCKED`
   - timeout → `TIMEOUT`
   - connection or other HTTP errors → `UNAVAILABLE`
   - unparseable body, non-positive price, or kline time not on a minute boundary → `MALFORMED`

   Failures are logged once at WARN with `reason=` and `requests=`. The SOL view always still works.
10. **Rate provenance.** Each rate carries its source string and minute. The UI shows them in tooltips ("Binance SOLUSDT 1m open, 17:47 UTC"), and the page states that USDT is a display conversion, not part of the simulation.
11. **Links.**
    - Only records from the two recorded-capture sources get links (Key Decision 6).
    - Links open in a new tab with `rel="noopener noreferrer"`; nothing is fetched from those sites.
    - Signature and mint values are validated and URL-encoded.
    - The synthetic sample shows no links.
12. **Determinism and idempotency.** For the same run and the same Binance data the view is identical. The view has no identity to make idempotent: it is a read with no side effects.

## Data Model

No schema change. No entity, table, column, index or production SQL. Rates live only in a bounded in-memory cache.

## Transactions & Integration

- `RunUsdtViewService` reads the run detail and observations through the existing read-only service calls, then requests rates **outside** any database transaction. No remote call happens inside a transaction.
- **Binance HTTP behaviour:**
  - one call at a time per lookup;
  - connect timeout 5 s and read timeout 10 s, no retries;
  - the configured `baseUrl` is not a secret and needs no key, but it is still never echoed in errors.
- **Rate budget:** Binance allows about 6,000 request weight per minute per IP. A klines request weighs 2 and the ticker 2, so the 20-request cap stays far below it.
- **Degradation:** if disabled or unreachable, the USDT switch shows the reason and the SOL view keeps working.
- **Configuration:** prefix `marketdata.reference-rates`, defaults above. It is wired only when the console is enabled, since nothing else uses it, so `prod` never calls Binance.
- **No live provider in tests:**
  - adapter tests use Spring's `MockRestServiceServer`, or a local stub on a random port, with recorded-shape JSON written by hand, not fetched;
  - the user does the first real check by enabling it locally.

## What To Implement

1. **Rate source.**
   - The `marketdata` port and value types.
   - The Binance adapter, covering kline parsing, minute grouping, the request cap, the cache, the ticker and failure categories.
   - The disabled adapter and the properties.

   Independent of the frontend.
2. **USDT view API.**
   - `RunUsdtViewService`: SOL quote check, per-minute lookup, exact conversion, per-leg P&L, null totals on gaps.
   - The `/api/runs/{id}/usdt` and `/api/market/sol-usdt` endpoints, DTOs and MapStruct mapper.

   Depends on 1.
3. **Run page SOL/USDT switch.**
   - The switch in the URL and the current SOL price badge.
   - Totals, scenario cards, observation table and timeline chart showing USDT values, with an axis label per currency, rate and minute in tooltips, and unavailable states.

   Depends on 2's contract and can start against MSW mocks in parallel with 1–2.
4. **Market links.**
   - `lib/marketLinks.ts` and link buttons on scenario cards, the event and observation tables, and the token header.

   Independent; can run in parallel with everything above.
5. **Docs and ADR:** the display-conversion ADR, README console section and run instructions.

## Verification

**Backend, test-first:**
- `BinanceSolUsdtRateSourceTest` must prove:
  - open-price selection;
  - exact `BigDecimal` parsing;
  - one request per contiguous range of up to 1,000 minutes;
  - the request cap (`REQUEST_LIMIT`);
  - cache hits make no second request;
  - the current minute is not cached;
  - `OUT_OF_RANGE` before 2020-08-11 and in the future, with no request;
  - a missing kline gives `NO_MARKET_DATA`;
  - 429, 418, 451 and 403, timeout and malformed bodies map to their categories;
  - error text never contains the base URL;
  - the ticker is cached for its TTL.
- `RunUsdtViewServiceTest` must prove:
  - the hand-checked values in Domain Rule 5 using the real dataset and a fixed rate of 200;
  - a different rate per minute converts each leg at its own minute;
  - a missing leg rate nulls that scenario's P&L and makes the total `PARTIAL`;
  - `NOT_SOL_QUOTED`;
  - `NOT_CONFIGURED` when disabled;
  - the report bytes and checksum are unchanged after viewing.
- `RunUsdtViewIntegrationTest` (PostgreSQL Testcontainers, fake rate source): the endpoint over the imported real dataset returns the hand-checked JSON strings, and row counts are unchanged.
- MVC tests for both endpoints, covering status codes, unknown run and JSON shape.

**Frontend (Vitest + MSW):**
- The switch persists in the URL.
- USDT values come from the API strings unchanged.
- Chart axis labels read "Price (SOL)" / "Price (USDT)".
- Unavailable reasons render.
- The badge shows the current price or the reason.
- `marketLinks` builds the four exact URLs.
- Links are hidden for synthetic and other sources and for invalid base58.
- Links use `target="_blank"` and `rel="noopener noreferrer"`.

**Commands**, run serially with Java 21 and Docker:
- `./gradlew build`
- `npm.cmd --prefix frontend run lint`
- `npm.cmd --prefix frontend test -- --run`
- `npm.cmd --prefix frontend run build`

## Documentation Sync

- `README.md` console section: the USDT switch, what it means, the enabling property and the links.
- New ADR `docs/adr/20261005-sol-usdt-display-conversion.md`.
- `docs/PROVIDER_SPIKE.md`: Binance adopted for display-only reference rates.
- `docs/IMPLEMENTATION_STATUS.md`.

## Acceptance Criteria

- With the rate source enabled and Binance reachable, the real run's page switched to USDT shows:
  - each price, fill, close and P&L converted at its own minute;
  - the rate and minute in tooltips;
  - the chart's axis labelled USDT;
  - a total that equals the sum of the three USDT P&Ls.
- Switching back to SOL shows exactly the current SOL strings.
- The run report download and `resultChecksum` `d0c746bb…` are unchanged after using the USDT view.
- With the source disabled, the switch shows `NOT_CONFIGURED`, and no outbound request appears in the logs.
- Opening the run twice in USDT makes no second Binance request for the same minutes (visible in the WARN/DEBUG request count).
- The real run shows working links for Decu's buy `35Vnpu9D…`, the six fill and exit transactions, and token `5bYUih…` on pump.fun and DexScreener.
- The synthetic sample's run shows none of those links.
- The default build makes no network call; all tests pass offline.

## Open Questions

1. **Resolved:** the user approved adding `marketdata.reference-rates.enabled: true` to `application-local.yml`.
2. **Binance reachability.** binance.com blocks some regions (HTTP 451). Your first local use confirms it.
   - If it's blocked, the recommended fallback is Pyth's benchmarks API (SOL/USD), as a separate small decision.
3. **Resolved:** the real wallet replay plan was archived first.
4. **Dataset page.** Recommendation: leave the dataset detail chart in SOL for now and add USDT there later only if wanted, so the conversion endpoint stays run-scoped.

## Progress

- **Implemented (2026-10-05):**
  - **Rate source:** `marketdata` `SolUsdtRateSource` with `BinanceSolUsdtRateSource`, `DisabledSolUsdtRateSource`, `ReferenceRateProperties` and `ReferenceRateConfiguration`. The local profile enables it, as approved.
  - **Conversion and endpoints:** `console` `UsdtConversion` (pure), `RunUsdtViewService`, `GET /api/runs/{id}/usdt`, `GET /api/market/sol-usdt` (`MarketController`) and the MapStruct `RunUsdtViewMapper`.
  - **Run page:** the SOL/USDT switch kept in the URL, the current price badge, USDT totals, scenario cards, observation table and timeline chart (axis "Price (USDT)").
  - **Links:** `lib/marketLinks.ts` adds links for recorded sources only, on scenario cards, the source buy, the token, and the observation and event tables.
  - **Decision record:** the display conversion ADR.
- **Deviations from the contract text:**
  - The view adds `realizedPnlUnavailableReason`, which carries `PARTIAL`, and a top-level `rateSource`.
  - The planned `available: Boolean` is not a field: the view is available exactly when `unavailableReason` is null.
  - The rate source bean is always wired, but it is `DisabledSolUsdtRateSource` unless `marketdata.reference-rates.enabled` is true. Only the local profile sets that, and only the console calls it, so `prod` never calls Binance.
  - The NOT_SOL_QUOTED check lives in `UsdtConversion.convert`, which takes the rate lookup as a function, so a non-SOL run never asks for rates.
- **Tests:**
  - `BinanceSolUsdtRateSourceTest` (10) and `ReferenceRateConfigurationTest` (2).
  - `UsdtConversionTest` (5): the real dataset at a fixed rate of 200, hand-checked.
  - `RunUsdtViewIntegrationTest` (2): PostgreSQL, the real dataset and a fake rate source, with the report and row counts unchanged.
  - Frontend: `marketLinks.test.ts` (5) and 5 new `RunDetailPage` tests.
  - `./gradlew build` passed with 355 tests and 0 failures. Frontend lint was clean, its 75 tests passed and the production build succeeded.
- **Review (2026-10-05):** the `backend-reviewer` found no blockers. Fixed:
  - observations of another quote in a wrapped-SOL run are now `NOT_SOL_QUOTED` rather than converted;
  - rate and minute provenance now appear in chart tooltips and observation prices, with a note that moves within a minute are not resolved;
  - SOL-mode chart tooltips keep their cost and net proceeds lines;
  - a rate limit or region block now backs off for 30 seconds instead of calling Binance on every poll;
  - the ticker field is `@Volatile`;
  - failure logs carry the exception class, never its message;
  - the fill leg resolves its observation like the close leg;
  - new tests cover a single missing leg, minutes 999 apart, cache eviction and the backoff;
  - `!!` is gone from the tests;
  - doc drift is fixed.
- **Open:** the user's first live check against Binance from the local console.


## Delivered

2026-10-10: SOL/USDT display and recorded-market links shipped. Implementation, accepted contract differences and review are recorded above; docs include README and the display-conversion ADR. Live Binance verification remains user-run.
