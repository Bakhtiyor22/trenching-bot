# Codex brief: wallet markers and Mcap chart

You work only under `frontend/`. Backend Parts 1 to 3 add the data below; develop against mock handlers (`frontend/src/mocks/`). Keep the terminal theme, Mantine and ECharts already installed. Amounts and prices are decimal strings; convert to numbers only for plotting (`toChartNumber`), show exact strings in tooltips.

Part 4 of the archived [chart data plan](archive/20261015-chart-trade-actors-mcap.md). The backend (Parts 1–3) is delivered on `develop`; build against the real endpoints or the mocks.

**HTTP contract** (owned by backend Part 3): `GET /api/runs/{id}/chart` and `GET /api/datasets/{id}/chart`, query: `pair=<base>/<quote>` (required for datasets and for runs with several pairs), `from`/`to` (ISO instants, default whole range), `maxBuckets` (default 300, server cap 2000), `markerLimit` (default 500, cap 2000), `wallet` (repeatable, at most 20, base58), `cursor` (opaque, from `markers.nextCursor`). Errors use the existing console error body (400 invalid request, 404 unknown run or dataset).
- `bucketSeconds` (int), `range {from,to}`, `trackedWallet` (string or null), `valuationBasis {label, supply, supplySource, quoteCurrency:"USDT", rateSource, rateGranularity, available, unavailableReason}` with `unavailableReason` one of `UNSUPPORTED_TOKEN`, `NOT_SOL_QUOTED`, `RATES_UNAVAILABLE` or null.
- `candles[]` (only buckets with data; absent buckets are gaps): `bucketStart`, `observationCount`, `price {open,high,low,close}` in SOL per token, `mcap {open,high,low,close}` in USDT or null, `mcapMissingPoints` (int), `mcapUnsupportedPoints` (int).
- `markers {available, unavailableReason ("NOT_RECORDED_BEFORE_SCHEMA_V4" or null), mode ("ITEMS"|"CLUSTERED"), total, items[], nextCursor, clusters[]}`. Item: `kind:"OBSERVED"`, `observationId`, `trader`, `isTrackedWallet`, `side` (BUY|SELL), `solLamports`, `tokenRaw`, `price`, `mcap` (or null), `slot`, `time`, `signature` (or null), `source`. Cluster: `bucketStart`, `count`, `buyCount`, `sellCount`, `firstSlot`, `lastSlot`, `firstTime`, `lastTime`. In CLUSTERED mode `items` holds only the tracked wallet's trades.
- `paperFills[]` (runs only): `kind:"PAPER"`, `phase` (ENTRY|EXIT), `side`, `intentId`, `slot`, `time`, `price`, `mcap` (or null), `quantity`, `reason` (exit reason or null).
- `ObservationResponse` gains `trade` (`{trader, side, solLamports, tokenRaw}` or null) on the existing `/observations` endpoints.

**Contract details as implemented** (backend Part 3, commit `a00471f`):
- `range` echoes the requested `from`/`to`, else the first and last observation time.
- Bucket ladder (seconds): 1, 5, 15, 30, 60, 300, 900, 3600, 14400, 86400; the smallest that fits `min(maxBuckets, 2000)` buckets, epoch-aligned on observation time.
- `cursor` is opaque (do not parse it). Paging applies to the tracked wallet's `items` only; in CLUSTERED mode `clusters` are sent on the first page only and later pages carry `items` only. `items + Σ clusters.count == total` holds over all pages; on the first page alone it holds only when the tracked wallet has at most `markerLimit` trades, so keep following `nextCursor` until it is null to show every tracked trade.
- `mcapMissingPoints` counts points whose minute has no recorded rate; `mcapUnsupportedPoints` (additive per-candle integer) counts points whose valuation is unavailable for another reason, e.g. a failed decimals cross-check. Show both in the gap note. `valuationBasis.available=false` only when no point can be valued.
- A paper fill's `mcap` values the observation the fill used, not the fill's execution price.
- `unavailableReason` precedence: `NOT_SOL_QUOTED` before `RATES_UNAVAILABLE`.
- `rateSource` may list several sources joined by a comma.
- `markers.available=false` only when no observation in the data carries a trade.

**Behaviour** (approved plan section 9):
- Circular wallet markers at the trade's time and coordinates; ring plus letter B or S so colour is not the only cue; deterministic identicon or saved alias from an address (never infer identity). Tooltip or selection panel: address, side, SOL amount (lamports / 1e9), token amount (raw / 1e6), time, slot, source, transaction link (`MarketLinks`, only when `signature` is non-null). Keyboard users and tapped markers on mobile get the same details in a panel.
- Default layer: the tracked wallet (pinned, always shown, selection kept across zoom) plus a user-edited watchlist (local storage; sent as `wallet` params). Optional "all captured traders" layer: no `wallet` param, rendered as clusters when `mode` is CLUSTERED, anchored at the bucket's real first trade coordinate with a count badge; expansion uses the next zoom's request. Re-request with `from`/`to` from the data zoom (debounced).
- PAPER markers (`paperFills`) use a different shape (diamond) and a "Paper" label, never the observed markers' look.
- Left axis is Mcap (compact: $10k, $1.2m, $3.4b), candles from `candles[].mcap`; price candles in SOL stay available as a toggle. Legend and tooltip show `valuationBasis.label`, supply, rate source and granularity. If `available=false`, show the reason and fall back to the price axis. Gaps stay visible: no interpolation, no lines across absent buckets or null mcap; show "n points without a rate" from `mcapMissingPoints`.
- When `markers.available=false`, show "Trader data not recorded for this dataset (v3)" instead of an empty layer.

**Files**: `frontend/src/api/types.ts` (chart types, `Observation.trade`), `api/endpoints.ts`, `api/queries.ts` (`useRunChart`, `useDatasetChart`), `components/Chart.tsx` (register candlestick and scatter/custom series), `charts/priceChart.ts` (candle/mcap helpers, compact axis label), `features/runs/timelineChart.ts` and `RunDetailPage.tsx` (time-axis Mcap chart; level lines kept in the price view), `features/datasets/DatasetDetailPage.tsx`, `features/datasets/DatasetUpload.tsx` (text: schema v3 or v4), `components/ObservationTable.tsx` (optional trader column), `mocks/handlers.ts` and `mocks/fixtures.ts` (v3 run, v4 run, clustered case, rate gap, unavailable valuation).

**Tests**: unit tests for the compact mcap formatter, candle option building (gaps, no connecting lines), marker option building (B/S ring, tracked pinned, paper distinct) and cluster anchoring; component tests for RunDetailPage and DatasetDetailPage covering the basis legend, the v3 unavailable message, the watchlist request params and the error state. Update `Chart.test.tsx`, `priceChart.test.ts`, `timelineChart.test.ts` and both page tests.

**Verify**: from `frontend/`, `npm test`, `npm run lint`, `npm run build`, all clean (`--max-warnings 0`).
