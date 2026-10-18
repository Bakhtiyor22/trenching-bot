# Codex handoff: capture, run and results experience

You work only under `frontend/`. The backend contract below is planned in [20261016-console-capture-run-ux.md](archive/20261016-console-capture-run-ux.md) (approved 2026-10-16). Every backend unit it lists is delivered, and the contract below was checked against the shipped DTOs and controllers on 2026-10-17 (see the note at the end). Build each task against MSW mocks in `frontend/src/mocks/` first, then check it against the real backend. Keep Mantine 9, ECharts 6, React Query and the existing dark terminal theme; add no dependency. Amounts, prices and percents are decimal strings: convert to numbers only for plotting (`toChartNumber`), and show the exact strings in tooltips.

**Design (agreed):** Claude Design canvas https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4, 7 pages. The copy on the artboards is the intended copy, and sample numbers are illustrative.

| Artboard | Route | Summary |
|---|---|---|
| 1 Captures list | `/captures` | Newest first; status chips with counts as filters; sort select; "What do the four steps mean?" disclosure; one row per capture with name, wallet label, token, status chip and reason, 4-segment step bar with "Step n of 4 · Stage" and progress, the next-step button with its cost hint, relative + exact created time. |
| 2 New capture | `/captures/new` (was a form on the list page) | 1 Whose trades? (Decu suggestion card, applied by default; wallet input with label chip; recent wallets). 2 Which token? (Decu's recent activity table, filters, pick a buy with a radio; "Load 20 older"; "Paste a token mint"). 3 Name (generated, editable, live availability). Footer with cost note and "Create and start wallet step". Right column: suggestion cards with Apply/Undo, Adjust, Dismiss. |
| 3 Capture detail | `/captures/:id` | Breadcrumb; name with rename; "now" panel (status, plain explanation, one primary next action with cost hint); 4-step stepper; progress card (bar, counts, requests this run / whole capture, data, run time, last progress, link to API usage); per-token cards in plain words with the technical code shown small; "Terms on this page" glossary; Advanced disclosure for limits. |
| 4 New run | `/runs/new` | Dataset select with source-capture link; green "Agreed strategy" banner (Applied, Read the spec, Reset to agreed) with the warning that dip-entry v1 applies 6 rules exactly, 8 need decu-dip v2, and 1 setting is missing; settings grid in **percent** with a status tag per field (Agreed / Changed by you · agreed X / Agreed, measured differently / Assumption / Engine default / Missing in agreed strategy); "In plain words" summary and Start run; suggestion cards. |
| 5 Results chart | `/runs/:id/chart` (new) | Breadcrumb Capture › Dataset › Run › Chart; context strip (token, result, settings, simulator, token stepper, Back to run, Open capture); toolbar (range presets This trade/1m/5m/15m/Whole window, From/To inputs, zoom +/−, pan ←/→, Reset view, Market cap/Price toggle, Layers, Full screen); large chart with level lines, waiting/holding bands, Decu B marker, gold paper diamonds; navigator strip; hint line; "What happened · click to jump" list; "How to read this". |
| 6 API usage | `/usage` (new nav item) | Period segmented control + "For" select (everything / one capture); explicit period sentence; 4 tiles with units; requests-per-bucket stacked bars; table grouped by provider (call, used for, requests, share, failed, rate-limited, data, last call); "By task" bars; "Limits and units". |
| 7 States | — | Loading, empty and error/warning states for each of the pages above (see Acceptance). |

## HTTP contract

All endpoints are additive; existing fields keep their meaning. Errors use the existing console error body (`code`, `message`, `fields`). Two errors add one top-level field: a 429 `PROVIDER_RATE_LIMITED` adds `retryAfterSeconds` (a whole number, rounded up) only when the provider sent `Retry-After`, and is absent otherwise; a capture 409 `NAME_TAKEN` adds `suggestion`. Any other error has neither key.

**Wallet activity.** `GET /api/wallets/{address}/activity?limit=20&before=<signature>&refresh=false`. `limit` is 1–50, default 20. `before` is a transaction signature, normally the previous page's `nextBefore`; a blank `before` reads the newest page.

- `200`: `{ wallet, source: "pumpfun-user-volume-accumulator", actions: [Action], unreadable, scanned, nextBefore: string|null, requestsUsed, cachedAt: instant|null }`. `nextBefore` is null once the listing ended; `cachedAt` is set only when the page came from the 60 s cache; `requestsUsed` is the provider requests this response sent, 0 when it came from the cache.
- `Action`: `{ signature, instructionIndex, innerIndex: int|null, slot, blockTime: instant|null, side: "BUY"|"SELL", mint, solLamports: string, tokenRaw: string, tokenDecimals: 6, venue: "PUMPFUN_CURVE", earlierActionInPage: bool, overSignalThreshold: bool, signalBasis: "CURVE_PAYMENT_EXCLUDING_FEES", capturedIn: [{ captureId, displayName }] }`. `capturedIn` is always an array; it is empty when the backend runs without the capture console (outside the local profile) or cannot read a capture's files.
- Errors: `400 INVALID_ADDRESS` (`fields.address`); `400 INVALID_REQUEST` with `fields.limit` or `fields.before` for a `limit` outside 1–50 or not a number, or a `before` that is not a signature; `409 RPC_NOT_CONFIGURED`; `409 NOT_MAINNET`; `429 PROVIDER_RATE_LIMITED` with top-level `retryAfterSeconds` when known (show a countdown only then); `502 PROVIDER_UNAVAILABLE`; `403 LOCAL_ACCESS_REQUIRED` through the public preview tunnel, as for the capture routes. The provider errors have empty `fields`.
- It costs live provider requests, at most `limit` + 2 per call (22 at 20, 52 at 50): call it only on an explicit user action (open the token step with a wallet filled in, Refresh, Load older). It is never polled. Responses are cached server-side for 60 s per wallet, `limit` and `before`; `refresh=true` bypasses the cache.

**Capture config.** `GET /api/captures/config` adds `suggestedWallets: [{ label, address, reasonCode: "AGREED_STRATEGY_WALLET" }]` and `recentWallets: [{ address, label: string|null, lastUsedAt }]` (up to 5, newest first; `label` is the suggested wallet's label when the address is one). `suggestedWallets` comes from `trenching.console.suggested-wallets`, which `application.yml` sets to Decu; a deployment can empty it, so handle an empty list: no prefill and no suggestion card.

**Capture list.** `GET /api/captures?page=0&size=20&sort=createdAt,desc&status=<Status>`

- `sort` ∈ `createdAt,desc` (default) | `createdAt,asc` | `name,asc` | `attention`.
- The response adds `statusCounts: { [Status]: number }` over all captures, with every status present (zero included). `total` counts the captures the `status` filter keeps.
- An unknown `sort` or `status` returns `400 INVALID_REQUEST` with `fields.sort` or `fields.status`.
- Every session (list and `GET /api/captures/{id}`) adds `displayName` and `summary`.

**Capture summary.** `summary`: `{ status, reasonCode: string|null, currentStep: { index: 1..4, stage }, steps: [{ stage, state }], progress: { processed, selected, percent: string }|null, nextAction: { kind, stage: string|null, attemptId: string|null, caseId: string|null, maxRequests: number|null }, cases: { total, ready, blocked, downloading }, lastProgressAt: instant|null }`

- `Status` ∈ `NOT_STARTED`, `RUNNING`, `WAITING_RETRY`, `PAUSED`, `STOPPED_LIMIT`, `STOPPED_DEADLINE`, `NEEDS_ATTENTION`, `READY_TO_REPLAY`, `DONE`, `CANCELLED`.
- `stage` ∈ `WALLET`, `MARKETS`, `RATES`, `DATASET`; `state` ∈ `DONE`, `ACTIVE`, `STOPPED`, `FAILED`, `TODO`.
- `nextAction.kind` ∈ `START_WALLET`, `RESUME_WALLET`, `RETRY_WALLET`, `START_MARKETS`, `RESUME_MARKETS`, `CAPTURE_RATES`, `REGISTER_DATASET`, `START_RUN`, `VIEW_RESULTS`, `INSPECT`, `WAIT`, `NONE`.
- `percent` is floored, so it never shows 100 before completion.
- `progress` is null while the total is unknown (discovery). Then show the stage only, never a guessed bar.

**Capture create and rename.**

- `POST /api/captures`: `name` is optional (blank means the server generates it); new optional `timeZone` (send `Intl.DateTimeFormat().resolvedOptions().timeZone`). A taken name returns `409 NAME_TAKEN` with `suggestion`.
- `GET /api/captures/names/check?name=` returns `{ available, normalized, suggestion: string|null }`. Debounce 300 ms.
- `PATCH /api/captures/{id}` with `{ requestId, name }` renames, with the same 409. `nextAction` maps onto the existing operation, attempt and case endpoints; the frontend still sends the same operation requests as today.

**Recommendations.**

- `GET /api/captures/recommendations?wallet=&scope=&mint=` and `GET /api/runs/recommendations?datasetId=` return `[{ id, appliesTo: "CAPTURE"|"RUN", setting: { field, value: string }|null, facts: { [name]: string|number }, caveatCode: string|null }]`. Blank capture parameters count as not chosen. Always send `datasetId`: blank gives `400 INVALID_REQUEST` (`fields.datasetId`), unknown `404 DATASET_NOT_FOUND`.
- Ids: capture `SCOPE_WALLET_TOKEN`, `CUTOFF_DECIDED` (caveat `CUTOFF_USES_LEGACY_DEFAULTS`), `ENABLE_RECOVERY`, `DECU_PUMPFUN_SCOPE`; run `USE_AGREED_STRATEGY`, `ADD_LEGACY_COMPARISON`, `SINGLE_TOKEN_DATASET`.
- Capture recommendations, in this order: `SCOPE_WALLET_TOKEN` (always; setting `{field:"scope", value:"wallet-token"}`, facts `{limit, scanPages, requestBudget}` plus `mint` when given); `CUTOFF_DECIDED` (only when rates are configured; setting `{field:"cutoffMode", value:"DECIDED"}`, facts `{dipPercent, takeProfitPercent, stopLossPercent}` of the legacy defaults the cutoff uses); `ENABLE_RECOVERY` (always; setting `{field:"recovery", value:"ENABLED"}`, facts `{requests, bytes, attempts}` for the chosen scope); `DECU_PUMPFUN_SCOPE` (only for a suggested wallet with scope `wallet-references`; setting `{field:"scope", value:"pumpfun-user-buys"}`, facts `{label}`).
- The frontend owns the title and "why" copy per id (use the artboard copy).

**Run preset.** `GET /api/runs/preset` returns `{ id: "decu-dip-v2-on-dip-entry-v1", source: { document: "docs/strategy/decu-dip-v2-spec.md", strategy: "decu-dip", version: 2, updated: "2026-10-11" }, engine: "dip-entry v1", config: Config, fields: [{ field, status, specRef: string|null, noteCode: string|null }], unsupportedRules: [{ specRef, code }] }`.

- `status` ∈ `AGREED`, `AGREED_MEASURED_DIFFERENTLY`, `ASSUMPTION`, `ENGINE_DEFAULT`, `MISSING`.
- The preset's `horizonSlots` is the engine default with status `MISSING`: show it flagged, never as agreed.

**Config percents.** `Config` (in `GET /api/config/defaults` → `defaults`, the preset `config` and run detail `config`) adds `dipPercent`, `takeProfitPercent`, `stopLossPercent` (strings such as `"20"`, `"15.5"`, `"0.01"`), each right after its bps field. Limits add `dipPercent: { min: "0.01", max: "99.99" }` and likewise for take profit and stop loss. Decimal fields keep their existing scale: the preset's `notional` reads `"0.100000000000"` and `transactionCostSol` `"0.001000000000"`; compare numerically and display trimmed (`0.1 SOL`). Fills and closes add `feePercent` after `feeBps` and `priceImpactPercent` after `priceImpactBps`.

Preset `fields[].field` uses the config JSON names, in this order: `dipBps`, `takeProfitBps`, `stopLossBps`, `notional`, `transactionCostSol`, `quoteMint`, `minMarketCapUsd`, `latencySlots`, `horizonSlots`, `maxAgeSlots`. `noteCode` values: `CURVE_PAYMENT_FEES_ON_TOP` (notional), `NETWORK_FEE_NOT_ADDED` (transactionCostSol), `MEASURED_AT_DIP_INCLUSIVE` (minMarketCapUsd), `REPLAY_LATENCY_ASSUMED` (latencySlots), `NO_SINGLE_WINDOW_IN_SPEC` (horizonSlots), otherwise null. `unsupportedRules[].code`, in order: `FIRST_EVER_BUY` (P2), `SIGNAL_OVER_1_9_SOL` (P3), `MCAP_AT_REFERENCE_BUY` (P5), `DIP_WINDOW_30_SECONDS` (P7), `ENTRY_SLIPPAGE_15_PERCENT` (P8), `TIMED_EXIT` (P11), `EXIT_RETRIES_AND_UNRESOLVED` (Section 8), `ONE_POSITION_INCLUDING_IN_FLIGHT` (P13).

Run recommendation facts: `USE_AGREED_STRATEGY` has setting `{field:"presetId", value:"decu-dip-v2-on-dip-entry-v1"}` and facts `{unsupportedRuleCount, missingFieldCount}`; `ADD_LEGACY_COMPARISON` has setting null and facts `{dipPercent, takeProfitPercent, stopLossPercent}` of the legacy defaults; `SINGLE_TOKEN_DATASET` appears only when the dataset has exactly one pair, facts `{pairCount}`.

**Run start.** `POST /api/runs`: `config` accepts `dipPercent`, `takeProfitPercent`, `stopLossPercent` (strings) instead of the bps fields, plus optional top-level `presetId` (only `decu-dip-v2-on-dip-entry-v1` is accepted, anything else returns 400 `INVALID_REQUEST` on field `presetId`; it is logged, not stored, and never changes the run id).

- Errors: one code per response, by precedence `CONFLICTING_FIELDS` (a percent and its bps disagree) > `INVALID_PERCENT` (more than 2 decimals, a JSON number instead of a string, or out of range) > `CONFIG_INVALID`; `fields` still lists every bad field.
- Send percents, not bps. Do not convert on the client.

**Run detail.** `GET /api/runs/{id}` adds:

- `config` percents.
- `source: { datasetId, datasetName: string|null, captureId: string|null, captureDisplayName: string|null, caseId: string|null }`, always present. `datasetName` is null once the dataset was deleted from the catalog (its runs stay). The capture fields are null for an uploaded dataset, a deleted capture, a capture file the backend cannot read, or a backend without the capture console; `caseId` is set when the dataset came from a case export rather than the whole-capture export.
- Per scenario, `timeline: { firstBuyAt, dipAt, entryAt, exitTriggerAt, exitAt }`, observation block times, each an instant or null when that stage did not happen. The timeline holds only these five instants; `horizonEndSlot` stays where it already was, a field of the scenario itself.
- On fills and closes, `feePercent` and `priceImpactPercent` (signed strings).

**Dataset detail.** `GET /api/datasets/{id}` adds `source`, as on run detail. It is on the detail only: the dataset list and registration responses leave the key out.

**Chart.** `GET /api/runs/{id}/chart` and `/api/datasets/{id}/chart` add `extent: { firstTime, lastTime, firstSlot, lastSlot }|null`, the pair's full data range, independent of `from`/`to`; null when the pair has no observations. The rest is unchanged ([previous brief](20261015-chart-frontend-codex-brief.md)).

**API usage.** `GET /api/usage?period=LAST_HOUR|LAST_24_HOURS|LAST_7_DAYS|ALL&captureId=` returns `{ from, to, bucketSeconds: 300, recordingStartedAt: instant|null, totals: Totals, byCall: [{ provider, operation, purpose, totals: Totals, sharePercent, lastCallAt }], byTask: [{ task, totals: Totals }], series: [{ bucketStart, provider, requests }], pacing: [{ provider, minRequestIntervalMillis, maxConcurrentRequests }] }`.

- `Totals` = `{ requests, failedRequests, rateLimitedResponses, responseBytes, cacheHits }`.
- `provider` ∈ `SOLANA_RPC`, `BINANCE`, `TELEGRAM`.
- `purpose` ∈ `READ_TRANSACTION`, `LIST_SIGNATURES`, `MAINNET_CHECK`, `MINT_PROGRAM_CHECK`, `CONNECTION_CHECK`, `MINUTE_RATES`, `LIVE_RATE`, `UNKNOWN`.
- `task` ∈ `WALLET_STEP`, `MARKET_STEP`, `RATES_STEP`, `CASE_RATES`, `WALLET_ACTIVITY`, `CONNECTION_CHECK`, `LIVE_PRICE`, `UNATTRIBUTED`.
- `period` defaults to `LAST_HOUR`. `from` is aligned to 5 minutes; `to` is the request time, not aligned. `recordingStartedAt` is the oldest bucket recorded for any capture, or null when nothing was recorded; `ALL` starts there. `sharePercent` is a string with one decimal (`"0"` when there were no requests). `pacing` lists `SOLANA_RPC` only.
- An unknown `period` returns `400 INVALID_REQUEST` with `fields.period`; a `captureId` that is not a capture id returns it with `fields.captureId`. A blank `captureId` means everything.
- Poll every 30 s while visible (`refetchIntervalInBackground: false`).

## Tasks in dependency order

Each task lists its backend dependency (plan unit). Tasks 1–3 need nothing new from the backend.

1. **Shared plain-language layer** (no backend dependency).
   - `src/lib/glossary.ts` (term → one-sentence meaning: Market window, Slot, Request, Gap, Cutoff, Validated, Case/Token, Dataset, Run, Rates, Recovery, Paper trade, Market cap basis) and `src/lib/codes.ts` (every `reasonCode`, `Status`, `nextAction.kind`, recommendation id, caveat, `purpose`, `task` and error code → label + explanation, falling back to the raw code in mono).
   - A `<Term>` component (dotted underline + tooltip/popover, keyboard reachable) and `<CodeText>` (small mono "Technical code: X").
   - Tests: every code in the contract enums has an entry (a typed `Record<Union, …>` so a missing one fails `tsc`).
2. **Percent formatting** (no backend dependency for display). `src/lib/percent.ts`: `formatPercent(s: string, { signed })` (`"20"` → `20%`, `"-0.11"` → `−0.11%`). Replace every `bpsLabel`/`… bps` display in `features/runs/configLabels.ts`, `RunInspector`, `ScenarioCard`, `RunTables`, `ComparePage`, `MarketActionCard` with the server's percent strings once they arrive; until then derive the display from bps with exact string arithmetic. Tests: table of bps → percent strings, including 1, 125, 1550, 9999, −11.
3. **Results chart page** (`/runs/:id/chart`; can start on the current chart API).
   - New `features/results/RunChartPage.tsx` using the existing `TradeChart` pieces.
   - Toolbar per artboard 5: presets (This trade = scenario first buy − 10 s to exit + 30 s, or horizon end; 1m/5m/15m from first buy; Whole window = `extent`), From/To inputs (local time, validated against `extent`), zoom in/out (halve/double the span around the centre), pan (shift by 25% of the span), Reset view (back to This trade), Market cap/Price toggle, Layers menu (Decu trades, other traders, paper fills, levels), Full screen (Fullscreen API with a fallback).
   - Mouse: wheel zooms without Ctrl when the pointer is over the plot, drag pans, and the ECharts slider `dataZoom` is the navigator.
   - Keyboard (when the chart has focus): `+`/`-`, `←`/`→`, `0`.
   - Re-request on view change, debounced 300 ms, keeping the previous candles on screen until new data arrives.
   - URL holds `pair`, `from`, `to`, `view` (mcap|price) so reload and back restore the view.
   - Height `max(480px, 62vh)`, full content width with no sidebar; sidebar navigation moves to the top bar on this page.
   - "What happened" list from scenario `timeline`; a click centres and zooms the view on that instant.
   - `RunDetailPage` keeps a small preview with an "Open chart" button; the old cramped chart panel goes.
   - Breadcrumb and context strip use `source` (Task 8) when present; until then "Run › Chart".
4. **API usage page** (backend units 2–3). New route `/usage` and nav item "API usage". Build artboard 6:
   - The period control maps to `period`; the "For" select lists captures (`captureId`).
   - The period sentence is formatted from `from`/`to` in local time, plus "Recording started …" from `recordingStartedAt`.
   - Tiles: requests (count), data (bytes → MB, 1 MB = 1,000,000 bytes, labelled), rate-limited (count), cache hits (transactions).
   - Stacked bars per bucket (`series`, aggregated client-side to hours for 24h/7d and kept at 5 minutes for 1h).
   - Table grouped by provider with purpose copy from `codes.ts`; "By task"; pacing line.
   - Every number shows its unit.
   - Link to it from the capture detail progress card with `captureId` preselected.
5. **Capture list** (backend unit 6, names unit 5). Rebuild `CapturesPage` per artboard 1:
   - Status filter chips from `statusCounts`, mapped to `status`; the sort select maps to `sort`; both live in URL search params.
   - Rows from `displayName`/`summary`.
   - The next-step button runs the same action as on the detail page (shared `useNextAction` hook) or navigates there; it is disabled with a reason while another operation runs.
   - Created column: relative + exact.
   - Move the create form to `/captures/new`.
6. **New capture** (backend units 4, 5, 7).
   - Route `/captures/new`. Prefill the wallet from `suggestedWallets[0]` with the suggestion card shown as "Applied" (Undo clears it); with an empty list, start with an empty wallet and no card.
   - Recent wallets as links.
   - The token step calls wallet activity when the wallet is valid and the user opens the step or presses Refresh. Show the filters (Buys only default on, Hide already captured, Over 1.9 SOL using `overSignalThreshold` with its basis in a tooltip), radio selection (sells disabled with reason), notes from `earlierActionInPage`/`capturedIn` (never call it "first buy"), and "Load 20 older" via `nextBefore`.
   - Show the request cost from `requestsUsed` and the time loaded.
   - "Paste a token mint" fallback.
   - Selecting a buy sets scope `wallet-token` and `mint`.
   - Name field: empty means generated (preview locally with the same format: `{label} · {first4}…{last4} · {d MMM HH:mm}`); typing calls `names/check`; 409 shows the suggestion with "Use suggestion".
   - Suggestions column from capture recommendations: Apply sets the setting, Undo restores the previous value, Adjust focuses the field, Dismiss hides it (localStorage key `rec-dismissed:<id>`, wrapped in try/catch), "Hide all".
   - Create sends `timeZone`; "Create and start wallet step" chains the existing wallet operation start.
7. **Capture detail** (backend units 5–6).
   - Rebuild the top of `CaptureDetailPage` per artboard 3: "now" panel from `summary` (status, plain reason from `codes.ts`, one primary button from `nextAction` with `maxRequests` and a time estimate from `pacing` when present), stepper from `steps`, progress card (existing progress fields plus `lastProgressAt` as relative time).
   - Token cards in plain words with the code below.
   - Glossary aside.
   - Rename via `PATCH`.
   - Move limits and recovery into "Advanced". Keep all existing controls (pause, cancel, recovery settings, attempts, downloads), only relocated.
8. **New run** (backend units 1 and 7).
   - Load `GET /api/runs/preset` and prefill from it (not `/api/config/defaults`).
   - Banner per artboard 4 with the counts from `fields`/`unsupportedRules`. "Reset to agreed" refills from the preset.
   - Inputs in percent for dip, take profit and stop loss (2 decimals max, validated client-side with the same regex the backend uses: `^[0-9]+(\.[0-9]{1,2})?$`).
   - The per-field status tag is "Changed by you · agreed X" when the value differs from the preset.
   - Send `…Percent` fields and `presetId`; show `INVALID_PERCENT` per field.
   - "In plain words" summary built from the current values.
   - Run recommendations: `ADD_LEGACY_COMPARISON` starts a second run with `/api/config/defaults` values and opens Compare.
   - If the preset fails to load, use `/api/config` defaults with every tag "Engine default · not the agreed strategy" and the warning state from artboard 7.
   - Dataset select shows the source capture link from dataset `source`.
9. **Source links everywhere** (backend unit 8): breadcrumbs and "Open capture" on run detail, the chart page and dataset detail, from `source`. Hide the capture link (and show "This run's capture was deleted") when `captureId` is null but the dataset came from a capture.

## Acceptance criteria

- `/captures` loads newest first by default; changing sort or filter updates the URL and the list; status chip counts equal `statusCounts`; every row shows status, step "n of 4", a progress bar only when `progress` is non-null, and exactly one next-step control.
- Two captures with the same legacy name show distinct `displayName`s; creating with an empty name shows the generated name after creation; a taken typed name shows the suggestion and can be applied in one click.
- `/captures/new` opens with Decu's address filled and the suggestion marked Applied when the backend lists Decu in `suggestedWallets`, and with an empty wallet otherwise; no wallet activity request is sent until the token step is opened or Refresh is pressed; picking a buy fills `mint` and the scope; a sell row cannot be selected; every network failure shows its artboard 7 state and keeps rows already loaded.
- Every run setting that was bps reads as percent everywhere (form, run detail, compare, cards, tooltips), with the server's strings; `20.255` cannot be submitted; a run started from the untouched preset has the same `configurationHash` as one started with the equivalent bps.
- The new-run page states the strategy source, marks changed fields, shows the observation window as "Missing in agreed strategy", and lists the 8 unsupported rules behind the warning.
- `/runs/:id/chart` shows the chart at ≥ 480 px tall; buttons, wheel, drag, slider and keys all zoom and pan; Reset returns to This trade; the From/To inputs and the URL stay in sync; reloading restores the view; breadcrumb links go to the capture, dataset and run.
- `/usage` shows the period's exact `from`/`to`, units on every number, providers grouped, purposes in plain words, and polls only while visible.
- Every state in artboard 7 exists with its copy: loading (list skeleton, activity progress, chart keeps the previous view), empty (no captures, no activity, no trades in range, no usage, no tokens yet, dataset not replayed), errors (RPC not configured, naming only `SOLANA_RPC_URL`, never a value; rate limited with retry countdown; name taken; invalid percent; preset unavailable; capture deleted).
- Accessibility: all controls are real buttons, links and inputs; icon buttons have `aria-label`; focus is visible; status is never shown by colour alone; text contrast is at least 4.5:1; 44 px targets; pages work at 375 px width with tables scrolling in their box.

## Tests

- Unit: `glossary`/`codes` completeness; `percent.ts`; generated-name preview; chart range math (presets, zoom ±, pan, clamp to `extent`, reset); usage aggregation 5 min → 1 h and MB formatting; `useNextAction` mapping for every `nextAction.kind`.
- Component (MSW): CapturesPage (sort/filter params, rows, counts, empty, error); NewCapturePage (Decu prefill, no request before opening the token step, selection, filters, 409 name, recommendations apply/undo/dismiss); CaptureDetailPage (now panel per status, rename); NewRunPage (preset prefill, changed tag, percent submit body, `INVALID_PERCENT`, preset failure fallback); RunChartPage (controls change the request's `from`/`to`, URL sync, event list jump); ApiUsagePage (periods, grouping, units).
- Update the existing tests that assert bps text (`RunDetailPage.test.tsx`, `NewRunPage.test.tsx`, `ComparePage.test.tsx`, `MarketActionCard.test.tsx`, `TradeChart.test.tsx`) and `CapturesPage.test.tsx` for the moved form.
- Mocks: extend `mocks/handlers.ts`, `captureHandlers.ts` and fixtures with every new endpoint and each error code above.

## Verify

From `frontend/`: `npm test`, `npm run lint` (`--max-warnings 0`), `npm run build`, all clean. Then check against the mocked dev server (`VITE_USE_MOCKS=true`, port 5174) at 1440 px and 375 px. Never click actions that start live provider calls against the real backend.

## Unresolved questions (from the backend plan)

1. Sells may be incomplete in wallet activity until confirmed with a sanitized sample; label the list "Pump.fun buys and sells (sells may be incomplete)" until the backend drops that flag.
2. No token names or symbols in this milestone: show the short mint and a pump.fun link.
3. The `DECIDED` cutoff uses the legacy defaults: show caveat `CUTOFF_USES_LEGACY_DEFAULTS` on that recommendation.
4. No agreed observation window for dip-entry v1: show it flagged `MISSING`; never present a value as agreed.
5. Gradle CLI captures are not counted in API usage: say so in "Limits and units".
6. When the `decu-dip` v2 engine ships, the preset will point to it and the unsupported-rules warning disappears; keep that banner driven by `unsupportedRules.length`.

## Contract confirmed against the backend on 2026-10-17

Checked against the shipped `console/api/dto/*`, `WalletController`, `RunController`, `RunSetupController`, `DatasetController`, `ChartController`, `UsageController`, `CaptureController`, `ConfigController` and their error handlers. Field names and shapes above match. Corrections made in this pass:

- Errors: 429 `PROVIDER_RATE_LIMITED` carries a top-level `retryAfterSeconds` only when the provider sent `Retry-After`; capture `NAME_TAKEN` carries `suggestion`.
- Wallet activity: an invalid `limit` or `before` returns 400 `INVALID_REQUEST` with `fields`; `limit` defaults to 20; the request cost is at most `limit` + 2.
- Run and dataset `source.datasetName` can be null. Dataset `source` is on the detail only.
- The scenario `timeline` holds only the five instants; `horizonEndSlot` stays on the scenario.
- `suggestedWallets` can be empty; `total` follows the status filter while `statusCounts` covers every capture; chart `extent` can be null; the usage report's `from`/`to`, `recordingStartedAt`, `pacing` and parameter errors are spelled out; each recommendation's setting, facts and conditions are listed.
