# Charts

Read this when adding or changing a chart. For general colour, mark and legend choices and for
validating a palette, also load the `dataviz` skill; this file covers what is specific to the console.

## The wrapper

- Render every chart through `<Chart option ariaLabel height>` from `components/Chart.tsx`. It
  registers only the ECharts parts the console uses (bar, candlestick, custom, line, scatter,
  dataZoom, grid, legend, markLine, markPoint, tooltip, canvas) to keep the bundle small. If you
  need another part, register it there, not in a feature.
- The dark `terminal` theme gives hairline axes, mono axis labels, amber axis pointer and a
  transparent background. Don't restyle axes per chart; change the theme if the change is general.
- Build the `option` in a plain `.ts` module (`charts/priceChart.ts`, `features/*/…Chart.ts`) and
  unit-test it. Components only pass data and events in.
- `ariaLabel` states what the chart shows ("Price in SOL for run X, cycle window"). The chart is
  `role="img"`, so the important figures must also exist as text nearby (headline, KPI, table).

## Data honesty

- Convert with `toChartNumber` only for coordinates. Tooltip and label text uses the original
  strings (`formatDecimal`, `compactDecimal`).
- `connectNulls: false`. Never draw a line or candle across missing data; a gap is information.
  When a gap needs explaining (unrecorded capture stretch, missing rate), draw it distinctly
  (dashed "unrecorded" connector) and explain it in the card or note.
- Never substitute a nearby value for a missing one (e.g. a candle open as a trade price). If the
  contract lacks a coordinate, place the mark on a separate rail and say why.
- The y-axis fits the visible data; price axes use `priceAxisLabel`, slot axes `formatSlot`.

## Marks and layers

- Strategy levels (P0, dip, take-profit, stop-loss) are `markLine`s with their name and value.
- Decu (tracked wallet) actions: amber B/S pins. Paper fills: blue diamonds (`terminal.info`).
  Keep these meanings stable across pages; a reader learns them once.
- Markers that open details are real buttons in an overlay (`MarketMarkerOverlay`), not canvas-only
  clicks, so they are reachable by keyboard and screen reader.
- Nearby markers cluster and offer a list; don't let pins overlap into an unreadable blob.

## Interaction

- Window presets (Cycle, 5m, 15m, 1H, All), drag to pan, double-click/Fit, Reset to cycle.
  Embedded charts zoom with Ctrl/⌘ + wheel so page scrolling still works; full-page charts use plain
  wheel. Focused plot: `+/−` zoom, `←/→` pan, `0` reset, `F` fit. Show these in the UI.
- Persist the view (window, metric, style, layers, selection) in URL parameters so a link reproduces
  what the sender saw; per-chart drawings go in `localStorage` under a versioned key, and a storage
  failure must not break the chart.
- Large windows debounce data requests (250 ms) and keep the previous data visible while loading.
- `animation: false` on live or large charts; motion there only delays reading.

## Small charts

- Sparklines and trend charts (`VariationTrend`, dashboard charts) skip axes chrome, but still show
  the latest value as text and keep a tooltip with the exact string.
- Status grids use the CSS variables in `styles.css` (`--automation-*`), defined for both colour
  schemes and validated with the dataviz palette script. New status colours follow the same process.
