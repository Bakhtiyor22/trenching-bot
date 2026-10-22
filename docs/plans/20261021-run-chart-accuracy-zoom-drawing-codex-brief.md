# Codex brief: an accurate, second-level, zoomable run chart with drawing tools and a full-page view

Status: Ready for Codex, 2026-10-21. Frontend only; the backend part is already done (section 1). You work only under `frontend/`. Keep Mantine 9, ECharts 6, React Query and the dark terminal theme; add no dependency. Prices and mcaps stay decimal strings (`lib/decimal.ts`) everywhere except the plotted coordinates.

The reference case for every check below is run `fbd3425249f1c23bc212ecc28df230e8012eb5c090964140b273179128742b0b` (capture "Decu · 6Ez7…pump · 21 Oct 11:34", token `6Ez7jwuUoTr81akzQsBPjfGA67nRWoePEQUsKCUfpump`, 1,674 recorded trades over slots 459,490,744–459,495,064).

## 0. What is wrong today, verified against the source data

### 0.1 The first buy marker sits below the line — the marker is right, the line is missing the rest of its slot

Checked on 2026-10-21 against the capture case export and the live chart API:

| Fact | Value | Source |
|---|---|---|
| Decu's first buy | slot 459,490,743, block time 04:10:18 UTC, 1.955555555 SOL for 21,979,703.232124 tokens | wallet transaction `2gCgxYnv…` |
| Its execution price | 1.955555555 / 21,979,703.232124 = 8.8971e-8 SOL; × 1e9 supply × 105.98 SOL/USDT (04:10 minute) = **$9,429** | marker `price`, `mcap` in the API — exact |
| Curve right after it (derived from the constant product) | ≈ 9.228e-8 SOL ≈ **$9,780** | |
| First recorded trade | slot **459,490,744**, same block-time second, opens at **$13,195** (curve before it: 63.28 virtual SOL) | first tick |
| Recorded window | `coverage.fromSlot` = 459,490,744: the market capture starts the slot *after* the first buy | dataset coverage |
| Sub-second placement | first buy `estimatedTime` 04:10:18.284; first tick 04:10:18.500; slot 459,490,745 at .716 (216.2 ms per slot) | chart API with `tickLimit` |

So about 8.8 SOL of other buys landed in slot 459,490,743 after Decu's buy, and the capture never recorded them. From slot 459,490,744 on the recording is complete: every one of the 1,674 trades' reserves chain exactly into the next, with no gap. There is no timezone, unit, precision or aggregation error in the marker: it is plotted at its true execution price and its true block-time second. **Do not move it.** Explain it (section 2.1).

### 0.2 Rendering faults this brief fixes

1. **Each 1 s candle's close is drawn at the start of its second** (`charts/priceChart.ts:165` `chartMarketPoints` + `step: 'end'` at line 289): every value appears up to a second before it happened, and everything inside a second collapses to one point. This is why the line already reads $13.3k at `0s`, exactly where the first buy (at $9.4k) sits.
2. **Markers and fills are placed at whole-second block times** (`components/MarketMarkerOverlay.tsx:30`), so 2–5 slots of one second pile onto one x.
3. **Zoom snaps and reloads.** A wheel or drag zoom calls `requestRange` → after 300 ms `setZoomRange` → a new option whose `xAxis.min/max` *is* the new range, rendered with `notMerge` (`components/Chart.tsx:117`). ECharts' own `dataZoom` resets to 0–100 % of the new axis and the request refetches, so every gesture jumps and flickers. The y-axis is fitted to all data in the response, not to what is visible.
4. **No drawing tools, and full screen loses state.** `RunChartPage` has a browser-fullscreen toggle, but the run page's "Open chart" link drops the range, metric, style and layers, and there is nothing to draw with.

(Backend, already fixed: candles used to take open/close in transaction-id order inside a slot, which is arbitrary. They now follow the proven execution order.)

## 1. HTTP contract additions (already served by the backend)

`GET /api/runs/{id}/chart` and `GET /api/datasets/{id}/chart`. Add to `api/types.ts`:

