# Codex brief: post-trade prices and Decu's sells in the console

Frontend part of the approved plan [20261024-post-trade-price-and-wallet-sell.md](20261024-post-trade-price-and-wallet-sell.md) (plan unit 7). The backend is done: engine `dip-paper-v5`, decision [docs/adr/20261025-post-trade-price-and-wallet-sell.md](../adr/20261025-post-trade-price-and-wallet-sell.md), spec amendment in [docs/strategy/decu-dip-v2-spec.md](../strategy/decu-dip-v2-spec.md) Sections 2, 6 and 8. This brief is self-contained: every file, change, HTTP shape, test and command you need is below. Claude does not edit `frontend/`; you own every change here.

## What changed for the console

- A run has two more settings:
  - `priceMeasure`: `"TRADE_AVERAGE"` (default; each trade's own average price, as before) or `"POST_TRADE"` (the curve's price right after each trade, spec Section 5). It decides how the reference (P0), the minimum market cap, the dip and take profit / stop loss are compared. Take-profit and stop-loss **levels** still come from our own average fill.
  - `followWalletSells`: `false` (default) or `true`. When on, a Decu sell of the token before the dip ends the cycle with no entry (`WALLET_SOLD`), and a Decu sell while we hold sells **100%** of our position (close reason `WALLET_SOLD`), whatever amount Decu sold.
- The agreed preset sets `POST_TRADE` and `true`, lists both as `AGREED`, and now lists **5** unsupported spec rules (`POST_TRADE_PRICE` is gone).
- New codes: entry outcome / rejection reason `WALLET_SOLD` and `REFERENCE_UNAVAILABLE`; close and exit trigger reason `WALLET_SOLD`; capture verdict replay failure categories `REFERENCE_UNAVAILABLE` and `SELL_CHECK_NEEDS_TRADES`; run launch error code `SELL_CHECK_NEEDS_TRADES` (HTTP 409).

## HTTP contract (all additive)

### `GET /api/config/defaults`

`defaults` (a `Config`) gains two fields, at their `mvp()` values:

```json
{ "priceMeasure": "TRADE_AVERAGE", "followWalletSells": false }
```

`limits` gains the list of accepted measures, in this order:

```json
{ "priceMeasure": ["TRADE_AVERAGE", "POST_TRADE"] }
```

`followWalletSells` has no limit entry (it is a boolean).

### `GET /api/runs/preset`

- `source.updated` is now `"2026-10-24"`.
- `config` carries `"priceMeasure": "POST_TRADE", "followWalletSells": true` (plus the clocks and levels as before).
- `fields` has **15** entries, in this order:

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
| priceMeasure | AGREED | P4 / Section 5 | null |
| followWalletSells | AGREED | Section 6 / Section 8 | null |
| latencySlots | ASSUMPTION | null | REPLAY_LATENCY_ASSUMED |
| horizonSlots | ENGINE_DEFAULT | null | RECORDING_BOUND_ONLY |
| maxAgeSlots | ENGINE_DEFAULT | null | null |

- `unsupportedRules`, in this order: `{P2, FIRST_EVER_BUY}`, `{P3, SIGNAL_OVER_1_9_SOL}`, `{P8, ENTRY_SLIPPAGE_15_PERCENT}`, `{"Section 8", EXIT_RETRY_ECONOMICS}`, `{P13, ONE_POSITION_INCLUDING_IN_FLIGHT}`.

### `GET /api/runs/recommendations`

`USE_AGREED_STRATEGY.facts` is now `{"unsupportedRuleCount": 5, "missingFieldCount": 0}`.

### `POST /api/runs`

`config` accepts:

- `priceMeasure`: a JSON **string**, exactly `"TRADE_AVERAGE"` or `"POST_TRADE"`, or absent/`null` for the default. Anything else (another string, wrong case, a number, a boolean) is a 400 `CONFIG_INVALID` with `fields.priceMeasure = "must be one of TRADE_AVERAGE, POST_TRADE"`.
- `followWalletSells`: a JSON **boolean**, or absent/`null` for `false`. A string `"true"`, `1` or `0` is a 400 `CONFIG_INVALID` with `fields.followWalletSells = "must be true or false"`.

