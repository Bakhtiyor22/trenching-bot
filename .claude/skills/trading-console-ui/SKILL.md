---
name: trading-console-ui
description: >
  UI/UX conventions for the trenching-bot console under `frontend/` (React 19 + Vite + TypeScript,
  Mantine 9, ECharts 6, TanStack Query, MSW, Vitest): a dark trading-terminal UI for a Solana
  paper-trading research system. Covers exact decimal amounts, signed P&L, percent and bps, slots
  and UTC times, KPI strips, dense tables, loading/empty/error/provider states, live polling cues,
  ECharts price/Mcap/candle charts and markers, plain-language copy, keyboard and accessibility,
  and how to verify a page in the browser. Use it whenever writing, changing, reviewing or planning
  anything in `frontend/src` — a page, component, chart, table, form, mock or test — even when the
  request only says "make this look better", "add a column", "show the P&L" or "fix the chart".
  Do not use for Kotlin backend code (use `kotlin-spring-guidelines`).
---

# trenching-bot console UI

The console is a research tool, not a brokerage app. People open it to answer "did this strategy
make or lose money, and can I trust the number?". So the UI favours **exact, traceable figures**
over pretty rounding, **dense but calm** layouts over decoration, and **plain words** over codes.
Every rule below serves one of those.

`CLAUDE.md` at the repo root is the base rule set and always wins. Frontend work runs in its own
session, apart from backend work. Configuration comes from the process environment (Vite's env-file
loading is disabled); never read `.env` files or print configuration values.

## Before you build anything, reuse what exists

Most UI problems here already have a component or helper. Look for one before you write markup;
a second, slightly different version is how inconsistency starts.

| Need | Use | Where |
|---|---|---|
| Exact amount, digits untouched | `<Decimal>` / `formatDecimal` | `components/DisplayValues.tsx`, `lib/decimal.ts` |
| Signed P&L with colour | `<Pnl>` (signed; `trim` for a headline), `formatSignedDecimal`, `pnlTone`, `pnlColor` | same |
| Tiny price (`0.0₇8643`) | `compactDecimal` | `lib/decimal.ts` |
| Sums, sorting, sign of amounts | `addDecimals`, `compareDecimals`, `decimalSign` | `lib/decimal.ts` |
| Number for a chart coordinate only | `toChartNumber` | `lib/decimal.ts` |
| Percent / bps | `formatPercent`, `levelPercent`, `signedBps` | `lib/percent.ts`, `lib/format.ts` |
| Time | `<Timestamp>` (local, UTC in tooltip), `<RelativeTime>`; automation clock: `<ZoneTime>`, `<ZoneNote>` | `components/DisplayValues.tsx`, `features/automation/AutomationShared.tsx` |
| Slot, slot range, counts, bytes, durations | `formatSlot`, `slotRange`, `formatCount`, … | `lib/format.ts` |
| Address, mint, id | `<ShortId>`, `<Mint>`, `<CopyValue>`, `<PairLabel>` | `components/DisplayValues.tsx` |
| Page title, tabs, panel, KPIs | `PageHeader` (breadcrumbs, status, meta, tabs), `SubNav`, `Section`, `KpiStrip` + `Kpi` (`size`, `span`, `text`), `Stat`, `KeyValueTable` | `components/Panels.tsx` |
| A status as glyph + word | `StatusChip` (tones: success, failure, running, attention, pending, neutral, ready) | `components/StatusChip.tsx` |
| Any bar of counts (cap, share, buy states) | `Meter`, `MeterLegend` | `components/Meter.tsx` |
| A polled value that changed | `<Flash watch={value}>`, `.console-live-dot` | `components/Panels.tsx`, `styles.css` |
| Loading / error / empty / provider down | `LoadingState`, `ErrorState`, `EmptyState`, `ProviderErrorState`, `RetryCountdown` | `components/StateViews.tsx` |
| A query rendered data first (failed refresh keeps data) | `QueryView`, `RefreshNotice` | `components/QueryView.tsx` |
| Jargon and backend codes | `<Term>` + `lib/glossary.ts`, `codeLabel`, `humanizeCode`, `<CodeText>` | `components/PlainLanguage.tsx`, `lib/codes.ts` |
| Colours | Mantine `teal`/`red`/`blue`/`orange` (= gain, loss, info, rejected in both schemes), `--console-*` CSS variables, `terminal` / `terminalLight`; charts `useChartTokens()` | `theme.ts`, `styles.css`, `charts/chartTokens.ts` |
| Charts | `<Chart>` wrapper with the `terminal` ECharts theme | `components/Chart.tsx` |
| Routes and URL params | `paths`, constants in `paths.ts` | `paths.ts` |

