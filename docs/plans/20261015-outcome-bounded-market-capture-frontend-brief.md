# Codex brief: decided market capture in the console

You work only under `frontend/`. The backend part is delivered; develop against the MSW mocks (`frontend/src/mocks/`) and the contract below. Keep the existing Mantine components, terminal theme and test style (Vitest + React Testing Library + MSW). No new dependency.

Part 4 of the approved [outcome-bounded market capture plan](20261015-outcome-bounded-market-capture.md) ([decision](../adr/20261015-outcome-bounded-market-capture.md)). User guide: [decided and full mode](../REAL_DATA_CAPTURE.md#decided-and-full-mode).

## What changes for the user

A market capture runs in one of two cutoff modes. `FULL` fetches every trade of each 4,500-slot window, as today. `DECIDED` stops a window once its dip-entry outcome under the default strategy can no longer change (for example a take profit two minutes in), which makes most captures much cheaper. A decided window can later be extended to the whole window ("Capture the rest of the window"). A run whose configuration needs trades after a decided window's cutoff is refused with 409 `MARKET_COVERAGE_ENDED`.

## HTTP contract (as implemented)

All new fields are optional and additive; every existing field keeps its name and type. Unknown mode values are refused with the existing 400 body `{code:"INVALID_REQUEST", message, fields:{cutoffMode:"must be FULL or DECIDED"}}`. Mode values are exactly the strings `"FULL"` and `"DECIDED"`.

**Market attempt settings** (`MarketSettings`, used in `CaptureAttempt.market` and `CaptureAttemptRequest.market`): gains `cutoffMode?: 'FULL' | 'DECIDED' | null`.
- In responses, a new attempt always carries the resolved mode. An attempt saved before this change has no `cutoffMode` field: treat absent or null as `FULL`.
- `GET /api/captures/config` → `marketDefaults` does **not** carry a mode (it depends on the scope). The UI default is: `DECIDED` when the session's scope is `wallet-token` **and** `config.ratesConfigured` is true, otherwise `FULL`. The backend applies the same rule when the field is omitted.

**Create a session** `POST /api/captures` (`CaptureCreate`): gains `cutoffMode?: 'FULL' | 'DECIDED'` for the session's first market attempt. Omit it to take the scope default above.

**New attempt** `POST /api/captures/{id}/attempts` with `stage:"MARKET"`: `market.cutoffMode` optional, same default rule using the session's `wallet.scope`.

**Start an operation** `POST /api/captures/{id}/operations` (`CaptureOperationRequest`): gains `cutoffMode?: 'FULL' | 'DECIDED'`, allowed only with `step:"MARKET"` (400 `fields.cutoffMode` otherwise). It overrides the attempt's mode for this operation only. `cutoffMode:"FULL"` on a capture with a decided window is the **extension**: it is accepted although the market stage is `READY` with `canStart`/`canResume` false.

**Operation response** (`CaptureOperation`): gains `cutoffMode?: 'FULL' | 'DECIDED'`, present on MARKET operations (the mode it runs in), absent otherwise.

**Errors** (existing body `{code, message, fields}`):
- 409 `RATES_NOT_CONFIGURED` — `DECIDED` (requested or the attempt's) while reference rates are disabled. Returned by create session, new market attempt and start MARKET operation. Message: `Deciding market windows early needs reference rate capture; enable it or capture in FULL mode`.
- 409 `MARKET_COVERAGE_ENDED` — `POST /api/runs` on a dataset whose coverage ends before this configuration's outcome is decided. Message names the mint, the decision slot and the coverage end, e.g. `Market coverage ends before the outcome is decided mint=<base58> decidedAt=5500 throughSlot=1006 (MARKET_COVERAGE_ENDED)`. Nothing is stored. (Already returned since Part 1; the Runs page only needs to explain it.)
- The verdict's `capture.reason` can also be `MARKET_COVERAGE_ENDED` (classification `INCOMPLETE_CAPTURE`, stage `MARKET`).

**Inspection** (`CaptureInspection.marketSummary`, a `Record<string,string>`): per market window at index `i` (the same index as `marketWindows[i]`):
- `window.{i}.observedThrough` — last observed slot (decimal string); present once the market capture is decoded (acquisition complete).
- `window.{i}.cutoffThroughSlot` and `window.{i}.cutoffOutcome` — present only for a decided window, also while acquisition is still running. The outcome is a strategy outcome code such as `TAKE_PROFIT`, `STOP_LOSS`, `MIGRATED`, `MIGRATED_BEFORE_ENTRY`, `EXCEEDS_CURVE`, `ZERO_QUANTITY`, `NO_FILL_AFTER_TRIGGER`, `UNSUPPORTED_SUPPLY`.
`marketWindows[]` itself is unchanged.

**Independent cases** `GET /api/captures/{id}/cases` (`CaptureCase`): gains `observedThrough: number | null` — the window's last observed slot, `null` before the case has market progress. `observedThrough < toSlot` means the case was decided early. The case response carries no outcome; when you need it, read `cutoffOutcome` from the session inspection's window with the same `mint`, `fromSlot` and `toSlot`, and omit it if not found.

## Files and exact changes

1. `frontend/src/api/captures.ts`
   - `export type CutoffMode = 'FULL' | 'DECIDED';`
   - `MarketSettings.cutoffMode?: CutoffMode | null`; `CaptureCreate.cutoffMode?: CutoffMode`; `CaptureOperationRequest.cutoffMode?: CutoffMode`; `CaptureOperation.cutoffMode?: CutoffMode | null`; `CaptureCase.observedThrough?: number | null`.
   - A small pure helper, e.g. `defaultCutoffMode(scope: string, ratesConfigured: boolean): CutoffMode` and `attemptCutoffMode(settings: MarketSettings | null | undefined): CutoffMode` (absent → `FULL`), and `decidedWindow(inspection, index)` returning `{ throughSlot: number, outcome: string, observedThrough: number | null } | null` from the summary keys. Keep these in one place (e.g. `features/captures/cutoff.ts`) and reuse them.
2. `frontend/src/api/types.ts`: add `'MARKET_COVERAGE_ENDED' | 'RATES_NOT_CONFIGURED'` to `ApiErrorCode`.
3. `frontend/src/features/captures/CapturesPage.tsx` (new capture form): for mode `new` and scope `wallet-token`, show a `SegmentedControl` (or `Select`) "Market cutoff" with `Decided early` (`DECIDED`) and `Full window` (`FULL`), defaulting to `defaultCutoffMode(scope, config.ratesConfigured)`; when rates are not configured, disable `DECIDED` with the hint "Needs reference rates". Send `cutoffMode` only for the wallet-token scope (other scopes and attachments omit it so the backend default applies). One line of help text: "Decided early stops each market window once the default strategy’s outcome is known; you can capture the rest of the window later."
4. `frontend/src/features/captures/CaptureDetailPage.tsx`
   - **New market attempt** section: the same cutoff selector, defaulting to `defaultCutoffMode(session.wallet?.scope ?? '', config.ratesConfigured)`; send it as `market.cutoffMode`.
   - **Market panel**: show the current attempt's mode ("Cutoff: decided early" / "Cutoff: full window") next to the scan pages/capacity summary line.
   - **Market windows table**: for a window with `cutoffThroughSlot`, render under the coverage cell (or an extra "Observed" column) `Decided early at slot {cutoffThroughSlot} ({reasonText(cutoffOutcome)})`; when `observedThrough` is present also show `Observed through slot {observedThrough} of {toSlot}`. Undecided windows show `observedThrough` only (equals `toSlot`) or `—`.
   - **Capture the rest of the window** button, shown when at least one window has `cutoffThroughSlot`. Enabled when not `locked` and `config.rpcConfigured`, regardless of `stage.canStart`/`canResume`. It calls the existing `start('MARKET')` path with `cutoffMode: 'FULL'` added (same budget, scan pages and recovery fields as Start). Help text: "Fetches the remaining trades of decided windows from the saved listing; no listing requests are repeated. Afterwards create a new rates attempt, since the rate minutes change."
   - Optional: in `AttemptHistory`, show each market attempt's mode.
5. `frontend/src/features/captures/CaptureCases.tsx` (`CaseCard`): add a row "Observed through" with `count(item.observedThrough)` when non-null; when `observedThrough < toSlot` render `Decided early at slot {observedThrough}` plus ` ({reasonText(outcome)})` when the joined session window gives an outcome (pass the inspection or a lookup from the parent `CaptureCases`).
6. `frontend/src/features/captures/captureReasons.ts`: add
   - `RATES_NOT_CONFIGURED: 'Deciding windows early needs the reference-rate source. Enable reference rates in the backend, or capture the full window.'`
   - `MARKET_COVERAGE_ENDED: 'The market capture stopped once the default strategy’s outcome was decided, so it cannot answer this configuration. Capture the rest of the window, export again, and run.'`
   `CaptureShared.tsx`: add `RATES_NOT_CONFIGURED` to the set whose guidance `CommandError` shows (rename the set if it no longer only holds qualification errors).
7. `frontend/src/features/runs/NewRunPage.tsx`: when `serverError` is an `ApiError` with code `MARKET_COVERAGE_ENDED`, keep the server message and add the guidance above (use `reasonText`, do not duplicate the string), with a title such as "This dataset was captured only until its outcome was decided". Other errors are unchanged.
8. Mocks
   - `frontend/src/mocks/captureFixtures.ts`: `captureSession.marketAttempt.market.cutoffMode = 'FULL'`; add a wallet-token session fixture with `market.cutoffMode: 'DECIDED'` and an inspection whose `marketSummary` holds `window.0.observedThrough: '1040'`, `window.0.cutoffThroughSlot: '1040'`, `window.0.cutoffOutcome: 'TAKE_PROFIT'` (window 1000–5500), market stage `READY`; a case fixture with `observedThrough: 1040`; a legacy attempt without `cutoffMode`.
   - `frontend/src/mocks/captureHandlers.ts`: create/new attempt resolve an omitted `cutoffMode` with the default rule and reply 409 `RATES_NOT_CONFIGURED` for `DECIDED` when `captureConfig.ratesConfigured` is false; operations reply 400 with `fields.cutoffMode` for a non-MARKET step with a mode, echo `cutoffMode` on MARKET operations, and a MARKET operation with `cutoffMode:'FULL'` on the decided fixture clears its `cutoff*` keys and sets `observedThrough` to `toSlot`.
   - `frontend/src/mocks/handlers.ts` (`POST /api/runs`): one dataset id (a new fixture dataset) answering 409 `MARKET_COVERAGE_ENDED` with the message format above.

## Tests (Vitest + RTL, existing files)

- `features/captures/CapturesPage.test.tsx`
  - The wallet-token create form preselects "Decided early" and the request body carries `cutoffMode:'DECIDED'`; other scopes send no `cutoffMode`.
  - With `ratesConfigured:false`, the form preselects "Full window", `DECIDED` is disabled, and a forced `DECIDED` 409 shows the `RATES_NOT_CONFIGURED` guidance.
  - A new market attempt sends the chosen `market.cutoffMode`.
  - The decided fixture shows "Decided early at slot 1,040 (Take profit)" and "Observed through slot 1,040 of 5,500" in the market tab; "Capture the rest of the window" is enabled although Start is disabled, and sends `{step:'MARKET', cutoffMode:'FULL', attemptId, budget, scanPages}`; after the mock completes, the line disappears.
  - A legacy attempt without `cutoffMode` shows the full-window label.
  - The case card shows "Observed through" and "Decided early at slot 1,040".
- `features/runs/NewRunPage.test.tsx`: starting a run on the coverage-ended dataset shows the server message and the extend guidance, and does not navigate.
- `features/captures/captureReasons.test.ts`: the two new codes have plain-language text.
- Unit tests for the helpers in step 1 (default rule, absent → FULL, summary parsing with and without cutoff keys).

## Verify

From `frontend/`:

```bash
npm test
```

```bash
npm run lint
```

```bash
npm run build
```

All clean (`lint` runs with `--max-warnings 0`).