New refusal: `followWalletSells: true` on a dataset of schema 3 (its observations record no trader or side, so Decu's sells cannot be seen). Nothing is run or stored:

```json
HTTP 409
{
  "code": "SELL_CHECK_NEEDS_TRADES",
  "message": "The wallet-sell rule needs a dataset whose observations record trades schemaVersion=3 minimumSchemaVersion=4 (SELL_CHECK_NEEDS_TRADES)",
  "fields": {}
}
```

The same dataset runs with `followWalletSells: false`.

### `GET /api/runs/{id}` (run detail)

- `config` gains `priceMeasure` and `followWalletSells`, as in `Config` above. Runs stored before `dip-paper-v5` read `"TRADE_AVERAGE"` and `false`.
- Each `scenarios[]` gains two fields, between `trigger` and `status`:
  - `referenceObservationId: string | null`: under `POST_TRADE`, the market observation of Decu's buy that `referencePrice` was read from (link it like the dip trade). `null` under `TRADE_AVERAGE` and when `watch` is `REFERENCE_UNAVAILABLE`.
  - `walletSell: { eventId: string; slot: number } | null`: set exactly when `watch` is `WALLET_SOLD`; `eventId` is Decu's sell's market observation id.
- `watch` can be `WALLET_SOLD` or `REFERENCE_UNAVAILABLE`; the matching `rejectionReason` is the same code and the scenario has no fill and no position.
- When `watch` is `REFERENCE_UNAVAILABLE`, `referencePrice` and `dipPrice` hold Decu's average price **as a placeholder only**: the real reference is unknown. Never show them as the reference.
- `position.close.reason` and `position.exitTrigger.reason` can be `WALLET_SOLD`. For it, `exitTrigger.observationId` is Decu's sell. **Do not assume `exitTrigger.slot` is the sell's slot:** when Decu sold after we decided to buy but before our buy landed, `exitTrigger.slot` and `exitTrigger.time` are our fill's, while `observationId` is still the earlier sell. Link the observation; label the slot "Exit started at slot".

Examples:

```json
{ "watch": "WALLET_SOLD", "rejectionReason": "WALLET_SOLD", "status": "REJECTED",
  "referenceObservationId": "pumpfun-trade:5Wq…:2:-:6AeU…pump",
  "walletSell": { "eventId": "pumpfun-trade:3Kx…:0:-:6AeU…pump", "slot": 460699402 },
  "trigger": null, "fill": null, "position": null }
```

```json
{ "watch": "REFERENCE_UNAVAILABLE", "rejectionReason": "REFERENCE_UNAVAILABLE", "status": "REJECTED",
  "referencePrice": "0.00000007940000000000000000000000000000", "referenceObservationId": null, "walletSell": null }
```

```json
{ "position": { "close": { "reason": "WALLET_SOLD", "priceAssumption": "NEXT_TRADE" },
  "exitTrigger": { "observationId": "fixture:SL:2003:0", "slot": 2003, "time": "2026-10-01T00:33:23Z", "reason": "WALLET_SOLD" } } }
```

### `GET /api/runs/{id}/orders` and `GET /api/runs/{id}/positions`

`orders[].rejectionReason` can be `WALLET_SOLD` or `REFERENCE_UNAVAILABLE`; `positions[].close.reason` and `positions[].exitTrigger.reason` can be `WALLET_SOLD`. No new fields.

### Capture verdicts (`GET /api/captures/{id}` and `GET /api/captures/{id}/cases`)

`replay.category` can be `REFERENCE_UNAVAILABLE` (the recorded market does not contain Decu's buy, so the agreed post-trade reference is unknown; the capture predates the 50-slot lead and must be re-captured) or `SELL_CHECK_NEEDS_TRADES` (the dataset is schema 3). A `WALLET_SOLD` dip is a `NEGATIVE_CASE` with `dip: NOT_QUALIFIED` and reason `WALLET_SOLD`.

## Changes, file by file

### 1. Types: `frontend/src/api/types.ts`

- `Config`: add `priceMeasure: PriceMeasure; followWalletSells: boolean;` with `export type PriceMeasure = 'TRADE_AVERAGE' | 'POST_TRADE';`.
- `ConfigLimits`: add `priceMeasure: PriceMeasure[];`.
- `Scenario`: add `referenceObservationId: string | null;` and `walletSell: WalletSell | null;` with `export interface WalletSell { eventId: string; slot: number; }`. Update the `watch` doc comment to list `WALLET_SOLD` and `REFERENCE_UNAVAILABLE`, and the `referencePrice` comment: "P0: Decu's average price on his first buy, or under POST_TRADE the price right after it; a placeholder when watch is REFERENCE_UNAVAILABLE."
- `ExitTrigger` doc comment: mention `WALLET_SOLD`, whose `observationId` is Decu's sell and whose `slot` may be our fill's.
- The `RunStartRequest['config']` type: add `priceMeasure?: PriceMeasure | null; followWalletSells?: boolean | null;`.

### 2. Run form: `frontend/src/features/runs/runForm.ts`, `NewRunPage.tsx`, `configLabels.ts`

- `RunFormValues`: add `priceMeasure: PriceMeasure` and `followWalletSells: boolean`. `formValuesFrom` copies them from the config (preset → `POST_TRADE` / `true`; defaults → `TRADE_AVERAGE` / `false`). `toRunStartRequest` always sends both (a string and a boolean).
- `NewRunPage.tsx`: two new controls in `SETTING_FIELDS` after the clocks:
  - a `Select` labelled **Price measure** with options from `limits.priceMeasure`: `TRADE_AVERAGE` → "Each trade's average price", `POST_TRADE` → "Price after each trade (spec)". Hint: "Which price the reference, the dip and take profit / stop loss compare. The exit levels still come from our own fill."
  - a `Checkbox` labelled **Follow Decu's sells (no entry, and exit if holding)**. Hint: "No entry once Decu sells the token before the dip, including a sell in the dip's own slot; sell everything if he sells while we hold. Needs a dataset of schema 4 or later."
- `FIELD_LABELS` / `FIELD_HINTS`: the labels and hints above. Field errors from the backend land on `priceMeasure` and `followWalletSells`.
- `configLabels.ts` `formatSettingValue`: `priceMeasure` → "Average price" / "Post-trade price"; `followWalletSells` → "On" / "Off". `changedSettings` compares both. Add both to `RUN_INPUTS` so `RunDetailPage.tsx`'s config table shows them.
- `RunStartError` (`NewRunPage.tsx` around line 505): when `error.code === SELL_CHECK_NEEDS_TRADES`, title **"This dataset cannot show Decu's sells"** and text **"Its market recording is schema 3, which does not record who traded. Turn off "Follow Decu's sells" to run it, or re-export the capture as a newer dataset."**, plus a button **Run without following sells** that resubmits the same config with `followWalletSells: false` (the same pattern as the coverage-ended legacy button).
- Preset banner: the unsupported-rules count reads from the response (now 5).

### 3. Cycle card: `frontend/src/features/runs/ScenarioCard.tsx`, `scenarioOutcome.ts`

- The reference `Stat` (line ~145, `First buy price, P0 (…)`): under `POST_TRADE` (`config.priceMeasure`, pass it in from `RunDetailPage`) label it **"Reference price (after Decu's buy) (…)"**, and add `<TradeLinks label="Decu's buy" observationId={scenario.referenceObservationId} />` when it is set. Under `TRADE_AVERAGE` keep the existing label.
- `REFERENCE_UNAVAILABLE`: show the reference and the dip level as **"Unknown"** (never the placeholder decimals) and, in the "No entry" alert, **"Decu's buy is not in the recorded market, so the reference is unknown."**
- `WALLET_SOLD` (no entry): in the "No entry" alert, **"Decu sold before the dip"**, then `Sold at slot <walletSell.slot>` and `<TradeLinks label="Decu's sell" observationId={walletSell.eventId} />`.
- `scenarioOutcome`: add `WALLET_SOLD` close → `{ tag: 'SOLD', label: 'Sold because Decu sold', color: 'orange', hex: terminal.rejected, pnl }`. For no entry the existing `No entry` stays.
- Exit section: for `exitTrigger.reason === 'WALLET_SOLD'` the `Exit triggered` stat reads **"Sold because Decu sold"**, with `<TradeLinks label="Decu's sell" observationId={exitTrigger.observationId} />`, and the slot shown as "Exit started at slot N" (not "Decu sold at slot N").

### 4. Codes and labels: `frontend/src/features/captures/captureReasons.ts`, `frontend/src/lib/codes.ts`, `frontend/src/features/captures/TokenVerdict.tsx`

- `captureReasons.ts` `REASONS`:
  - `WALLET_SOLD: 'Decu sold the token before the dip.'` as an entry reason, and `'Sold because Decu sold'` as a close reason (add a close-reason lookup if `REASONS` is shared; `RunTables.tsx` uses `ReasonText` for both orders and positions, so pick the text by context);
  - `REFERENCE_UNAVAILABLE: "Decu's buy is not in the recorded market, so the reference is unknown."`;
  - `SELL_CHECK_NEEDS_TRADES: 'The dataset does not record who traded (schema 3), so Decu\'s sells cannot be followed.'`.
- `codes.ts`: export `SELL_CHECK_NEEDS_TRADES = 'SELL_CHECK_NEEDS_TRADES'` next to `MARKET_COVERAGE_ENDED`, with an `explanations` entry (the text above); remove `POST_TRADE_PRICE` once nothing renders it.
- `TokenVerdict.tsx` line 93: show `reasonText(replay.category)` for `REFERENCE_UNAVAILABLE` and `SELL_CHECK_NEEDS_TRADES` too (as already done for `FIRST_BUY_MISMATCH`), still under "Replay failed — this is not a strategy outcome". For `REFERENCE_UNAVAILABLE` add: "Capture the token again so its window includes Decu's buy."

### 5. Chart: `frontend/src/features/results/RunChartPage.tsx`, `timelineChart.ts`

- An exit trigger with reason `WALLET_SOLD`: label **"Decu sold"**, detail "Decu sold the token: our whole position is sold." Place the marker at Decu's sell observation (look it up by `exitTrigger.observationId`), not at `exitTrigger.slot`.
- A `WALLET_SOLD` cycle without entry: a marker **"Decu sold before the dip"** at the sell observation (`walletSell.eventId`).

### 6. Mocks: `frontend/src/mocks/handlers.ts`, `frontend/src/mocks/setupHandlers.ts`, `frontend/src/mocks/fixtures.ts`

- `handlers.ts` `CONFIG_FIELDS`: add `priceMeasure` and `followWalletSells`; `validateConfig` uses the backend's messages above. Defaults `TRADE_AVERAGE` / `false`; limits `priceMeasure: ['TRADE_AVERAGE', 'POST_TRADE']`. A run with `followWalletSells: true` on a mock schema-3 dataset answers the 409 above.
- `setupHandlers.ts` preset: the 15 fields and 5 rules above, `updated: '2026-10-24'`, the two fields in `config`, `facts.unsupportedRuleCount: 5`. Remove the `POST_TRADE_PRICE` rule (line 64).
- `fixtures.ts` run detail: one scenario `WALLET_SOLD` (with `walletSell`), one `REFERENCE_UNAVAILABLE`, one closed position with a `WALLET_SOLD` close whose `exitTrigger.slot` differs from the sell observation's slot; every other scenario gets `referenceObservationId` and `walletSell: null`; the run's `config` carries both new fields.

## Test expectations

- `runForm.test.ts`: the preset fills `POST_TRADE` / `true`, the defaults `TRADE_AVERAGE` / `false`; `toRunStartRequest` sends `"priceMeasure": "POST_TRADE"` and `"followWalletSells": true` as a string and a boolean.
- `NewRunPage.test.tsx`: the select and the checkbox render with the preset values; changing either counts as a changed setting; a 409 `SELL_CHECK_NEEDS_TRADES` shows the new title and text, and its button resubmits with `followWalletSells: false`; the preset banner says 5 rules and no longer lists post-trade prices.
- `configLabels.test.ts`: `Post-trade price`, `Average price`, `On`, `Off`.
- `ScenarioCard.test.tsx`: under `POST_TRADE` the label "Reference price (after Decu's buy)" and a link to `referenceObservationId`; a `WALLET_SOLD` entry shows "Decu sold before the dip", the sell's slot and a link to `walletSell.eventId`; a `REFERENCE_UNAVAILABLE` cycle shows "Unknown" for the reference and dip level and never the placeholder decimals; a `WALLET_SOLD` close shows "Sold because Decu sold" and links `exitTrigger.observationId`, and shows the trigger's slot as the exit start, not as Decu's sell slot.
- `TokenVerdict.test.tsx`: both new categories show their texts.
- `RunChartPage.test.tsx`: the `WALLET_SOLD` marker sits at the sell observation, not at `exitTrigger.slot`.

## Verify

```powershell
cd frontend
npm run lint
npm run format:check
npm test
npm run build
```

Then, with the backend running (`./gradlew bootRun` with the `local` profile) and `npm run dev`, check in the browser at desktop and 375 px width, in light and dark mode: the new-run form with the preset, a run with `followWalletSells` on the bundled v3 sample (the 409 message and its button), the same sample with the preset but the sell rule off (every supported cycle shows `REFERENCE_UNAVAILABLE`, since the sample's market data has no Decu buy), and a run on a recent capture.
