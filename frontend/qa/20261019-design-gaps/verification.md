# Console design-gap delivery · 19 October 2026

Implemented the approved frontend brief across captures, setup, results and usage. Mantine, ECharts, React Query and the dark theme are retained. No new dependency, backend change or schema change.

## Verification

- `npm test`: **331 tests passed, 51 files passed**.
- `npm run lint`: passed, zero warnings.
- `npm run build`: passed.
- Browser: all six pages rendered at 1440 px and 375 px. No horizontal page overflow; wide tables scroll inside their containers. See [recorded dimensions](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/viewport-checks.json).
- Mock provider states checked: RPC not configured, rate limited, unavailable, not mainnet, local access required; plus empty activity, empty captures, unavailable preset, empty usage and empty chart range.
- Review fixes verified: rate-limit controls share one deadline; Markets never borrows Wallet progress; isolated trades remain visible; expanded charts stay inside their card.

Real-backend verification could not run: the frontend on port 5173 and backend on port 8081 were unreachable. No live provider calls were initiated.

The existing API does not include a token-case buy timestamp or a capture chart endpoint. Token facts therefore show the buy slot; Chart preview explains that a prepared dataset is required.

## Screenshots

| Page | 1440 px | 375 px |
|---|---|---|
| Captures | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/captures-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/captures-375.jpg) |
| New capture | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-capture-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-capture-375.jpg) |
| Capture detail | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-375.jpg) |
| New run | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-run-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-run-375.jpg) |
| Results chart | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/run-chart-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/run-chart-375.jpg) |
| API usage | [Desktop](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/usage-1440.jpg) | [Phone](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/usage-375.jpg) |

Desktop capture detail:

![Capture detail at 1440 px](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-1440.jpg)

Phone capture detail:

![Capture detail at 375 px](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-375.jpg)

## Reproduce mock states

Start the frontend with `VITE_USE_MOCKS=true` supplied in the process environment, on port 5174. Use `/console/` routes. Add `?mockState=` with `captures-empty`, `activity-empty`, `preset-unavailable`, `usage-empty`, `chart-empty`, or a provider error code above to its relevant page. Capture detail also accepts every capture summary status, such as `STOPPED_LIMIT`, `RUNNING` and `WAITING_RETRY`. Reload after changing the query. The representative stopped capture is `/console/captures/capture-limited`.

## Files changed

