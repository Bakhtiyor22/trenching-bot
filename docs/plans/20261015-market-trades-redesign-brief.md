# Codex brief: Market & trades section redesign

Status: Design ready 2026-10-15 — not implemented.

Redesign of the **Market & trades** section on the run detail page (`Timeline` in `frontend/src/features/runs/RunDetailPage.tsx`, rendering `components/TradeChart.tsx`). Scope is this section only: no backend change, no change to other sections, the chart HTTP contract stays as in the [chart frontend brief](20261015-chart-frontend-codex-brief.md). The dataset page uses the same `TradeChart`; it gets the same treatment with no paper fills.

Design: the "Market & trades — redesign" row of the Trenching Console Redesign canvas (https://claude.ai/artifact/TfSxtnti4wFjZKZojgSNaR): an interactive default view, an edge-states board and a handoff-spec board. Where this brief and the canvas differ, this brief wins.

**Goal.** One large, quiet chart that answers "what did the market do, and where did Decu act?". Decu is the run's tracked wallet (`trackedWallet`; markers with `isTrackedWallet: true`). Everything else is one click away, not on screen.

## Layout, top to bottom

1. **Title row.** `Market & trades · 5ATM…pump / SOL` (the current hint text becomes a tooltip on the title). Right side: the Decu stepper `Decu actions ‹ n / N ›`, then the `Mcap | Price (SOL)` segmented control.
2. **Value block.** Big value (JetBrains Mono 38 px): latest Mcap in compact USDT (`$15.5k`) or price in SOL with the subscript notation (`0.0₇775 SOL`). Next to it a round ⓘ button. Below: a change chip `▲ 4.12%` / `▼ 4.12%` (green or red tint), the absolute change, and a muted caption: `over this cycle`, `over 15m`, or `at 15:31:15 UTC` while hovering.
3. **Chart.** 420 px tall (was 400 including controls; the plot itself is now much larger), no data-zoom slider. Line by default: 2 px, green if the range ends at or above where it started, red otherwise; no area fill. Four dashed grid lines, y labels in a 68 px right-hand column, x labels only at the start and end of the range. Gaps stay gaps (no line across absent buckets or null mcap).
4. **Control row.** Range pills in one rounded track: `Cycle · 5m · 15m · 1H · All`, then a round line/candles toggle and a round `⋯` Layers button. While zoomed, a `Reset zoom` pill appears at the end of the track.
5. **Legend line** (12 px, muted): `B Decu buy`, `S Decu sell`, `◇ Bot paper fill` (only while that layer is on), and on the right `4 Decu actions in view · 1 outside this range`.

## Markers

| Marker | Look | Placement |
|---|---|---|
| Decu buy | 26 px solid amber `#F2B43C` circle, dark `B` | pin above its point: circle centre 26 px above, 12 px amber stem, 7 px ringed dot on the exact point |
| Decu sell | 26 px dark circle with 2 px amber ring, amber `S` | pin below its point, same stem and dot |
| Selected | 3 px gap + 2 px `#E7ECEF` halo | — |
| Stacked | buy-style circle with the count and an offset second ring | when Decu circle centres would be under 28 px apart at the current zoom |
| Bot paper fill | 11 px blue `#5AA9FF` outlined diamond | on the exact point |
| Other traders | 6 px grey `#5C6B78` dots, buy filled, sell hollow | on the point; not interactive |

Rules: every Decu or paper marker is a real `<button>` with a 44 × 44 px hit area and an `aria-label` like `Decu buy, 2.4 SOL at 15:23:24 UTC`. Buy and sell differ by fill and letter, never colour alone. Markers never use green or red (those belong to the line and candles). Marker y uses the item's own `mcap` (or `price` in Price view), x its `time`. In CLUSTERED mode Decu trades are still individual `items`, so Decu markers are never replaced by server clusters; only the other-traders layer uses `clusters`.

Implementation hint: render Decu and paper markers as an HTML overlay positioned with `convertToPixel` from the ECharts instance (re-run on `finished`/`datazoom`/resize), not as scatter symbols. That gives real buttons, focus and the anchored card for free. Keep the ECharts series for the line, candles, grid and the other-traders dots.

## Action card (what a click opens)

Anchored to the clicked marker, 300 px wide, above it (or below when the marker is within 230 px of the top), horizontally clamped inside the plot. Background `#151B21`, border `#2A343D`, radius 10, shadow.

- Header: B/S badge, `Decu bought` / `Decu sold`, muted `Decu · 6AhH…99sm · 2 of 4`, close button.
- Big number: SOL amount (`exactUnits(solLamports, 9)`, trailing zeros trimmed; sells prefixed `+`).
- Rows: Tokens (`exactUnits(tokenRaw, 6)`), Mcap, Price (SOL), Time `HH:MM:SS UTC · slot N`.
- Optional amber note: `First buy · started this cycle` for the trade whose slot equals the cycle's `sourceSlot`; `Position closed` when known.
- Footer: `Transaction ↗` (`transactionLinks(signature)`, hidden when `signature` is null) and `Open action →`.

Paper fills open the same card with a blue diamond badge, `Bot paper entry` / `Bot paper exit`, `Simulated fill · not a real trade`, the quantity as the big number, Mcap, Price, Time, the `reason` as the note and `Open fill →`.

## Behaviour

- **Click a marker**: select it and open the card. Write `?action=<observationId>` (paper: `?fill=<intentId>:<phase>`) with `replace: true`, the same way `useQuoteCurrency` writes `?quote=`. Loading a URL with the param selects that action, widening the range to `All` if it is outside the default range. Clicking the selected marker again, the close button, `Esc` or a click outside closes the card and removes the param.
- **Open action →** goes to the action's own details: for the cycle's first buy, scroll to that cycle's card in Cycle details and outline it; otherwise switch Stored records to the Observations tab, scroll to it and highlight the row with that observation (add a stable row id). No new page.
- **Stepper**: visits Decu actions in view in time order and opens each card; wraps; `– / N` with nothing selected; `— / —` and disabled when `markers.available` is false.
- **Hover**: dashed vertical crosshair, a dot on the line, a time pill under the x axis; the big value, chip and caption follow the hovered point and return to the latest point on leave. Replaces the ECharts tooltip.
- **Ranges** (requested as `from`/`to`, debounced as today): `Cycle` (default) is 3 min before the cycle's first Decu buy to 3 min after the last Decu action in the data; `5m`, `15m`, `1H` start 1 min before the first buy; `All` clears `from`/`to`. Ctrl + scroll and drag still zoom and pan; a zoom shows `Reset zoom` and deselects the range pill. A selected action that leaves the range closes.
- **Mcap / Price**: switches the axis, the big value and the card's emphasis. Strategy level lines (P0, dip, TP, SL from `timelineChart`) draw only in Price view, as today.
- **Layers `⋯`** (popover): `Decu actions` (checked, disabled, "always on"), `Bot paper fills` (default on for runs), `All captured traders` (default off), divider, `Watchlist wallets · n / 20` with the base58 input and Add, then the watched wallets with Remove. Same validation, limits, local storage and request params as today. Watched wallets' trades render as grey dots, not as Decu markers.
- **ⓘ** (popover): `valuationBasis.label`, supply and source, rate source and granularity, `n points without a rate · n unsupported`, tracked wallet `6AhH…99sm · pinned` with copy.
- **Keyboard**: markers are tabbable in time order; Enter opens; Esc closes and returns focus to the marker; ← / → step Decu actions while a card is open. J / K keep stepping cycles at page level.

## States (edge-states board)

- **A — stacked Decu actions**: click lists them (badge, amount, time), each row opens that action's card; `Zoom to these →` sets the range to the stack's span.
- **B — `markers.available` false**: chart still renders; one dashed status line under it: "Decu's actions can't be placed: this dataset was recorded before trader data (v3)." Replaces the bold text above the chart.
- **C — `valuationBasis.available` false**: `Mcap` disabled, one amber line with the reason, Price view shown. Partial gaps are not a state; their counts live in ⓘ.
- **D — fewer than 3 points in range** (today's single-bar view): no candle; the point on a dashed level, labelled once, plus the status line "Not enough market data to draw a line…". The y axis always keeps at least ±10% padding.
- Loading and error keep `LoadingState` / `ErrorState`, sized to the section so the page does not jump.

## Removed from the default view

The valuation label and supply/rate line, tracked-wallet line and badge, watchlist input and list, `All captured traders` checkbox, the row of trade buttons under the chart, the details box under the chart, and the data-zoom slider. Each moved to the ⓘ popover, the Layers menu, the action card or Stored records; nothing is lost.

## Tokens

Ground `#0A0D10`, panel `#10151A`, raised `#151B21`, border `#1F2830` / `#2A343D`, text `#E7ECEF`, secondary `#C3CDD5`, muted `#93A1AD`, faint `#5C6B78`, Decu amber `#F2B43C`, paper blue `#5AA9FF`, up `#3DDC97`, down `#FF6B6B`. IBM Plex Sans for UI, JetBrains Mono for numbers. Map these to the existing Mantine theme values rather than hard-coding them where the theme already has them.

## Files

`components/TradeChart.tsx` (layout, layers menu, ⓘ, stepper, card, URL param), a new `components/MarketActionCard.tsx` and marker overlay component, `charts/priceChart.ts` and `features/runs/timelineChart.ts` (line default, grid, no slider, no tooltip, other-trader dots only), `features/runs/RunDetailPage.tsx` (title tooltip, `Open action` scroll targets, Observations row highlight), `features/datasets/DatasetDetailPage.tsx` (same section without paper fills).

## Tests

Marker placement and buy/sell/selected/stacked rendering; stacking threshold; card contents for buy, sell, first buy and paper fill; `?action=` round trip including an action outside the default range; stepper order, wrap and disabled state; Esc and focus return; the four edge states; the Layers menu still sends the same `wallet` params. Update `TradeChart.test.tsx`, `RunDetailPage.test.tsx`, `DatasetDetailPage.test.tsx`, `priceChart.test.ts` and `timelineChart.test.ts`.

**Verify**: from `frontend/`, `npm test`, `npm run lint`, `npm run build`, all clean (`--max-warnings 0`).
