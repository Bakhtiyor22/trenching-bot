# Console charts

Run and dataset detail pages use the recorded `/api/runs/{id}/chart` and
`/api/datasets/{id}/chart` responses. A Mcap line is the default, with a candle
toggle and visible gaps; **Price (SOL)**
keeps the strategy's P0, dip, take-profit and stop-loss levels on run charts.
The chart metric is independent of the run's SOL/USDT summary switch.

The valuation information popover shows the backend's supply and rate provenance. Missing
rates and unsupported valuations are counted separately, and candles never join
across missing data. If valuation is unavailable, its reason is shown and the
chart uses price. Old schema v3 recordings explicitly report that trader data
was not recorded; uploads support schemas v3 and v4.

The tracked wallet is pinned. Add or remove up to 19 other base58 wallet
addresses in the watchlist; the browser stores it locally. The requests send
repeated `wallet` parameters. The **Layers** popover contains the watchlist,
Paper fill toggle, and **All captured traders**, which omits the wallet filter.
Decu actions use amber B/S pins and Paper fills use blue diamonds. Their real
buttons open anchored cards with exact amounts and recorded transaction links.
The stepper and arrow keys visit Decu actions; Escape closes the card. Selected
actions use `action` or `fill` URL parameters. **Open action** and **Open fill**
jump to the existing cycle or stored-record details.

Cycle, 5m, 15m, 1H and All choose the visible time window. Ctrl + wheel and
dragging zoom or pan with debounced requests; every opaque marker cursor is
followed while first-page clusters are retained. Out-of-range selected actions
close; deep-linked actions outside the default Cycle range open in All.
Nearby Decu pins stack and offer a list and zoom action. Hover updates the
headline value, change and time. Fewer than three market points get a sparse
data state instead of candles. Select a cluster count to narrow its range.

## Cluster contract limitation

The delivered cluster contract has `firstTime` and `firstSlot`, but no first
trade price or Mcap. Two trades can share a slot and timestamp. Consequently,
clusters are placed at their actual first time on a separate **Trade times**
rail, with an explicit price-unavailable note. Exact price/Mcap anchoring needs
the backend to include those coordinates. The frontend never substitutes a
candle open or another trade's price. This is the sole rendering deviation from
the chart brief.

## Local development and verification

From this directory:

```powershell
$env:VITE_USE_MOCKS = 'true'
npm run dev
```

Configuration comes from the process environment; Vite's environment-file
loading is disabled. `VITE_API_TARGET`, when set locally, overrides the default
backend target. Never print secret configuration values.

```powershell
npm test
npm run lint
npm run build
```

If a Windows sandbox isolates temporary directories between test workers, use
a workspace temporary directory for that shell before running the same commands:

```powershell
$chartTestTemp = Join-Path (Get-Location) 'node_modules/.cache/chart-verification-tmp'
New-Item -ItemType Directory -Path $chartTestTemp -Force | Out-Null
$env:TEMP = $chartTestTemp
$env:TMP = $chartTestTemp
```

Fixtures include v3 and v4 charts, clustering, rate gaps and unavailable
valuation. Tests cover exact decimal details, requests, cursor cancellation,
chart rendering and page integration. No database schema changes are required.

Browser inspection against the mocked local dev server was unavailable because
the browser connection timed out. ECharts rendering is covered by the SVG
integration test instead.

Verification on 2026-10-16: all 212 tests passed across 33 files; lint passed
with zero warnings, and the production build passed.
