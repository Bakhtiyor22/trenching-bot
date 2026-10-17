# Capture, run and results frontend

Implements the approved `20261016-console-capture-run-ux-codex-handoff.md` against the delivered backend contract.

- `/captures`: status counts, filtering and sorting in the URL, readable names, four-step progress, and links to the next step.
- `/captures/new`: suggested and recent wallets, activity loaded when the token step opens, buy selection, activity filters and pagination, generated names and conflict suggestions, setup recommendations, attachment, and explicit create/start controls.
- Capture detail: derived status and next action, rename, usage link, request estimates, plain-language guidance, and the existing independent case, recovery, attempt and download controls. Export precedes dataset registration.
- `/runs/new`: agreed-strategy preset, exact percent inputs, per-field status, unsupported-rule warning, clearly labelled default fallback, and legacy comparison.
- `/runs/:id/chart`: a full-width chart with range presets, local From/To controls, zoom, pan, reset, wheel and keyboard navigation, layers, full screen, event jumps and URL state.
- `/usage`: period and capture filters, provider/task breakdowns, request chart, pacing, explicit units and recording dates; refreshes every 30 seconds only while visible.
- Run/dataset/chart source links, accessible glossary terms, and percent formatting throughout run results.

Money and percentages remain decimal strings. Only plotting converts amounts to floating point. No dependencies were added, no environment files were accessed, and verification uses mocked providers. No schema change.

## Verification

Passed: all 237 tests, TypeScript project checks, ESLint with zero warnings, and the production Vite build. Checks ran directly through the installed Node.js tools; Vitest used mocked providers and a workspace-local temporary directory.

Repeat from `frontend/`: `npm test`, `npm run lint`, and `npm run build`.

The local mocked server uses `VITE_USE_MOCKS=true` on port 5174. The new-capture page was checked in the browser, including at a measured 375 CSS-pixel width. Further visual inspection was interrupted by a browser approval timeout; desktop and mobile verification of the remaining pages is still outstanding.

## Files changed

