# Codex brief: timed dip window and time exit in the console

Frontend part of the approved plan [20261023-timed-dip-window-and-exit.md](archive/20261023-timed-dip-window-and-exit.md) (plan unit 7). The backend is done: engine `dip-paper-v4`, decision [docs/adr/20261024-timed-dip-window-and-exit.md](../adr/20261024-timed-dip-window-and-exit.md). This brief is self-contained: every file, change, HTTP shape, test and command you need is below. Claude does not edit `frontend/`; you own every change here.

## What changed for the console

- A run can carry three optional clocks, in **milliseconds**: `dipWindowMs` (a dip counts only within that long after Decu's buy), and the pair `maxHoldMs` / `hardExitStartMs` (a time exit at `exitStart = min(fill + maxHoldMs, Decu's buy + hardExitStartMs)`). Absent or `null` means untimed, as before.
- New outcome codes: close / exit trigger reason `TIME_EXIT`; open-position reason `UNRESOLVED`; the close's `priceAssumption` `NEXT_TRADE` or `STANDING_CURVE`. `NO_EXIT_FILL` is no longer produced (still readable on old runs).
- The agreed preset now carries the three clocks (30 s, 180 s, 240 s) and lists **6** unsupported spec rules.
- API usage has a new purpose `BLOCK_TIME`.
- Datasets can be schema 6.

## HTTP contract (all additive)

### `GET /api/config/defaults`

`defaults` (a `Config`) gains three nullable integers, all `null` here (the defaults stay `mvp()`):

```json
{ "dipWindowMs": null, "maxHoldMs": null, "hardExitStartMs": null }
```

`limits` gains three ranges, in milliseconds:

```json
{ "dipWindowMs": { "min": 1000, "max": 600000 },
  "maxHoldMs": { "min": 1000, "max": 3600000 },
  "hardExitStartMs": { "min": 1000, "max": 3600000 } }