```ts
export interface ChartRequest {
  // …existing
  /** 0–5000; 0 (default) returns no ticks. */
  tickLimit?: number;
}

export interface ChartTick {
  observationId: string;
  slot: number;
  /** Place among its slot's trades, 0-based. */
  indexInSlot: number;
  /** True when the curve reserves prove that order; false means id order (unknown real order). */
  slotOrderProven: boolean;
  /** Block time, whole seconds. */
  time: IsoInstant;
  /** Block time plus the slot's estimated position inside that second (ms precision). Never outside [time, time + 999 ms]. */
  estimatedTime: IsoInstant;
  /** The trade's average execution price, SOL per token. */
  price: DecimalString;
  mcap: DecimalString | null;
  /** The bonding curve's spot price right after the trade; null when not a valued Pump.fun trade. */
  curvePrice: DecimalString | null;
  curveMcap: DecimalString | null;
  side: 'BUY' | 'SELL' | null;
  trader: string | null;
  isTrackedWallet: boolean;
  solLamports: string | null;
  tokenRaw: string | null;
}

export interface ChartResponse {
  // …existing
  /** Pace used for every estimatedTime; null when under 500 slots were recorded (estimates then equal the block time). */
  millisPerSlot: number | null;
  /** Slots each market window of this pair was recorded through. */
  coverage: { fromSlot: number; throughSlot: number }[];
  ticks: { included: boolean; total: number; items: ChartTick[] };
}
// ObservedChartMarker and PaperChartFill each gain:
estimatedTime: IsoInstant;
```

Ticks come in execution order and only on the first page (no cursor); `completeChart` in `api/queries.ts` already keeps the first page's fields. Update the MSW mocks and fixtures under `src/mocks` so every chart response carries these fields.

## 2. Work, in this order

### 2.1 Marker accuracy and the explanation (`components/MarketMarkerOverlay.tsx`, `MarketActionCard.tsx`, `charts/priceChart.ts`)