If the right helper is missing, add it next to its siblings (with a test) rather than inline in a page.

## Numbers: exact first

The backend sends money, prices and quantities as **decimal strings** because they come from
`BigDecimal` and must match the ledger to the last digit. A float rounds `0.1 + 0.2`, and a user
comparing the console with a stored record would see a mismatch and stop trusting both.

- Display the string through `formatDecimal` / `<Decimal>`. Never `Number(x)`, `parseFloat`,
  `toFixed` or `Intl.NumberFormat` on an amount you show, compare, sum or send back.
- Decide sign, zero and order from the digits: `decimalSign`, `compareDecimals`, `addDecimals`.
  `Number(pnl) === 0` is a bug even when it happens to work.
- `toChartNumber` is the one float conversion, for plotting. Tooltips and labels on a chart still
  show the original string.
- A shortened figure (fewer places, `compactDecimal`) is fine for scanning, as long as the exact
  value is one hover away (tooltip) — see `SolValue` in `features/automation/AutomationShared.tsx`.
- Missing is `—`, not `0`. Zero is a result; missing is the absence of one.
- Numbers use tabular figures (set globally on `body`) and the mono font, so columns line up.
  Right-align numeric table columns.

Details, edge cases and percent/bps rules: [references/numbers-and-time.md](references/numbers-and-time.md).

## P&L and direction

- Gain is `teal` / `terminal.gain`, loss is `red` / `terminal.loss`, zero is `dimmed`. Use
  `pnlColor` / `pnlTone`, never a hand-picked green.
- Colour is never the only signal: about 1 in 12 men cannot tell this red from this green. Show the
  sign — `+` for gains, `−` (U+2212) for losses. `<Pnl>` and `formatSignedDecimal` do this (the backend sends
  `"12.5"`, not `"+12.5"`); never prefix `+` inline.
- Label what the number is relative to (realized vs worst case, SOL vs USDT, per scenario vs total).
  An unlabelled "+3.2%" invites the wrong conclusion.
- Rejected scenarios are orange (`terminal.rejected`), deliberately apart from loss red.

## Time and slots

- Show local time; keep the UTC ISO string in the tooltip (`<Timestamp>`). Times the backend
  estimates within a slot must say so — never present an estimate as an exact instant.
- Slots are exact integers: `formatSlot`, grouped digits, mono font.
- When execution order or timing is unproven, say it in the UI (as the chart resolution note does)
  rather than silently picking an order.

## Layout and density

- One `PageHeader` per page, then `KpiStrip` for the 3–6 figures that answer the page's question,
  then `Section`s. A `Section` hint says what a chart or table shows, in a sentence, visibly — not
  behind an info icon.
- Dense is fine; cramped is not. Use Mantine spacing tokens, `verticalSpacing="xs"` tables, and let
  long exact decimals wrap inside their cell (`overflow-wrap: anywhere`) instead of overflowing.
- Every page works at **1440 px and 375 px**. Wide tables scroll horizontally inside their card,
  with the identifying column sticky when it has one (see `.automation-sticky`).
- Amber is the single accent (primary actions, focus, the live axis pointer). Don't introduce a
  second accent; status colours carry meaning, not decoration.

## States: every view has five

Loading, error, empty, provider-unavailable and loaded. Design the first four before the fifth —
they are what a user sees while a backfill runs or an RPC provider is rate limited.

- Use the `StateViews` components; an empty state says *why* it is empty and offers the next step.
- `placeholderData: keepPreviousData` keeps the last data on screen while a new page or filter loads;
  don't blank a table on every refetch.
- Errors show the backend's message via `errorMessage`, plus a retry when retrying can help.
  Backend codes go through `codeLabel` / `humanizeCode`; show the raw code only as `<CodeText>`.