```

### `GET /api/runs/preset`

`config` carries `"dipWindowMs": 30000, "maxHoldMs": 180000, "hardExitStartMs": 240000`. `fields` has 13 entries, in this order:

| field | status | specRef | noteCode |
|---|---|---|---|
| dipBps | AGREED | P6 | null |
| dipWindowMs | AGREED | P7 | null |
| takeProfitBps | AGREED | P9 | null |
| stopLossBps | AGREED | P10 | null |
| maxHoldMs | AGREED | P11 | null |
| hardExitStartMs | AGREED | P11 | null |
| notional | AGREED | P12 | CURVE_PAYMENT_FEES_ON_TOP |
| transactionCostSol | AGREED | entryPriorityFeeSol | NETWORK_FEE_NOT_ADDED |
| quoteMint | AGREED | P15 | null |
| minMarketCapUsd | AGREED | P5 | null |
| latencySlots | ASSUMPTION | null | REPLAY_LATENCY_ASSUMED |
| horizonSlots | ENGINE_DEFAULT | null | RECORDING_BOUND_ONLY |
| maxAgeSlots | ENGINE_DEFAULT | null | null |

`unsupportedRules`, in this order: `{P2, FIRST_EVER_BUY}`, `{P3, SIGNAL_OVER_1_9_SOL}`, `{"Section 5", POST_TRADE_PRICE}`, `{P8, ENTRY_SLIPPAGE_15_PERCENT}`, `{"Section 8", EXIT_RETRY_ECONOMICS}`, `{P13, ONE_POSITION_INCLUDING_IN_FLIGHT}`.

### `GET /api/runs/recommendations`

`USE_AGREED_STRATEGY.facts` is now `{"unsupportedRuleCount": 6, "missingFieldCount": 0}`.

### `POST /api/runs`

`config` accepts `dipWindowMs`, `maxHoldMs`, `hardExitStartMs` as **JSON integers** (milliseconds) or `null`. Per-field 400 `CONFIG_INVALID` errors, under `fields`:

- out of range or not an integer (a string, a fraction, a boolean): `"must be an integer between 1000 and 600000"` (dip window) or `"must be an integer between 1000 and 3600000"` (each exit clock);
- an exit clock sent without its pair: on the **missing** field, `"must be set together with maxHoldMs"` or `"must be set together with hardExitStartMs"`. An invalid exit clock is reported alone, never also as a missing pair.

### `GET /api/runs/{id}` (run detail) and `GET /api/runs/{id}/positions`

- `scenarios[].position` and `positions[]` gain `exitStart: IsoInstant | null` (null for an untimed run).
- `exitTrigger` gains `time: IsoInstant | null`, between `slot` and `reason` (null on runs stored before `dip-paper-v4`). For `TIME_EXIT`, `time` is the exit start and `observationId` is the trade whose curve stood then.
- `close` gains `priceAssumption: "NEXT_TRADE" | "STANDING_CURVE"` (old runs read `NEXT_TRADE`). `NEXT_TRADE`: sold into a later trade's curve. `STANDING_CURVE`: sold against the curve as it stood, with no trade to sell into; for `TIME_EXIT` that is the curve at the exit start, for `TAKE_PROFIT` or `STOP_LOSS` it means no trade landed within 60 s of the trigger (spec Section 8's active phase), so the sale is against the curve standing at the sale slot (`close.slot`). A take profit or stop loss sells into the next trade only when that trade lands within those 60 s.
- For a `STANDING_CURVE` take profit or stop loss, `timeline.exitAt` and the chart's exit marker time are the trigger's time (never earlier); for a `TIME_EXIT` they are the exit start.
- `config` gains the three clocks, as in `Config` above.
- `scenarios[].timeline.exitTriggerAt` is now the trigger's own time; for a `TIME_EXIT`, `exitTriggerAt` and `exitAt` are both the exit start.
- `exitUnavailableReason` can be `UNRESOLVED`.

### `GET /api/runs/{id}/chart`

`paperFills[]` with `phase: "EXIT"` and `reason: "TIME_EXIT"` is placed (`time`, `estimatedTime`) at the exit start, not at the earlier trade whose curve it sold against.

### `GET /api/usage`

`byCall[].purpose` can be `BLOCK_TIME`.

## Changes, file by file

### 1. Types: `frontend/src/api/types.ts`

- `Config`: add `dipWindowMs: number | null; maxHoldMs: number | null; hardExitStartMs: number | null;` with a doc comment "Optional block-time clocks in milliseconds; null when the run is untimed."
- `ConfigLimits`: add `dipWindowMs: Range; maxHoldMs: Range; hardExitStartMs: Range;`.
- `Close`: add `priceAssumption: 'NEXT_TRADE' | 'STANDING_CURVE';`.
- `ExitTrigger`: add `time: IsoInstant | null;` and update its doc comment (it is no longer only "the trade that crossed take profit or stop loss").
- `ScenarioPosition` (and the positions-list type, if separate): add `exitStart: IsoInstant | null;`.
- The `RunStartRequest['config']` type: add the three optional `number | null` fields.

### 2. Run form: `frontend/src/features/runs/runForm.ts` and `NewRunPage.tsx`

- Show the clocks in **seconds**, send **milliseconds**. Add to `RunFormValues`: `dipWindowSeconds: string; maxHoldSeconds: string; hardExitStartSeconds: string;` (typed text; empty means unset).
- `formValuesFrom`: `ms / 1000` as text without a trailing `.0` (30000 → `"30"`), `null` → `""`. The preset fills `30`, `180` and `240`.
- `toRunStartRequest`: an empty field sends `null`; otherwise convert exactly with string math, never floats: accept `^[0-9]+(\.[0-9]{1,3})?$` and send `whole * 1000 + fraction padded to 3 digits` as a JSON integer.
- `validateRunForm`: per field, `"Use whole seconds or up to 3 decimals"` for a bad format, and the range from `limits` shown in seconds (`"Must be between 1 and 600 s"`, `"Must be between 1 and 3600 s"`). If exactly one of the exit pair is filled: `"Set together with the maximum hold"` / `"Set together with the hard exit start"` on the empty one.
- `FIELD_LABELS` / `FIELD_HINTS`:
  - `dipWindowSeconds`: label `Dip window`, hint `Only a dip within this many seconds of Decu's buy triggers an entry; empty for no limit.`
  - `maxHoldSeconds`: label `Maximum hold`, hint `Sell this many seconds after the entry fill at the latest.`
  - `hardExitStartSeconds`: label `Hard exit start`, hint `Sell this many seconds after Decu's buy at the latest; the earlier of the two clocks wins.`