1. **One time coordinate.** Everything plotted on the market chart uses `estimatedTime` for x: ticks, markers, paper fills, the hover dot, the cycle origin (`charts/marketSummary.ts` `cycleStart` → use the first buy's `estimatedTime`). Add `plotTime(item)` in `charts/priceChart.ts` and use it everywhere `Date.parse(x.time)` is used for x today. Block `time` stays what tooltips print as "Block time".
2. **Marker y stays the execution value** (`price` or `mcap`). Unchanged.
3. **Unrecorded stretch.** For a tracked marker whose `slot` is below the `fromSlot` of the coverage that starts right after it (`coverage.find(c => c.fromSlot > marker.slot && c.fromSlot - marker.slot <= 1)`), draw a dashed segment (1 px, `terminal.dimmed`, dash 3/3) from the marker's point to the first tick (or first candle point) of that window, with a small label "unrecorded" centred on it, and add to the marker's card (`MarketActionCard`) a section titled **"Why the line starts higher"**:
   > Decu's buy filled at an average of {execution mcap} ({SOL} SOL for {tokens} tokens). The market line starts at slot {fromSlot}, the slot after this buy: trades later in slot {marker.slot} were not recorded, so its first value ({first tick mcap}) already includes them.

   Values come from the marker and the first tick of that window; format with `marketValue`.
4. **Execution vs curve price in the card.** For a tracked marker that has a tick with the same `observationId`, show two lines: "Execution price (average over this trade): {mcap / price}" and "Curve price right after it: {curveMcap / curvePrice}". For every marker also show "Block time 04:10:18 UTC · slot 459,490,743" and, when `millisPerSlot` is set, "Position inside the second estimated from the slot (≈ 216.2 ms per slot)".

Tests: the reference first buy (fixture copied from the values in 0.1) renders at its execution mcap, gets the dashed connector and the "Why the line starts higher" copy with $9.4k and $13.2k; a marker inside coverage gets neither.

### 2.2 Second-level detail (`charts/priceChart.ts`, `components/TradeChart.tsx`, `features/runs/timelineChart.ts`)

1. **Request ticks.** Every chart data request sends `tickLimit: 5000` alongside `maxBuckets: 2000`.
2. **Line style from ticks.** When `ticks.included`, the "Market" line is the ticks: one point per tick at `[plotTime(tick), tick[view]]`, `step: 'end'`, in the order received (never re-sort: the order inside a slot is the execution order). Several ticks of one slot share an x, which draws the slot's moves as a vertical run — intended. Show symbols (size 4) only when fewer than 120 ticks are visible.
3. **Line style without ticks** (window over 5,000 trades): build the line from candles but place each close at the **end** of its bucket (`bucketStart + bucketSeconds * 1000`), so no value is ever drawn before it happened. Keep `step: 'end'`. Candle style keeps the candles as they are.
4. **Hover.** The hover value is the last tick at or before the cursor (`marketPointAt` on tick points). The hover pill shows `+12.4s · 04:10:30.4 UTC · slot 459,490,790 · #2 in slot`, with the decimal only when the visible span is under 2 minutes.
5. **Axis labels.** `secondTicks` gains sub-second steps `[0.1, 0.2, 0.5]` s before 1 s; `secondsFrom` prints one decimal (`+0.4s`) when the step is under 1 s. Allow up to 8 labels on the full-page chart.
6. **Resolution note.** Under the chart, when the visible span is under 60 s, one dimmed line:
   - with `millisPerSlot`: "Block times are whole seconds. Inside a second, trades are placed by slot at the recorded {millisPerSlot} ms per slot; trades of one slot are in their on-chain order but share one instant."
   - without it: "Block times are whole seconds; positions inside a second are not known."
   - if any visible tick has `slotOrderProven: false`, add: "{n} slots have no proven trade order."

Tests: tick line order and coordinates for a slot with three ticks; candle-line closes at bucket end; hover pill text; resolution note in all three cases.

### 2.3 Zoom and navigation (`components/TradeChart.tsx`, `components/Chart.tsx`, `features/results/chartRange.ts`, `features/results/RunChartPage.tsx`)

1. **One source of truth.** The visible window `{from, to}` in ms lives in one hook, `useChartView` (new, `charts/chartViewState.ts`), backed by URL search params `from`, `to` (ISO) so it survives navigation (2.5). `xAxis.min/max` are the **pair's extent** (`extent.firstTime`–`extent.lastTime`, padded 2 %), never the window. The window is applied with `dataZoom` `startValue`/`endValue`.
2. **No reset on update.** `Chart` gets a `notMerge` prop (default true for the other charts). The market chart passes `notMerge={false}` with `replaceMerge: ['series']`, and its `dataZoom` component carries the current `startValue`/`endValue`, so a data refresh never moves the view. When ECharts emits `datazoom`, write the new window to the hook immediately (no 300 ms timer for the view itself).
3. **Fetch once when possible.** Fetch the full extent once (`from`/`to` omitted, `tickLimit: 5000`). If `ticks.included` (the reference run: 1,674 trades), all zooming and panning is client-side with no request. Only when it is not included, fetch the visible window debounced by 250 ms with `keepPreviousData`, and keep drawing the previous data until it arrives.
4. **Gestures.** Embedded card: Ctrl/⌘ + wheel zooms around the cursor, drag pans, plain wheel scrolls the page. Full page (2.5): plain wheel zooms around the cursor. Double-click on the plot = **Fit to data**. Minimum span 2 s; maximum the extent; always clamped inside it.
5. **Controls.** Next to the range pills: **Fit** (whole extent), **Reset** (back to the cycle preset), zoom in, zoom out. Keyboard when the chart has focus: `+`/`-` zoom, `←`/`→` pan 25 %, `0` reset, `F` fit. Remove the old "Reset zoom" pill. `RunChartPage` reuses the same hook instead of its own `writeRange`.
6. **Y fits what is visible.** The y-axis domain is computed from the ticks/candles, markers and fills inside the window (plus levels when shown), recomputed on every view change. Keep the current padding rule (`marketAxisDomain`).
7. **Stable during updates.** A refetch or a new response never changes the window unless the window was "following" the right edge (its `to` equals the old extent end); then it extends to the new end keeping its span.

Tests: wheel and drag do not trigger a request when ticks are included; a refetch keeps `startValue`/`endValue`; Fit and Reset set the right windows; URL params round-trip; y-domain changes with the window.

### 2.4 Drawing tools (new `charts/chartDrawings.ts` + `components/ChartDrawingLayer.tsx`)

1. **Toolbar** in the chart header: Pointer (default), Trend line, Measure, and Clear all (with confirm). Icons with `aria-label`s and tooltips; the active tool is `aria-pressed`. `Esc` returns to Pointer and cancels a drawing in progress.
2. **Model.**
   ```ts
   interface ChartDrawing {
     id: string;
     kind: 'trend' | 'measure';
     metric: 'mcap' | 'price';
     a: { time: number; value: number };
     b: { time: number; value: number };
   }
   ```
   Coordinates are data coordinates (`plotTime` ms and the plotted metric value), so drawings follow zoom and pan. A drawing shows only on the metric it was drawn on.
3. **Drawing.** Click (or press) to place `a`, move, click again (or release after a drag) to place `b`. While moving, a label next to the pointer shows the change from `a` to the current point: `+12.34% · +$1.2k · +4.2s` (price metric: `+12.34% · +0.0₇123 SOL · +4.2s`). Percent is `(b − a) / a × 100`, two decimals, sign always shown, green when up and red when down. Points snap to the nearest tick or candle value within 8 px; hold Alt for a free point.
4. **Measure** draws a translucent box from `a` to `b` with the same label inside; **Trend line** draws a line with the label at its end. Both keep their label after they are placed.
5. **Editing.** With Pointer, clicking a drawing selects it: endpoint handles (8 px) appear; dragging a handle moves that end, dragging the body moves both; the label updates live. `Delete`/`Backspace` removes the selected drawing; a small "Delete" button appears next to it too.
6. **Rendering.** An absolutely positioned SVG over the plot, like `MarketMarkerOverlay`: convert with `chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, …)` on `finished`, `datazoom` and resize; clip to the grid rect. While a drawing tool is active, the overlay takes pointer events and the chart's drag-pan is suspended; with Pointer, the overlay only catches events on drawings.
7. **Persistence.** `localStorage` key `trenching.chart.drawings.v1:{kind}:{id}:{pair}`, every read and write in try/catch (reuse the watchlist's "Browser storage unavailable" status line on failure). Drawings live outside component state that the full-page toggle could reset.

Tests: percent label for up and down moves and a zero start (`—`); snap and Alt; select, drag a handle, delete with the key; persistence and the storage-failure path; drawings re-project after zoom.

### 2.5 Full-page chart and preserved state (`components/TradeChart.tsx`, `features/runs/RunDetailPage.tsx`, `features/results/RunChartPage.tsx`)

1. **Expand in place.** Add an **Expand** button (icon + `aria-label="Open full-page chart"`) to the chart's control row on every `TradeChart`. It renders the *same* chart instance's state into a fixed full-viewport layer (`position: fixed; inset: 0; z-index` above the app shell; body scroll locked) with the chart filling the height minus its header. Exit with a visible **"Exit full page"** button (top right, with `Esc` shown) or `Esc`; focus returns to the Expand button.
2. **Keep everything.** View window (2.3), metric, style, layers (Decu actions, strategy levels, paper fills, all traders, watchlist), the selected action and drawings are the same before, during and after. Lift what is component state today (`metric`, `style`, `paperLayer`, `decuLayer`, `levelsLayer`, `allTraders`) into `useChartView`, backed by URL params `view`, `style`, `layers` (comma list), plus the existing `action`/`fill`, so a reload keeps them too.
3. **Open chart link.** `RunDetailPage`'s "Open chart" link carries the current params (`pair`, `from`, `to`, `view`, `style`, `layers`, `action`) to `paths.runChart(id)`, and RunChartPage's "← Back to run" carries them back. The browser-fullscreen toggle on RunChartPage becomes the same Expand behaviour.
4. **Responsive.** At 375 px the full-page layer stacks the toolbar above the chart, keeps the Exit button reachable, and gives the plot at least 60 vh.

Tests: expand → change zoom and draw → exit keeps both; Esc exits; params survive "Open chart" and "Back to run"; layers survive a reload.

## 3. Out of scope (backend, open)

- The replay engine also orders a slot's trades by id (`marketdata/domain/OrderedMarketData.kt`), which decides which trade triggers a dip or fills an entry inside one slot. Using the proven curve order there changes simulation results and checksums; it needs its own decision.
- Recording the rest of the first buy's own slot needs a capture change (the window would start at the buy's slot, keeping only trades after it in that block's order) and a re-capture.

## 4. Verify

```bash
cd frontend
npm run lint
npm test
npm run build
```

Then with the backend running, open `/runs/fbd3425249f1c23bc212ecc28df230e8012eb5c090964140b273179128742b0b/chart?pair=6Ez7jwuUoTr81akzQsBPjfGA67nRWoePEQUsKCUfpump%2FSo11111111111111111111111111111111111111112` at 1440 px and 375 px and check:

1. The first B sits at $9.4k at `0s`; a dashed "unrecorded" segment joins it to the line's first point at $13.2k; its card explains why.
2. Zoomed to the first 5 seconds, the line steps through each trade, axis labels read `+0.5s`, `+1.0s`…, the resolution note is shown, and no request is sent while zooming (Network panel).
3. Wheel, drag, Fit, Reset and double-click move smoothly with no jump back.
4. A trend line and a measure show a live percent while drawing, keep it afterwards, can be dragged and deleted, and are still there after a reload.
5. Expand → zoom and draw → Exit: view, metric, style, layers and drawings are unchanged.
