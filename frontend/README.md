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

Cycle, 5m, 15m, 1H and All choose the visible time window. Ctrl/⌘ + wheel zooms
around the cursor in an embedded chart; the chart page and expanded view use
plain wheel. Drag pans; double-click or Fit shows the whole extent. Reset returns
to the cycle. Focus the plot for +/− zoom, ←/→ pan, 0 reset and F fit.
Every request asks for up to 5,000 execution-ordered ticks. Complete tick windows
zoom and pan locally; larger windows debounce visible-data requests by 250 ms
while retaining previous data. Opaque marker cursors retain the first page's ticks
and clusters. The visible window, metric, style, layers and selected action survive
reloads and the Open chart/Back to run links through URL parameters.
Nearby Decu pins stack and offer a list and zoom action. Hover updates the
headline value, change, estimated UTC instant, slot and position within its slot.
Ticks and markers share the backend's estimated sub-second coordinates; cards
retain whole-second block time. A missing capture stretch after a tracked buy is
shown as a dashed “unrecorded” connector and explained in its card. The execution
marker stays at its actual execution value. Candle-line fallback closes appear
at bucket end, and the y-axis fits the visible data. The resolution note explains
slot timing and any unproven execution order. Select a cluster count to narrow its range.

Pointer, Trend line and Measure tools store data-coordinate drawings per pair in
`trenching.chart.drawings.v1:{kind}:{id}:{pair}`. Points snap within 8 px; Alt places
free points. Select a drawing to move its endpoints or body, then Delete/Backspace
or its Delete button removes it. Clear all asks for confirmation. Labels show
signed percent, value and time changes, including a dash for percent from zero.
Storage failure leaves drawings usable for the current visit and shows a status.
Expand keeps the chart mounted in a full-page layer; Exit full page or Escape
returns with the same window, metric, style, layers, action and drawings.

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

The dev-only `?mockState=chart-first-slot-gap` chart preview reproduces the
reference $9.4k execution marker and $13.2k first recorded trade. The actual
reference capture still requires checking against a backend process serving the
new contract. Until that process is restarted, the frontend accepts the previous
contract, uses bucket-end candles and whole-second markers, and reports that
positions inside a second are unknown.