- [frontend/src/charts/priceChart.test.ts](D:/Projects/trenching_bot/frontend/src/charts/priceChart.test.ts) — Regression coverage: keeps isolated market observations visible across empty buckets.
- [frontend/src/charts/priceChart.ts](D:/Projects/trenching_bot/frontend/src/charts/priceChart.ts) — Keeps isolated market observations visible across empty buckets.
- [frontend/src/components/DisplayValues.tsx](D:/Projects/trenching_bot/frontend/src/components/DisplayValues.tsx) — Adds relative time with exact local timestamps.
- [frontend/src/components/MarketMarkerOverlay.tsx](D:/Projects/trenching_bot/frontend/src/components/MarketMarkerOverlay.tsx) — Uses gold for simulated paper trade diamonds.
- [frontend/src/components/Recommendations.test.tsx](D:/Projects/trenching_bot/frontend/src/components/Recommendations.test.tsx) — Regression coverage: separates settings from one-shot actions and shows applied state.
- [frontend/src/components/Recommendations.tsx](D:/Projects/trenching_bot/frontend/src/components/Recommendations.tsx) — Separates settings from one-shot actions and shows applied state.
- [frontend/src/components/SourceLinks.test.tsx](D:/Projects/trenching_bot/frontend/src/components/SourceLinks.test.tsx) — Regression coverage: omits missing capture crumbs and labels dataset/run links.
- [frontend/src/components/SourceLinks.tsx](D:/Projects/trenching_bot/frontend/src/components/SourceLinks.tsx) — Omits missing capture crumbs and labels dataset/run links.
- [frontend/src/components/StateViews.test.tsx](D:/Projects/trenching_bot/frontend/src/components/StateViews.test.tsx) — Regression coverage: explains provider errors and shares the original retry deadline.
- [frontend/src/components/StateViews.tsx](D:/Projects/trenching_bot/frontend/src/components/StateViews.tsx) — Explains provider errors and shares the original retry deadline.
- [frontend/src/components/TradeChart.test.tsx](D:/Projects/trenching_bot/frontend/src/components/TradeChart.test.tsx) — Regression coverage: adds empty-range recovery, data callbacks and correctly sized expanded plots.
- [frontend/src/components/TradeChart.tsx](D:/Projects/trenching_bot/frontend/src/components/TradeChart.tsx) — Adds empty-range recovery, data callbacks and correctly sized expanded plots.
- [frontend/src/features/captures/CaptureCases.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureCases.tsx) — Presents token facts and actions while preserving technical controls.
- [frontend/src/features/captures/CaptureDetailPage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureDetailPage.test.tsx) — Regression coverage: replaces duplicate layouts with Now, progress, terms and Advanced.
- [frontend/src/features/captures/CaptureDetailPage.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureDetailPage.tsx) — Replaces duplicate layouts with Now, progress, terms and Advanced.
- [frontend/src/features/captures/CaptureOverview.test.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureOverview.test.tsx) — Regression coverage: shows one stepper and stage-specific market progress.
- [frontend/src/features/captures/CaptureOverview.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureOverview.tsx) — Shows one stepper and stage-specific market progress.
- [frontend/src/features/captures/CaptureShared.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CaptureShared.tsx) — Adds shared capture status chips.
- [frontend/src/features/captures/CapturesPage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CapturesPage.test.tsx) — Regression coverage: rebuilds list columns, filters, progress, paging and navigation.
- [frontend/src/features/captures/CapturesPage.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/CapturesPage.tsx) — Rebuilds list columns, filters, progress, paging and navigation.
- [frontend/src/features/captures/NewCapturePage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/NewCapturePage.test.tsx) — Regression coverage: reworks wallet suggestions, exact activity values, naming and creation.
- [frontend/src/features/captures/NewCapturePage.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/NewCapturePage.tsx) — Reworks wallet suggestions, exact activity values, naming and creation.
- [frontend/src/features/captures/TokenVerdict.test.tsx](D:/Projects/trenching_bot/frontend/src/features/captures/TokenVerdict.test.tsx) — Regression coverage: updates the existing test for the renamed saved-file action.
- [frontend/src/features/captures/activityDisplay.test.ts](D:/Projects/trenching_bot/frontend/src/features/captures/activityDisplay.test.ts) — Regression coverage: formats lamports exactly and chooses ordered activity notes.
- [frontend/src/features/captures/activityDisplay.ts](D:/Projects/trenching_bot/frontend/src/features/captures/activityDisplay.ts) — Formats lamports exactly and chooses ordered activity notes.
- [frontend/src/features/captures/captureNow.test.ts](D:/Projects/trenching_bot/frontend/src/features/captures/captureNow.test.ts) — Regression coverage: defines clear copy for every capture status and stage.
- [frontend/src/features/captures/captureNow.ts](D:/Projects/trenching_bot/frontend/src/features/captures/captureNow.ts) — Defines clear copy for every capture status and stage.
- [frontend/src/features/captures/useNextAction.test.ts](D:/Projects/trenching_bot/frontend/src/features/captures/useNextAction.test.ts) — Regression coverage: adds next-step request and navigation hints.
- [frontend/src/features/captures/useNextAction.ts](D:/Projects/trenching_bot/frontend/src/features/captures/useNextAction.ts) — Adds next-step request and navigation hints.
- [frontend/src/features/results/RunChartPage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/results/RunChartPage.test.tsx) — Regression coverage: adds context, token navigation, precise ranges and an event timeline.
- [frontend/src/features/results/RunChartPage.tsx](D:/Projects/trenching_bot/frontend/src/features/results/RunChartPage.tsx) — Adds context, token navigation, precise ranges and an event timeline.
- [frontend/src/features/runs/NewRunPage.css](D:/Projects/trenching_bot/frontend/src/features/runs/NewRunPage.css) — Shows agreed settings, limitations, field resets and explicit defaults fallback.
- [frontend/src/features/runs/NewRunPage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/runs/NewRunPage.test.tsx) — Regression coverage: shows agreed settings, limitations, field resets and explicit defaults fallback.
- [frontend/src/features/runs/NewRunPage.tsx](D:/Projects/trenching_bot/frontend/src/features/runs/NewRunPage.tsx) — Shows agreed settings, limitations, field resets and explicit defaults fallback.
- [frontend/src/features/runs/configLabels.test.ts](D:/Projects/trenching_bot/frontend/src/features/runs/configLabels.test.ts) — Regression coverage: formats agreed values and counts exact setting differences.
- [frontend/src/features/runs/configLabels.ts](D:/Projects/trenching_bot/frontend/src/features/runs/configLabels.ts) — Formats agreed values and counts exact setting differences.
- [frontend/src/features/runs/runForm.test.ts](D:/Projects/trenching_bot/frontend/src/features/runs/runForm.test.ts) — Regression coverage: trims decimal input scale without changing submitted values.
- [frontend/src/features/runs/runForm.ts](D:/Projects/trenching_bot/frontend/src/features/runs/runForm.ts) — Trims decimal input scale without changing submitted values.
- [frontend/src/features/usage/ApiUsagePage.test.tsx](D:/Projects/trenching_bot/frontend/src/features/usage/ApiUsagePage.test.tsx) — Regression coverage: groups providers, explains units, shows task shares and empty states.
- [frontend/src/features/usage/ApiUsagePage.tsx](D:/Projects/trenching_bot/frontend/src/features/usage/ApiUsagePage.tsx) — Groups providers, explains units, shows task shares and empty states.
- [frontend/src/features/usage/usageData.test.ts](D:/Projects/trenching_bot/frontend/src/features/usage/usageData.test.ts) — Regression coverage: groups usage, sorts tasks and fetches all capture choices.
- [frontend/src/features/usage/usageData.ts](D:/Projects/trenching_bot/frontend/src/features/usage/usageData.ts) — Groups usage, sorts tasks and fetches all capture choices.
- [frontend/src/lib/codes.ts](D:/Projects/trenching_bot/frontend/src/lib/codes.ts) — Centralizes plain labels, status tones and suggestion copy.
- [frontend/src/lib/format.test.ts](D:/Projects/trenching_bot/frontend/src/lib/format.test.ts) — Regression coverage: adds deterministic relative-time formatting.
- [frontend/src/lib/format.ts](D:/Projects/trenching_bot/frontend/src/lib/format.ts) — Adds deterministic relative-time formatting.
- [frontend/src/lib/glossary.ts](D:/Projects/trenching_bot/frontend/src/lib/glossary.ts) — Uses plain explanations for capture terminology.
- [frontend/src/mocks/captureHandlers.ts](D:/Projects/trenching_bot/frontend/src/mocks/captureHandlers.ts) — Adds stopped/legacy captures and selectable mock statuses.
- [frontend/src/mocks/fixtures.ts](D:/Projects/trenching_bot/frontend/src/mocks/fixtures.ts) — Adds a six-minute chart fixture.
- [frontend/src/mocks/handlers.ts](D:/Projects/trenching_bot/frontend/src/mocks/handlers.ts) — Serves meaningful chart windows and the empty-range mock.
- [frontend/src/mocks/previewState.ts](D:/Projects/trenching_bot/frontend/src/mocks/previewState.ts) — Selects dev-only mock scenarios through mockState query parameters.
- [frontend/src/mocks/setupHandlers.ts](D:/Projects/trenching_bot/frontend/src/mocks/setupHandlers.ts) — Adds varied activity, Binance usage and selectable error/empty states.
- [frontend/qa/20261019-design-gaps/capture-detail-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/capture-detail-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/capture-detail-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/captures-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/captures-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/captures-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/captures-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/captures-empty-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/captures-empty-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/chart-empty-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/chart-empty-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/new-capture-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-capture-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/new-capture-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-capture-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/new-run-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-run-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/new-run-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/new-run-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/preset-unavailable-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/preset-unavailable-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/run-chart-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/run-chart-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/run-chart-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/run-chart-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/usage-1440.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/usage-1440.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/usage-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/usage-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/usage-empty-375.jpg](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/usage-empty-375.jpg) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/viewport-checks.json](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/viewport-checks.json) — Browser verification evidence.
- [frontend/qa/20261019-design-gaps/verification.md](D:/Projects/trenching_bot/frontend/qa/20261019-design-gaps/verification.md) — Delivery report, complete changed-file list and screenshot index.
