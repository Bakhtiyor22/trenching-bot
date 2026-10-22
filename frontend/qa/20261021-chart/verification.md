# Run chart handoff verification — 2026-10-21

Implemented the frontend brief in `docs/plans/20261021-run-chart-accuracy-zoom-drawing-codex-brief.md`.
No backend, schema, dependency or Git state changes were made by this implementation.

## Checks

- Full `npm test`: 381 tests passed across 55 files.
- After the browser-discovered ECharts tooltip/geometry fix: 46 focused tests passed across six files, including two new geometry regressions.
- `npm run lint`: passed with zero warnings.
- `npm run build`: passed, including the final geometry extraction.
- `git diff --check -- frontend`: passed with environment and key files excluded.

Tests use the workspace temporary-directory workaround documented in the frontend README.

## Browser verification

Used the mocked local development app and `?mockState=chart-first-slot-gap` at 1440×1000 and 375×812.

- The execution marker remains $9,429; its card explains the $13,195 first recorded trade and the unrecorded remainder of slot 459,490,743.
- The dashed connector and slot timing note appear.
- Native wheel zoom, drag pan and Fit update the URL window; custom hover shows estimated UTC time, slot and index within the slot.
- A trend line can be placed, retains its signed label, and survives exiting and reopening the full-page view. Unit coverage also checks measurements, snapping, editing, deletion and storage failure.
- Clear all confirmation appears above the expanded chart.
- The mobile toolbar stacks, Exit stays reachable, the plot retains its minimum height, and drawing labels wrap.
- Final clean browser run had no ECharts errors or grid-containment warnings during zoom/pan. An existing React Router hydration fallback warning remains unrelated to the chart.

Screenshots: [desktop](desktop.jpg), [mobile](mobile.jpg).

## Local backend verification limit

The already-running backend at `127.0.0.1:8081` still serves the previous chart contract: it has no `ticks`, `coverage` or `millisPerSlot` fields. It was not restarted. The frontend now accepts that response safely, keeps candle closes at bucket end, and states that sub-second positions are unknown. Restart the backend with its existing pending changes before checking the actual reference run's 1,674 ticks and coverage. Browser precision checks above use the reference fixture, not the live capture.

The brief remains in place because this task explicitly limits edits to `frontend/`.
