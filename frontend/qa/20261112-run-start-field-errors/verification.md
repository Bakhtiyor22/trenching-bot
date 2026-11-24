# New run: field errors from a refused start — 2026-11-12

`NewRunPage` now puts a refused start's `fields` on the inputs that show them. The backend names the clocks by their
millisecond API properties (`dipWindowMs`, `maxHoldMs`, `hardExitStartMs`); they land on the seconds inputs, restated
in seconds in the form's own words ("Must be between 1 and 600 s", "Set together with the maximum hold"). A field no
input shows (`presetId`) is said in the refusal text, unless the backend message already names it. "See the highlighted
field(s)." is said only while an input still shows one of the refusal's reasons, and the backend message is closed
with a period before it.

## Checks

- `npm run lint`, `npx tsc -b`: pass.
- `npm test`: 70 files, 572 tests pass (11 new: 5 in `runForm.test.ts`, 6 in `NewRunPage.test.tsx`).
- Prettier on the touched lines: clean. The older drift in `NewRunPage.tsx` and `NewRunPage.test.tsx` was left alone.

## Browser verification

`frontend-mocks` preview, at 1440×900 and 375×812, with two new dev-only mock states:

- `?mockState=run-limits-changed`: `GET /api/config/defaults` serves clock limits up to 7200 s, which the mock's
  `POST /api/runs` (the backend's limits) then refuses. Typing 900 in Dip window and starting gives a 400 for
  `dipWindowMs`: only Dip window is marked invalid, with "Must be between 1 and 600 s"; the refusal reads
  "Simulation configuration is invalid. See the highlighted field."; no millisecond figure is shown. Editing the field
  clears the highlight, and the refusal then reads "Simulation configuration is invalid".
  [1440](new-run-clock-1440.jpg), [375 field](new-run-clock-field-375.jpg), [375 refusal](new-run-clock-refusal-375.jpg).
- `?mockState=preset-retired`: `GET /api/runs/preset` serves a retired preset id, so `POST /api/runs` answers 400
  `INVALID_REQUEST` for `presetId`. The refusal reads "Invalid request field=presetId reason=is not a known preset",
  once, with no pointer to fields, and no input is marked invalid. [1440](new-run-preset-1440.jpg),
  [375](new-run-preset-375.jpg).
- Long ids: the refusal text wraps anywhere. With the sample dataset deleted after the page loaded (the mock's
  `DELETE /api/datasets/:id`), Start run gives a 404 "Replay dataset not found datasetId=<64 hex>"; at 375 px the id
  wraps over three lines inside the alert. [375](new-run-long-id-375.jpg).

No horizontal scroll at 375 px. The console only showed the expected 400s, plus 502s from one moment when the MSW
service worker stopped intercepting (requests reached the Vite proxy with no backend running); after a reload the same
steps worked.