- [frontend/IMPLEMENTATION_NOTES.md](/D:/Projects/trenching_bot/frontend/IMPLEMENTATION_NOTES.md:1) — implementation summary, verification and file manifest.
- [frontend/src/api/captures.ts](/D:/Projects/trenching_bot/frontend/src/api/captures.ts:1) — additive API contracts and client integration.
- [frontend/src/api/client.ts](/D:/Projects/trenching_bot/frontend/src/api/client.ts:1) — additive API contracts and client integration.
- [frontend/src/api/queries.ts](/D:/Projects/trenching_bot/frontend/src/api/queries.ts:1) — additive API contracts and client integration.
- [frontend/src/api/setup.ts](/D:/Projects/trenching_bot/frontend/src/api/setup.ts:1) — additive API contracts and client integration.
- [frontend/src/api/types.ts](/D:/Projects/trenching_bot/frontend/src/api/types.ts:1) — additive API contracts and client integration.
- [frontend/src/components/AppLayout.tsx](/D:/Projects/trenching_bot/frontend/src/components/AppLayout.tsx:1) — shared UI and chart integration.
- [frontend/src/components/Chart.tsx](/D:/Projects/trenching_bot/frontend/src/components/Chart.tsx:1) — shared UI and chart integration.
- [frontend/src/components/PlainLanguage.tsx](/D:/Projects/trenching_bot/frontend/src/components/PlainLanguage.tsx:1) — shared UI and chart integration.
- [frontend/src/components/Recommendations.tsx](/D:/Projects/trenching_bot/frontend/src/components/Recommendations.tsx:1) — shared UI and chart integration.
- [frontend/src/components/SourceLinks.tsx](/D:/Projects/trenching_bot/frontend/src/components/SourceLinks.tsx:1) — shared UI and chart integration.
- [frontend/src/components/TradeChart.tsx](/D:/Projects/trenching_bot/frontend/src/components/TradeChart.tsx:1) — shared UI and chart integration.
- [frontend/src/features/captures/CaptureCases.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CaptureCases.tsx:1) — capture setup, progress and controls.
- [frontend/src/features/captures/CaptureDetailPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CaptureDetailPage.tsx:1) — capture setup, progress and controls.
- [frontend/src/features/captures/CaptureOverview.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CaptureOverview.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/captures/CaptureOverview.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CaptureOverview.tsx:1) — capture setup, progress and controls.
- [frontend/src/features/captures/CapturesPage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CapturesPage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/captures/CapturesPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/CapturesPage.tsx:1) — capture setup, progress and controls.
- [frontend/src/features/captures/NewCapturePage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/NewCapturePage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/captures/NewCapturePage.tsx](/D:/Projects/trenching_bot/frontend/src/features/captures/NewCapturePage.tsx:1) — capture setup, progress and controls.
- [frontend/src/features/captures/captureName.test.ts](/D:/Projects/trenching_bot/frontend/src/features/captures/captureName.test.ts:1) — behavior and regression coverage.
- [frontend/src/features/captures/captureName.ts](/D:/Projects/trenching_bot/frontend/src/features/captures/captureName.ts:1) — capture setup, progress and controls.
- [frontend/src/features/captures/captureQueries.ts](/D:/Projects/trenching_bot/frontend/src/features/captures/captureQueries.ts:1) — capture setup, progress and controls.
- [frontend/src/features/captures/useNextAction.ts](/D:/Projects/trenching_bot/frontend/src/features/captures/useNextAction.ts:1) — capture setup, progress and controls.
- [frontend/src/features/compare/compareRuns.test.ts](/D:/Projects/trenching_bot/frontend/src/features/compare/compareRuns.test.ts:1) — behavior and regression coverage.
- [frontend/src/features/datasets/DatasetDetailPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/datasets/DatasetDetailPage.tsx:1) — capture source links.
- [frontend/src/features/results/RunChartPage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/results/RunChartPage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/results/RunChartPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/results/RunChartPage.tsx:1) — chart navigation and results view.
- [frontend/src/features/results/chartRange.test.ts](/D:/Projects/trenching_bot/frontend/src/features/results/chartRange.test.ts:1) — behavior and regression coverage.
- [frontend/src/features/results/chartRange.ts](/D:/Projects/trenching_bot/frontend/src/features/results/chartRange.ts:1) — chart navigation and results view.
- [frontend/src/features/runs/NewRunPage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/NewRunPage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/runs/NewRunPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/NewRunPage.tsx:1) — preset run setup and percent display.
- [frontend/src/features/runs/RunDetailPage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/RunDetailPage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/runs/RunDetailPage.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/RunDetailPage.tsx:1) — preset run setup and percent display.
- [frontend/src/features/runs/RunTables.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/RunTables.tsx:1) — preset run setup and percent display.
- [frontend/src/features/runs/ScenarioCard.tsx](/D:/Projects/trenching_bot/frontend/src/features/runs/ScenarioCard.tsx:1) — preset run setup and percent display.
- [frontend/src/features/runs/configLabels.ts](/D:/Projects/trenching_bot/frontend/src/features/runs/configLabels.ts:1) — preset run setup and percent display.
- [frontend/src/features/runs/runForm.ts](/D:/Projects/trenching_bot/frontend/src/features/runs/runForm.ts:1) — preset run setup and percent display.
- [frontend/src/features/runs/scenarioStory.test.ts](/D:/Projects/trenching_bot/frontend/src/features/runs/scenarioStory.test.ts:1) — behavior and regression coverage.
- [frontend/src/features/runs/scenarioStory.ts](/D:/Projects/trenching_bot/frontend/src/features/runs/scenarioStory.ts:1) — preset run setup and percent display.
- [frontend/src/features/runs/timelineChart.ts](/D:/Projects/trenching_bot/frontend/src/features/runs/timelineChart.ts:1) — preset run setup and percent display.
- [frontend/src/features/usage/ApiUsagePage.test.tsx](/D:/Projects/trenching_bot/frontend/src/features/usage/ApiUsagePage.test.tsx:1) — behavior and regression coverage.
- [frontend/src/features/usage/ApiUsagePage.tsx](/D:/Projects/trenching_bot/frontend/src/features/usage/ApiUsagePage.tsx:1) — API usage dashboard and aggregation.
- [frontend/src/features/usage/usageData.test.ts](/D:/Projects/trenching_bot/frontend/src/features/usage/usageData.test.ts:1) — behavior and regression coverage.
- [frontend/src/features/usage/usageData.ts](/D:/Projects/trenching_bot/frontend/src/features/usage/usageData.ts:1) — API usage dashboard and aggregation.
- [frontend/src/lib/codes.ts](/D:/Projects/trenching_bot/frontend/src/lib/codes.ts:1) — shared wording and exact percent helpers.
- [frontend/src/lib/format.ts](/D:/Projects/trenching_bot/frontend/src/lib/format.ts:1) — shared wording and exact percent helpers.
- [frontend/src/lib/glossary.ts](/D:/Projects/trenching_bot/frontend/src/lib/glossary.ts:1) — shared wording and exact percent helpers.
- [frontend/src/lib/percent.test.ts](/D:/Projects/trenching_bot/frontend/src/lib/percent.test.ts:1) — behavior and regression coverage.
- [frontend/src/lib/percent.ts](/D:/Projects/trenching_bot/frontend/src/lib/percent.ts:1) — shared wording and exact percent helpers.
- [frontend/src/mocks/captureHandlers.ts](/D:/Projects/trenching_bot/frontend/src/mocks/captureHandlers.ts:1) — offline API fixtures and handlers.
- [frontend/src/mocks/handlers.ts](/D:/Projects/trenching_bot/frontend/src/mocks/handlers.ts:1) — offline API fixtures and handlers.
- [frontend/src/mocks/setupHandlers.ts](/D:/Projects/trenching_bot/frontend/src/mocks/setupHandlers.ts:1) — offline API fixtures and handlers.
- [frontend/src/paths.ts](/D:/Projects/trenching_bot/frontend/src/paths.ts:1) — page routes and navigation.
- [frontend/src/routes.tsx](/D:/Projects/trenching_bot/frontend/src/routes.tsx:1) — page routes and navigation.