- **Market cap help text** (`FIELD_HINTS.minMarketCapUsd`): replace "at the dip" with "at Decu's buy": `Minimum market cap in US dollars at Decu's buy (strictly above), from the dataset’s SOL/USDT rate.`
- `NewRunPage.tsx`: add the three fields to `SETTING_FIELDS` after the percent fields, with unit `s` in `UNITS`.
- `configLabels.ts`: `SETTING_LABELS` picks them up through `FIELD_LABELS`; in `formatSettingValue`, a `…Seconds` field shows `"30 s"`, and an empty one `"Off"`. `changedSettings` must compare them (empty equals null). Add the clocks to `RUN_INPUTS` (`Dip window`, `Maximum hold`, `Hard exit start`, shown in seconds or `Off`), and in `RunDetailPage.tsx`'s config table next to the other inputs.

### 3. Cycle card: `frontend/src/features/runs/ScenarioCard.tsx`, `scenarioOutcome.ts`

- `scenarioOutcome`: add `TIME_EXIT` → `{ tag: 'TIME', label: 'Time exit', color: 'orange', hex: terminal.rejected, pnl }`.
- Exit section: when `position.exitStart` is set, add a `Stat` `Exit start` with the time (use the existing `Timestamp` component).
- `Exit triggered` stat: for `TIME_EXIT` show `Time exit at <time>` (the trigger's `time`); otherwise keep `<reason> at slot N`.
- Under the close grid, when `close.priceAssumption === 'STANDING_CURVE'`, show a dimmed note: **"Sold against the curve at the exit time"** for `TIME_EXIT`, and **"No trade within 60 s — sold against the curve at the trigger"** for take profit or stop loss. Rename the `TradeLinks` label there from `Exit trade` to `Curve from trade` in that case.
- **Overlapping long numbers.** In the two-column `SimpleGrid`s the long exact decimals (P0, dip level, dip trade price, take profit at, stop loss at, execution prices) overflow into the next column. Give each grid cell `minWidth: 0` and let `Decimal` wrap: add `style={{ overflowWrap: 'anywhere' }}` to the `Stat` value wrapper `div` in `frontend/src/components/Panels.tsx` (or to `Decimal` in `frontend/src/components/DisplayValues.tsx`), so a number wraps inside its own column instead of overlapping. Check at 375 px and at desktop width.

### 4. Codes and labels: `frontend/src/lib/codes.ts`, `frontend/src/features/captures/captureReasons.ts`, `frontend/src/api/setup.ts`

- `captureReasons.ts` `REASONS`:
  - `TIME_EXIT: 'Time exit'`;
  - `UNRESOLVED: 'The exit was triggered, but no curve could pay the sale.'`;
  - `REFERENCE_BELOW_MIN_MARKET_CAP: "Decu's buy was valued at or below the minimum market cap."`;
  - `NO_EXIT_FILL`: keep, reworded `'No later trade could fill the exit (runs before engine dip-paper-v4).'`;
  - `NO_DIP`: `'No dip: the price never fell far enough below the first buy within the window.'` stays.
- `codes.ts` `explanations`:
  - remove `DIP_WINDOW_30_SECONDS`, `TIMED_EXIT` and `EXIT_RETRIES_AND_UNRESOLVED` (keep `MCAP_AT_REFERENCE_BUY` only if something still renders it; otherwise remove);
  - add `EXIT_RETRY_ECONOMICS: 'Exit retries, slippage steps and fee escalation'`;
  - add `RECORDING_BOUND_ONLY: 'Only bounds how far the market is recorded; the clocks end each cycle.'`;
  - remove `NO_SINGLE_WINDOW_IN_SPEC` once nothing renders it;
  - add `NEXT_TRADE: 'Sold into the next trade'`, and label `STANDING_CURVE` by the close's reason, not with one fixed text: `TIME_EXIT` → `'Sold against the curve at the exit time'`; `TAKE_PROFIT` and `STOP_LOSS` → `'No trade within 60 s — sold against the curve at the trigger'` (for example a `standingCurveLabel(reason)` helper next to `explanations`).
- `api/setup.ts`: add `'BLOCK_TIME'` to `UsagePurpose`; `codes.ts` `purposeLabels`: `BLOCK_TIME: "Read the block time where a market window's recording ends, to prove a time exit."`.

### 5. Chart: `frontend/src/features/results/RunChartPage.tsx` (and `timelineChart.ts` if the marker list lives there)

- `scenarioEvents`: the `exitTriggerAt` label and detail use the reason: `TAKE_PROFIT` → `Take profit crossed` / `Price crossed the take-profit level.`; `STOP_LOSS` → `Stop loss crossed` / `Price crossed the stop-loss level.`; `TIME_EXIT` → `Time exit started` / `The exit clock ran out: sold against the curve standing then.`. Today anything but take profit reads as stop loss.
- **Exit-start marker.** When `scenario.position?.exitStart` is set, draw a vertical marker line at that time labelled `Exit start` (dashed, the same muted colour as the horizon line if there is one). Draw it even when the position closed earlier by take profit or stop loss, so the user sees how much time was left; skip it when it is outside the chart range.
- `exitAt` detail: append ` · sold against the standing curve` when `close.priceAssumption === 'STANDING_CURVE'`.

### 6. Mocks: `frontend/src/mocks/handlers.ts`, `frontend/src/mocks/setupHandlers.ts`, `frontend/src/mocks/fixtures.ts`

- `handlers.ts` upload handler (around line 507): accept `schemaVersion` 3, 4, 5 and 6; the message becomes `expected 3, 4, 5 or 6`.
- `handlers.ts` `CONFIG_FIELDS`: add the three clock fields; `validateConfig`: integer range checks from `limits` and the pair rule, with the backend's messages above. The mock defaults carry the three clocks `null` and the three limit ranges.
- `setupHandlers.ts` preset: the 13 fields and 6 rules above, the clocks in `config`, and `facts: { unsupportedRuleCount: 6, missingFieldCount: 0 }`.
- Fixture run detail: give one closed position a `TIME_EXIT` close with `priceAssumption: 'STANDING_CURVE'`, an `exitStart` and a trigger `time`; every other close `priceAssumption: 'NEXT_TRADE'` and every trigger a `time`.

## Capture cutoff recommendations

`DECIDED` captures are now cut by the agreed strategy (`SimulationConfig.agreed()`) and keep at least 4 minutes after Decu's buy; a window typically stops 4–5 minutes after it.

### HTTP contract

- Capture recommendation `CUTOFF_DECIDED` keeps its id and its `dipPercent`, `takeProfitPercent` and `stopLossPercent` facts, now `"20"`, `"55"` and `"40"`, and gains numeric facts `dipWindowSeconds: 30`, `maxHoldSeconds: 180`, `hardExitStartSeconds: 240` and `minimumRecordingSeconds: 240`.
- Its `caveatCode` is renamed `CUTOFF_USES_LEGACY_DEFAULTS` → `CUTOFF_USES_AGREED_STRATEGY`.
- Run recommendation `ADD_LEGACY_COMPARISON` now carries `caveatCode: "COMPARISON_MAY_NEED_FULL_CAPTURE"` (was `null`).

### Changes

- `frontend/src/lib/codes.ts` around line 133: replace the `CUTOFF_USES_LEGACY_DEFAULTS` text with `CUTOFF_USES_AGREED_STRATEGY`: "Windows are cut by the agreed strategy and kept at least {minimumRecordingSeconds} s after Decu's buy. A run with other settings may be refused for coverage; use FULL for those." (read the number from the recommendation's facts, fall back to 240). Add `COMPARISON_MAY_NEED_FULL_CAPTURE`: "The legacy settings may need a longer recording than a DECIDED capture keeps; capture with FULL to compare."
- `frontend/src/lib/codes.ts` around line 102: the `CUTOFF_DECIDED` "why" no longer says "full 30-minute window"; say that each token is recorded until the agreed strategy's outcome is decided, and at least 4 minutes after Decu's buy.
- `frontend/src/components/Recommendations.tsx:43`: drop "(old defaults)"; show the cutoff's levels and clocks from the facts (`20% dip within 30 s, +55% / −40%, time exit at 3 / 4 min`).
- `frontend/src/components/Recommendations.tsx:147`: replace "Today the cutoff is decided with the old default rules…" with the agreed-strategy wording above.
- `frontend/src/features/captures/NewCapturePage.tsx:772`: label "Stop at a decided legacy outcome" → "Stop once the agreed strategy's outcome is decided (at least 4 minutes)"; `:778`: the alert uses `codeLabel('CUTOFF_USES_AGREED_STRATEGY')`.
- `frontend/src/mocks/setupHandlers.ts:142`: the mock `CUTOFF_DECIDED` carries the new facts and `caveatCode: 'CUTOFF_USES_AGREED_STRATEGY'`; the mock `ADD_LEGACY_COMPARISON` carries `caveatCode: 'COMPARISON_MAY_NEED_FULL_CAPTURE'`.

### Tests

- `Recommendations.test.tsx`: `CUTOFF_DECIDED` renders the agreed levels and clocks and the `CUTOFF_USES_AGREED_STRATEGY` text with 240 s; `ADD_LEGACY_COMPARISON` renders its caveat.
- `NewCapturePage` tests: the `DECIDED` option shows the new label and alert; no text says "legacy" or "old default".

## Test expectations

- `runForm.test.ts`: seconds ↔ milliseconds exactly (`"30"` → 30000, `"0.5"` → 500, `"1.234"` → 1234, `""` → null, `"1.2345"` invalid); the preset's config fills `30`/`180`/`240`; the pair rule's message lands on the empty field; ranges show in seconds.
- `NewRunPage.test.tsx`: the three fields render with the preset values; clearing them sends `null`; a changed clock counts as a changed setting; the preset banner says 6 rules and lists `Exit retries, slippage steps and fee escalation`.
- `configLabels.test.ts`: the clocks format as `30 s` and `Off`.
- `RunDetailPage.test.tsx` / a `ScenarioCard` test: a `TIME_EXIT` close shows the `Time exit` badge, `Exit start`, and "Sold against the curve at the exit time"; a `STANDING_CURVE` take profit shows "No trade within 60 s — sold against the curve at the trigger", never the exit-time text; an `UNRESOLVED` open position shows its reason text; a `REFERENCE_BELOW_MIN_MARKET_CAP` rejection shows its text.
- `RunChartPage.test.tsx`: the exit-start marker is drawn at `position.exitStart`; a `TIME_EXIT` trigger is labelled `Time exit started`, not `Stop loss crossed`.
- `ApiUsagePage.test.tsx`: a `BLOCK_TIME` row shows its label.
- Mock upload: a schema 5 and a schema 6 file are accepted.

## Verify

```powershell
cd frontend
npm run lint
npm run format:check
npm test
npm run build
```

Then, with the backend running (`./gradlew bootRun` with the `local` profile) and `npm run dev`, check in the browser at desktop and 375 px width, in light and dark mode: the new-run form with the preset, a run on the dip sample (the former `NO_EXIT_FILL` cycle now stays open as `HORIZON_REACHED`: the v3 sample has no coverage end time to prove that no trade landed within 60 s of its take profit), and the API usage page.
