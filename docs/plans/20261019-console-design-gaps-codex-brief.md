# Codex brief: close the gaps between the console and the agreed design

Status: Ready for Codex, 2026-10-19. Frontend only. No backend change and no new HTTP contract: everything below uses fields the backend already serves (see [the handoff contract](20261016-console-capture-run-ux-codex-handoff.md#http-contract)).

You work only under `frontend/`. Keep Mantine 9, ECharts 6, React Query and the dark terminal theme; add no dependency. Amounts, prices and percents stay decimal strings (`lib/decimal.ts`); convert to numbers only for plotting.

**Why this brief exists.** Commit `866ae60` built every route and wired every endpoint from the handoff, and tests, lint and build pass. A page-by-page check against the agreed Claude Design canvas (https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4, artboards 1–7) on 2026-10-19 found that the pages work but several still read like the old console: raw codes where the artboard has plain sentences, missing columns, and controls that were added on top of old layouts instead of replacing them. It also found a few real bugs (section 0). The artboard copy is the intended copy; sample numbers on it are illustrative.

Work in the order below. Section 0 is bugs: do it first, each with a regression test.

## 0. Bugs

| # | File | Problem | Fix | Test |
|---|---|---|---|---|
| 0.1 | `features/captures/CapturesPage.tsx:175-189` | The next-step button is disabled whenever **any** capture has an open operation, including the row of the running capture itself. It is only a link to the detail page, so this blocks navigation for no reason. | Never disable it. It stays a link to `paths.capture(id)`. | Row link enabled while another session's `operation.state` is `RUNNING`. |
| 0.2 | `features/results/RunChartPage.tsx:48-54` | The From input has no `step="1"`, so seconds are dropped. A sub-minute range (most "This trade" views) can't be re-applied, and From and To show the same minute. | `step="1"` on both inputs. | Typing a From with seconds keeps them in the request's `from`. |
| 0.3 | `features/runs/NewRunPage.tsx:117-123` | The "Changed by you · agreed X" tag prints raw values: `agreed 40`, `agreed 0.100000000000`. | Format by field: percent fields `formatPercent`, SOL fields trimmed + ` SOL`, `minMarketCapUsd` as `$` + grouped trimmed value, slot fields `N slots`, quote mint `mintLabel`. One helper `formatSettingValue(field, value)` in `features/runs/configLabels.ts`. | `agreed 40%`, `agreed 0.1 SOL`, `agreed $10,000`. |
| 0.4 | `features/runs/runForm.ts` (`formValuesFrom`) | Decimal inputs show server scale: `10000.000000000000`, `0.001000000000`. | Trim with `trimFractionZeros` when filling the form. Comparisons already use `compareDecimals`, so equality is unaffected. | Preset fills `10000` and `0.001`; untouched submit still sends values equal to the preset. |
| 0.5 | `components/SourceLinks.tsx:17-26` | When `captureId` is null it shows "Capture link unavailable", including for uploaded datasets that never had a capture. | Omit the capture crumb when `captureId` is null. The backend can't yet tell "deleted" from "uploaded" (see section 7). | Uploaded-dataset run shows `Dataset › Run`, with no capture text. |
| 0.6 | `components/Recommendations.tsx:84-99` | `ADD_LEGACY_COMPARISON` is an action, but the card toggles it to "Undo" after starting two runs. | Section 4.6. | Clicking it starts the runs once and the button never reads "Undo". |

## 1. Shared pieces (do before the pages)

1. **Relative time.** Add `formatRelative(iso, now)` to `lib/format.ts` (`just now`, `4 min ago`, `9 h ago`, `6 days ago`, then the date) and a `<RelativeTime iso>` in `components/DisplayValues.tsx` that renders the relative text with the exact local time on a second line or in `title`. Unit-test the boundaries (59 s, 60 s, 59 min, 23 h, 7 days).
2. **Status presentation.** In `lib/codes.ts` replace `statusLabels` with the artboard copy and add a tone per status. Chips always carry the text, never colour alone:

   | Status | Label | Tone |
   |---|---|---|
   | `NOT_STARTED` | Ready to start | idle (violet) |
   | `RUNNING` | Running | running (blue) |
   | `WAITING_RETRY` | Waiting to retry | running |
   | `PAUSED` | Paused | stopped (amber) |
   | `STOPPED_LIMIT` | Stopped · request limit | stopped |
   | `STOPPED_DEADLINE` | Stopped · time limit | stopped |
   | `NEEDS_ATTENTION` | Needs attention | attention (red) |
   | `READY_TO_REPLAY` | Ready to replay | ready (green) |
   | `DONE` | Done | done (grey) |
   | `CANCELLED` | Cancelled | done |

   Add `<CaptureStatusChip status>` in `features/captures/CaptureShared.tsx` and use it on the list and the detail page.
3. **Stage names.** `stageLabels: Record<'WALLET'|'MARKETS'|'RATES'|'DATASET', string>` = Wallet, Markets, Rates, Dataset. Use it wherever the code now prints `stage.toLowerCase()`.
4. **Next-step hint.** `nextActionHint(summary, pacing?)` in `features/captures/useNextAction.ts`:
   - `maxRequests` set → `At most N requests`;
   - `REGISTER_DATASET` → `Offline; no provider requests`;
   - `START_RUN` → `Uses the agreed strategy by default`;
   - `RETRY_WALLET` → `Safe to retry; nothing is lost`;
   - `WAIT`, or status `RUNNING` → `Pause or cancel from the capture page`;
   - `VIEW_RESULTS` → `Opens this dataset's runs`.

   Unit-test every `NextActionKind`.
5. **Glossary copy.** Replace the texts in `lib/glossary.ts` for Market window, Slot, Request, Gap, Cutoff and Validated with the artboard 3 sentences (for example Slot: "Solana's clock tick, about 0.4 seconds. Used to order trades exactly."). Keep the other terms.
6. **Provider labels.** `providerLabels`: `SOLANA_RPC` → Solana RPC, `BINANCE` → Binance, `TELEGRAM` → Telegram. Never show the raw enum in body text; it may appear only through `<CodeText>`.
7. **Provider error state.** `<ProviderErrorState error onRetry? alternatives?>` in `components/StateViews.tsx`, keyed by `ApiError.code`, with the artboard 7 copy:
   - `RPC_NOT_CONFIGURED`: title "Solana RPC is not set up", body "Set the SOLANA_RPC_URL environment variable for the backend process and restart it. Its value is never shown here."
   - `PROVIDER_RATE_LIMITED`: title "The provider is busy (rate limited)", with a countdown only when `retryAfterSeconds` is present and a "Try now" button enabled once it reaches 0.
   - `PROVIDER_UNAVAILABLE`, `NOT_MAINNET`, `LOCAL_ACCESS_REQUIRED`: one sentence each.

   Every variant ends with "Technical code: X". Move the existing `RetryCountdown` here.

## 2. Captures list, artboard 1 (`features/captures/CapturesPage.tsx`)

1. **Columns:** Capture · Status · Progress · Next step · Created ↓.
   - **Capture:** `displayName` link. Below it, the wallet label (the suggested-wallet `label` when the address matches `config.suggestedWallets`/`recentWallets[].label`, else `ShortId`), the short mint (or `N tokens` from `summary.cases.total` when there is no mint) and the scope label (Wallet + token / Pump.fun buys / Wallet references). Load `useCaptureConfig()` once on the page for the labels.
   - **Legacy note:** when `displayName !== name`, add "Two captures share this name; numbered by creation time".
   - **Status:** `<CaptureStatusChip>`, then one plain "why" line from `codeLabel(reasonCode)`, or from `summary.cases` when there is no reason, e.g. "2 of 3 tokens ready; 1 still blocked". No `CodeText` in the list.
   - **Progress:** a compact 4-segment bar, one segment per `steps[]`, filled by state, with `aria-label` "Wallet done, Markets stopped, …". Below it, "Step n of 4 · Markets", or "All steps complete" when every step is `DONE`. Then the progress bar and "1,323 of 1,748 transactions · 75%", only when `progress` is non-null. Drop the per-step text that `CaptureStepper` prints today in the list.
   - **Next step:** the `actionLabels` button, using the filled variant for a step to take and default for `WAIT`/`VIEW_RESULTS`/`NONE`, plus the `nextActionHint` line underneath. It stays a link (bug 0.1).
   - **Created:** `<RelativeTime>`.
   - Delete stays as the last column.
2. **Filter chips:** "All · {total of statusCounts}" first, then only the statuses with a count above 0 (plus the selected one, even at 0). Each chip is "{label} · {count}" with `aria-pressed`. The chips and the sort select share one row that wraps on phones.
3. **"What do the four steps mean?"** shows the four artboard sentences in a 4-column grid (1 column under 600 px).
4. **Empty state:** "No captures yet", with the artboard sentence. When `suggestedWallets` is non-empty, the primary action is "New capture with {label}" (to `/captures/new`); the secondary is "Attach a saved capture folder" (to `/captures/new?mode=attach`, which preselects the Attach tab).
5. **Paging text:** "Showing a–b of N · {sort label}".

## 3. New capture, artboard 2 (`features/captures/NewCapturePage.tsx`)

1. **Breadcrumb:** Captures › New capture. Keep the "All captures" button.
2. **Step 1, whose trades:**
   - A suggestion card replaces the current text row. It shows "Suggested: {label}", the artboard sentence, an "Applied" chip while the wallet equals the suggestion, and an Undo button that clears the field. When the wallet differs, the button reads "Use {label}".
   - The wallet input shows a label badge in its `leftSection` when the address has a known label.
   - Recent wallets render as "Other wallets you used: a · b" links, excluding the address already filled.
3. **Default scope:** when the wallet is prefilled from `suggestedWallets[0]`, start with scope `wallet-token` and show the `SCOPE_WALLET_TOKEN` card as Applied, so its Undo restores the `walletDefaults` scope, limit and pages. With an empty `suggestedWallets`, keep today's defaults.
4. **Step 2, activity table:**
   - Columns, in order: Select (radio, visually hidden header "Select") · When (`<RelativeTime>` with the exact `HH:mm:ss` below) · Action (Buy/Sell chip) · Token (short mint in mono, a pump.fun link, and "Name not loaded" below) · **SOL** (right-aligned mono, `solLamports` / 10⁹ as an exact decimal string trimmed to at most 3 decimals for display, full value in `title`) · Notes.
   - The selected row gets a highlight.
5. **Notes copy**, first match wins:
   - SELL → "Sells cannot start a capture";
   - `capturedIn` non-empty → "Captured in “{displayName}”" (each a link);
   - `earlierActionInPage` → "An earlier action on this token is in the list";
   - BUY and not `overSignalThreshold` → "At or under 1.9 SOL: the agreed strategy skips it";
   - otherwise "No earlier action in this list (proof comes in the wallet step)".

   Delete "No first-buy claim." Never say "first buy" as a fact.
6. **Filters** become toggle chips with `aria-pressed` (Buys only on by default, Hide already captured, Over 1.9 SOL). The last one keeps a tooltip: "Curve payment, excluding fees".
7. **Header and footer lines:**
   - The header line next to Refresh reads "Loaded {relative} · {requestsUsed summed} requests", plus "(from cache)" when the newest page has `cachedAt`.
   - The footer reads "{n} most recent actions · Load 20 older (up to 22 more requests)" and "Token not listed? Paste a token mint". The latter focuses `#capture-mint`.
8. **States:**
   - **Loading:** "Loading {label}'s latest actions…" with "at most 22 requests" and a Cancel button that calls `queryClient.cancelQueries({ queryKey: ['wallet-activity', wallet] })`.
   - **Empty** (no actions and `nextBefore` null): "No Pump.fun buys or sells found", with "Paste a token mint". When `nextBefore` is set, use "Look further back", which equals Load older.
   - **Errors:** use `<ProviderErrorState>`, with "Paste a token mint instead" as the alternative. Rows already loaded stay visible above the error.
9. **Step 3, name:**
   - Show "✓ Name is free" when `names/check` says available, and "Another capture already has this name." plus "Use suggestion" when taken.
   - Keep "Made from wallet, token and time. Edit it if you like; names must be unique." under the field.
10. **Footer card "What happens next":**
    - The text is "Wallet step: at most {walletTokenBudgetDefaults.requestBudget or budgetDefaults.requestBudget} requests. The market step's cost depends on how busy the token was; you'll see it before you start."
    - Buttons: Cancel (link to the list), "Create only" (default variant) and **"Create and start wallet step" as the primary button**. That last one stays disabled with its reason when `rpcConfigured` is false.
11. **Suggestion cards** (`components/Recommendations.tsx`, shared with new run): see 4.6 for the component changes. Capture card copy:
    - `SCOPE_WALLET_TOKEN`: "Capture one token at a time". Why: "Each buy adds its own market window, and a busy token alone can need thousands of requests. One token gets you a replayable case sooner." Sets: "Scope: wallet + token".
    - `CUTOFF_DECIDED`: "Stop each window once the result is known". Why: "Trades after the exit don't change the outcome, so the market step usually needs far fewer requests than a full 30-minute window." The caveat is built from `facts` ("Today the cutoff is decided with the old default rules ({dipPercent}% dip, +{takeProfitPercent}% / −{stopLossPercent}%), not the agreed strategy.").
    - `ENABLE_RECOVERY`: "Resume automatically after a request limit". Why: "A single run is capped at {budget} requests. With recovery on, the capture continues by itself up to the total you set, instead of stopping and waiting for you." Sets: "Recovery: {facts.requests} requests, {facts.attempts} attempts".
    - `DECU_PUMPFUN_SCOPE`: keep today's copy.

    Under the list: "Suggestions only change settings when you apply them. Dismissed ones stay hidden on this device."

## 4. New run, artboard 4 (`features/runs/NewRunPage.tsx`, `runForm.ts`, `configLabels.ts`)

1. **Banner.** Title "Agreed strategy: {source.strategy} v{source.version}" with an "Applied" chip, or "Changed · N settings" once something differs.
   - Subtitle: "From the strategy spec, last updated {source.updated as d MMM yyyy}. You can change any setting below; changed ones are marked."
   - Actions: "Reset to agreed" and "Read the spec". Read the spec shows `source.document` in a popover; the repo file is not served.
   - A warning box that is **always visible**, not inside `<details>`:
     - "This simulator ({engine}) applies {count of fields with status AGREED} of the agreed rules exactly."
     - "{unsupportedRules.length} rules need the new decu-dip v2 engine, which is planned but not built yet: {codeLabel of each, joined with commas}." Show this sentence only when `unsupportedRules.length > 0`.
     - "{MISSING count} setting has no agreed value for this simulator: the observation window. It is flagged below rather than guessed."
2. **Field cards.** Each setting is a card with label, status tag, input with prefix and unit, help line, and the note.
   - Labels and units: Dip below Decu's first buy `%` · Take profit `+ … %` · Stop loss `− … %` · Position size `SOL` · Minimum market cap `$ … USD` · Cost per transaction `SOL` · Fill delay `slot` · Observation window `slots` · Max price age `slots` · Quote currency (mint with `mintLabel`).
   - Percent help adds the bps equivalent, e.g. "20% = 2,000 bps".
   - Render `noteCode` through `codeLabel` under the field, in amber.
   - The `MISSING` field card is red-tinted with its note in red.
   - A changed field card is amber-tinted and shows a "Reset to {formatted agreed}" link that resets that field only.
3. **"In plain words" aside** (sticky on ≥ lg, stacked on phones):
   - "After Decu's first buy of a token, wait for the price to drop **{dip}** below his price. Then buy **{notional} SOL**, and sell at **+{tp}** or **−{sl}** from our fill."
   - "Settings you changed: **N** ({field labels})."
   - **Start run** moves into this card. Remove the bottom-right Start button.
4. **Dataset select** labels read "{name} — {pairs.length} token(s), {transactionCount} trades". Under the select: "From capture {link}." from `source` (hidden when `captureId` is null), plus "Slots a – b" when the dataset detail has them.
5. **Preset failure state** (artboard 7). Today the page silently falls back.
   - Show a warning card "Agreed strategy could not be loaded" with the artboard body, "Technical code: STRATEGY_PRESET_UNAVAILABLE" (a frontend-only code; add it to `codes.ts`), **Retry** (`preset.refetch()`) and "Continue with defaults".
   - Show the form only after "Continue with defaults", with every tag "Engine default · not the agreed strategy" as today.
6. **Recommendations component** (`components/Recommendations.tsx`). Give each item a kind:
   - setting items (Apply ⇄ Undo, Adjust, Dismiss) show a state chip, "Applied" or "Suggested", and a "Sets {plain setting}" line;
   - action items have one button with their own label and no Undo:
     - `ADD_LEGACY_COMPARISON` → "Add comparison run". It runs the existing two-run flow once, shows a loading state, and is disabled while pending.
     - `SINGLE_TOKEN_DATASET` → "Capture another token", a link to `/captures/new`.

   Run card copy:
   - `ADD_LEGACY_COMPARISON`: "Also run the old defaults for comparison". Why: "One click starts a second run with {facts.dipPercent}% / +{facts.takeProfitPercent}% / −{facts.stopLossPercent}%, then opens both side by side."
   - `SINGLE_TOKEN_DATASET`: "This dataset has one token". Why: "A single trade says little about the strategy. Results are judged on all signals together, including skips. Capture more of Decu's buys first."
   - `USE_AGREED_STRATEGY`: hide it while the form already equals the preset.

## 5. Results chart, artboard 5 (`features/results/RunChartPage.tsx`)

1. **Breadcrumb:** "Capture: {captureDisplayName}" › "Dataset: {datasetName}" › "Run {shortId}" › Chart (with bug 0.5 applied).
2. **Context strip** (replaces the page header and the Token select). Four facts:
   - Token: `{mintLabel(base)} / SOL`.
   - Result: the outcome label from `features/runs/scenarioOutcome.ts` plus net P&L in SOL, e.g. "Stop loss · −0.0385 SOL".
   - Settings: "Agreed strategy" when the run `config` equals the cached preset `config` (use `['run-preset']`, same key as new run); otherwise "Agreed strategy, N changes ({labels})"; or "Custom settings" when the preset is unavailable.
   - Simulator: "{strategyName} v{strategyVersion} · run {local d MMM HH:mm}".

   Then a token stepper "‹ Token i of n ›" (icon buttons with `aria-label`, disabled at the ends), "← Back to run" and, when `source.captureId` is set, "Open capture".
3. **Toolbar, one wrapping row:**
   - range presets as one group with `aria-pressed` on the active one (compare the current range to each preset; "Custom" when none matches);
   - compact From/To inputs (bug 0.2) applied on Enter or blur; delete the "Apply range" button and keep the inline error;
   - zoom and pan icon buttons with `aria-label`;
   - Reset view.

   Market cap/Price, Layers and Full screen stay where `TradeChart` already puts them.
4. **Extent fetch.** `full` requests the whole window only to read `extent`. Pass `maxBuckets: 1, markerLimit: 1` (both accepted, minimum 1) so it costs one bucket.
5. **"What happened · click to jump"** becomes an ordered list. Each row is a button with time (`HH:mm:ss`), what, and a detail line built from the scenario:
   - Decu bought: reference buy SOL and mcap if present, and the dip level;
   - Dip reached;
   - Paper buy filled: notional, fees %, impact %;
   - Take profit / Stop loss crossed;
   - Paper sell filled: net result SOL.

   Skip null instants. Clicking keeps today's behaviour (centre ±15 s).
6. **"How to read this"**, artboard copy:
   - "B: Decu's own buy (observed on chain). Gold diamonds: our simulated paper trades, never real."
   - "Market cap = price × total supply × SOL/USDT at that minute. USDT stands in for USD."
   - "Candles group trades into {bucketSeconds}-second buckets at this zoom; zooming in shows finer buckets."

   Read `bucketSeconds` from the latest chart response; `TradeChart` can expose it through an `onData` callback.
7. **Empty range** (artboard 7). When the visible range has no trades, `TradeChart` shows "No trades between {from} and {to}" and "Nobody traded this token in the selected range." with **Reset view** and **Zoom out** buttons. Add an optional `emptyActions` prop to `TradeChart`; other callers pass nothing and keep today's text.

## 6. API usage, artboard 6 (`features/usage/ApiUsagePage.tsx`)

1. **Header and controls:**
   - Period labels: Last hour / Last 24 hours / Last 7 days / All recorded.
   - The "For" select lists every capture: page through `useCaptures` or request `size=100`, not only the first 20.
2. **Period sentence:** "Showing **{from} → {to}** (your local time), in {5-minute | hourly} buckets. Updated {relative of dataUpdatedAt}. Recording started {recordingStartedAt}; nothing before that is counted." When `recordingStartedAt` is null: "Nothing has been recorded yet."
3. **Tiles:** label, big value and a unit line:
   - Requests sent: "HTTP calls, all APIs, {period}";
   - Data received: "response bodies, {period}";
   - Rate-limited replies: "HTTP 429 answers, retried automatically";
   - Served from cache: "transactions, no request sent".
4. **Chart:**
   - Title "Requests sent per {5 minutes | hour}".
   - Legend uses `providerLabels`.
   - Colours: RPC blue, Binance amber, Telegram grey.
   - X labels `HH:mm` (add the date for 7 days and All).
5. **Table grouped by provider.** Use a group header row (`<th colspan scope="colgroup">`) with a note:
   - Solana RPC: "your configured provider; address hidden because it can contain a key";
   - Binance: "public market data, no key".

   Columns: API · call (operation in mono) · Used for · Requests · Share (a thin bar plus `sharePercent`%) · Failed · Rate-limited · Data · Last call (`HH:mm:ss`).

   "Used for" uses sentences; update `purposeLabels` to the artboard text:
   - `READ_TRANSACTION`: "Download one full transaction: wallet buys, market trades and first-buy checks."
   - `LIST_SIGNATURES`: "List a wallet's or token's transactions, up to 1,000 per page."
   - `MAINNET_CHECK`: "Confirm the provider is on Solana mainnet before a capture step."
   - `MINT_PROGRAM_CHECK`: "Check which token program owns a token, for decoding."
   - `CONNECTION_CHECK`: "Connection checks from the Solana page."
   - `LIVE_RATE`: "Live SOL/USDT price in the top bar and the USDT view of runs."
   - `MINUTE_RATES`: "SOL/USDT price for every minute of a dataset (rates step)."
   - `UNKNOWN`: "Another call to this provider."
6. **By task:** sorted by requests descending, each with a share bar and "{n} req".
7. **Limits and units:**
   - "Solana RPC pace set in this backend: {1000 / minRequestIntervalMillis} requests per second, {maxConcurrentRequests} at a time.";
   - "Requests are not billing credits. Some providers bill calls differently; check your provider's dashboard for credits used.";
   - the cache-hit sentence;
   - "Telegram: not used yet." (when no `TELEGRAM` rows);
   - keep the CLI and 15-second sentences.
8. **Empty state:** "No calls in the {period}" with the recording sentence, plus "Show last 24 hours" when the period is Last hour.

## 7. Capture detail, artboard 3 (`features/captures/CaptureDetailPage.tsx`, `CaptureOverview.tsx`, `CaptureCases.tsx`)

This is the largest gap. The new overview was added above the old tabbed workspace, so the page shows both, and the stage status appears twice (`KpiStrip` "Capture stages" and the new stepper). Restructure without removing any control.

1. **Header:**
   - Breadcrumb Captures › {displayName}.
   - The title has an inline rename icon button (`aria-label="Rename capture"`) that swaps the title for the existing rename form.
   - Meta line: wallet label + short address, Token short + pump.fun link, scope label, "Created {exact}".
   - Actions: "Re-check saved files" (the existing Inspect evidence) and Delete.
   - Remove the two raw mono address lines (`CaptureDetailPage.tsx:515-524`).
2. **"Now" panel** (top, full width, tinted by status tone):
   - chip;
   - a **headline and one explanation paragraph** from a new pure `nowCopy(summary, operation)` in `features/captures/captureNow.ts`, using the artboard pattern for at least these cases:
     - `STOPPED_LIMIT` on Markets: headline "Markets stopped after using this run's {budget} requests"; body "Nothing is lost: {processed} of {selected} transactions are saved. Resume to download the remaining {selected−processed}. After that come rates, then the dataset."
     - `STOPPED_DEADLINE`, `RUNNING`, `PAUSED`, `WAITING_RETRY`, `NEEDS_ATTENTION` (per stage), `READY_TO_REPLAY`, `DONE`, `NOT_STARTED`, `CANCELLED`: one headline and body each, written in the same voice.
   - the primary next-action button with "Up to N requests · about X s at R requests/s" (R = 1000 / `minRequestIntervalMillis`);
   - for `STOPPED_LIMIT` with recovery off, a secondary "Turn on automatic resume" that opens Advanced with the recovery checkbox checked and focused.

   Table-test `nowCopy` for every status.
3. **Stepper** (replaces the `KpiStrip`): four items, each with a badge (✓ when `DONE`, else its number), the stage name, the state in words ("Done", "Stopped · request limit", "Waiting for markets", …) and a detail line from data already on the page:
   - Wallet: "{n} buys found · {short mint} selected" from the wallet summary;
   - Markets: "{processed} of {selected} transactions";
   - Rates: "Next after markets", or the count of minutes;
   - Dataset: "Offline check and packaging; no requests".
4. **Progress card** (left column): "{Stage} progress", "Last progress {relative} ({HH:mm:ss})", bar and counts, then a definition list:
   - Requests, this run: used / budget;
   - Requests, whole capture;
   - Downloaded: MB;
   - Run time.

   Take these from the fields `OperationProgress` already reads, and keep its Pause/Cancel controls on this card. Then the link "See this capture's API usage". Remove the separate "Latest operation" section once its content lives here.
5. **Token cards** (`CaptureCases.tsx`): status chip, one plain sentence, a facts line (buy time, window slots, short signature), "Technical code: X" small, and two actions:
   - Ready → "Start a run" + "Open dataset";
   - Downloading or blocked → the resume action + "Chart preview".

   Header: "Tokens in this capture". Sub: "Each token becomes ready on its own, so you can replay one while another is still downloading." Empty state (artboard 7): "No tokens to capture yet. Run the wallet step first. It finds the buys this capture will follow." with "Start wallet step".
6. **"Terms on this page" aside** (right column on ≥ lg, below on phones): a `<dl>` of Market window, Slot, Request, Gap, Cutoff and Validated from `glossary.ts`. Remove the three inline `<Term>`s from the overview.
7. **Advanced.** Move the whole stage `Tabs` block (Wallet / Market / Rates / Dataset / Calibration / History, `CaptureDetailPage.tsx:575-940`) inside one closed-by-default disclosure, "Advanced: limits, attempts and history". The disclosure shows a summary line: "Per run: {requests} requests · {MB} MB · {minutes} min · Automatic resume: {on/off} · Market cutoff: {full | decided (old defaults)}". Every existing control stays and keeps working. Only its place changes.

## 8. Not in this brief (backend or decisions)

- **"This run's capture was deleted"** (artboard 7) needs the backend to remember that a dataset came from a capture. Today the link is found by scanning capture files, so a deleted capture looks exactly like an uploaded dataset. It would need a new column on the dataset (a production schema change) and its own plan. Until then, bug 0.5 hides the crumb instead of showing a wrong message.
- **Token names and symbols:** not in this milestone (plan Open Question 2). Keep "Name not loaded".

## Tests

- **Unit:** `formatRelative`; `formatSettingValue`; `nextActionHint` for every kind; `nowCopy` for every status; lamports → SOL display (`1900000000` → `1.9`, `2400000001` → `2.4` display and `2.400000001` title); usage grouping by provider and task sort; chart preset detection (`aria-pressed`).
- **Component (MSW):**
  - CapturesPage: columns and copy, chips hide zero counts, link enabled during another operation (0.1), legacy note, empty state with the Decu button.
  - NewCapturePage: SOL column and notes copy, default wallet-token scope with Decu and Undo, cancel loading, empty activity, rate-limited countdown + Try now with rows kept, primary "Create and start wallet step".
  - CaptureDetailPage: now panel per status, stepper replaces the KPI strip, Advanced holds the tabs and every old control is still reachable, Terms aside.
  - NewRunPage: always-visible warning text and counts, note codes rendered, changed tag formatting (0.3), trimmed inputs (0.4), per-field reset, preset failure with Retry and Continue, comparison card is not undoable (0.6).
  - RunChartPage: context strip, token stepper, From keeps seconds (0.2), extent request has `maxBuckets=1`, event rows with details, empty-range actions.
  - ApiUsagePage: grouped rows, provider labels, unit lines, empty state button.
  - SourceLinks: no capture crumb when `captureId` is null (0.5).
- Update existing tests whose copy changes (`CapturesPage.test.tsx`, `NewCapturePage.test.tsx`, `CaptureOverview.test.tsx`, `NewRunPage.test.tsx`, `RunChartPage.test.tsx`, `ApiUsagePage.test.tsx`). Add mock fixtures where needed:
  - a capture with a legacy duplicate name;
  - an activity page with a sell, an over-threshold buy, a `capturedIn` buy and an `earlierActionInPage` buy;
  - a usage report with Binance rows and a run with a full five-instant timeline over at least 5 minutes of observations, since today's mock window is 2.4 s and too short to judge the chart.

## Verify

From `frontend/`: `npm test`, `npm run lint` (`--max-warnings 0`) and `npm run build`, all clean.

Then use the mocked dev server (`VITE_USE_MOCKS=true`, port 5174) at 1440 px and 375 px:

- every page above;
- no horizontal page scroll at 375 px (tables scroll inside their box);
- all states from artboard 7 reachable through mock handlers.

Then **against the real backend** (port 5173, read-only): open `/captures`, a capture detail, `/runs/new`, a run's `/runs/:id/chart` and `/usage`, and check they render with real data. **Do not** press anything that sends provider requests: Start/Resume/Retry steps, Create and start, Refresh activity, or opening the "Which token?" step with a wallet filled in. Those spend the user's paid RPC requests; ask the user first.

Report: files changed, test count, and screenshots of each page at both widths.