## Server state over time

Most console bugs that reach a user are not wrong styling. They are a page that behaves correctly
on first load and wrongly on the second fetch. Before writing or reviewing a page, walk each query
through: first load, background refresh that succeeds, background refresh that **fails**, a
parameter change (filter, page, URL id), and the watched work finishing.

- **A failed background refresh must not blank the page.** In TanStack Query v5, a failed refetch
  sets `isError` while `data` still holds the last good result. `if (q.isError) return <ErrorState>`
  before checking `data` replaces a working table with an error card on one network blip (closing
  any open modal with it). Many existing pages do exactly this, so don't copy it: render `data` when
  it exists and show a small inline notice ("Couldn't refresh — showing data from 14:02", with
  Retry); use the full `ErrorState` only when there is no data (`isError && !data`). Fix the
  pattern in a page you touch.
- **Related views refresh together.** A detail panel fed by its own query must poll or be
  invalidated with its parent list, or the row says "Replayed" while the panel says "Not replayed".
- **Selections stay put.** A default chosen from live data ("first replayed buy") must be pinned
  once chosen (write it to the URL), or it jumps when the data changes under the user.
- **URL input is validated for meaning, not just shape.** `2026-02-31` matches `\d{4}-\d{2}-\d{2}`;
  check real dates and known ids before calling the API, and when a linked id is missing say so
  instead of silently showing something else.
- **Unknown is not none.** "No buys this day" is only true for a day that was scanned; a day not
  covered yet is "Not scanned yet". The same goes for future dates and still-running work.

## Live data

- Poll only while the tab is visible (the `visiblePoll` pattern in `api/automation.ts`) and stop
  when the thing being watched has finished.
- Mark a polled region with `.console-live-dot`, and wrap a value that moves in `<Flash>` so the
  change is noticed without shifting layout. Both respect `prefers-reduced-motion`.
- Never reorder rows under the cursor while polling; append or update in place.

## Charts

Charts go through `<Chart>` (it registers only the ECharts parts the console uses and applies the
dark `terminal` theme). Always pass an `ariaLabel` that states what the chart shows. Never join a
line across missing data; show gaps, and explain them. For colour choice, marks, legends, stat tiles
and validation, also load the `dataviz` skill. Project chart conventions:
[references/charts.md](references/charts.md).

## Words

- Write for a trader who is not reading the source: "Bought 0.5 SOL of XYZ at slot 312,004,118",
  not `FILL_CREATED`. Wrap domain jargon (P0, dip, horizon, worst case) in `<Term>` and add it to
  `lib/glossary.ts`.
- Buttons are verbs that say what happens ("Start backfill", "Delete dataset"). Destructive actions
  go through `ConfirmDelete` and name the thing being deleted.
- Say what is unknown or unsupported instead of hiding it ("Trader data was not recorded in schema
  v3 recordings").

## Keyboard and accessibility

- Every interactive thing is a real `button`/`a` (or `.console-row-button` for a clickable row),
  reachable by Tab, with a visible focus ring. Icon-only buttons get an `aria-label`.
- Popovers and full-page layers close on Escape and return focus. Document chart shortcuts in the UI.
- Contrast: text at least 4.5:1 against its panel in both schemes; status colours validated (the
  automation colours in `styles.css` were checked with the dataviz palette script — do the same).

## Verify before calling it done

1. Test first for logic (formatting, models, queries); `@testing-library` tests for pages, with
   MSW fixtures in `src/mocks/`. Add a `?mockState=` preview for states that are hard to reach.
2. Run, from `frontend/`: `npm test`, `npm run lint`, `npm run build`.
3. Start the dev server with mocks (`VITE_USE_MOCKS=true`, `npm run dev`) through the preview tool,
   open the page at 1440 and 375 px, walk the states, check the console for errors and keyboard-test
   the new controls.
4. Save screenshots to `frontend/qa/<yyyyMMdd>-<topic>/` named `<page>-<width>.jpg`.

To review a change, use [references/review-checklist.md](references/review-checklist.md), starting
with its behaviour-over-time section: convention slips are easy to spot, data-flow bugs are not.
