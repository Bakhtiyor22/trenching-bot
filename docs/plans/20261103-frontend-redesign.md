# Console redesign: one shell, quiet ledger, analytics

> Re-evaluate the whole console under `frontend/` against the `trading-console-ui` and `dataviz` skills, then
> redesign it consistently: one app shell, one page anatomy, one set of tokens for dark and light, and calmer,
> more honest analytics and charts. Frontend only; the backend contract stays as it is.

Status: **Approved 2026-11-03** with direction A and every recommended decision (1 A, 2 trim headline zeros, 3 Automation card, 4 blue paper fills, 5 behaviour fixes included). Implemented 2026-11-03; verification in `frontend/qa/20261103-redesign/verification.md`.

Design: the existing canvas, new rows "Redesign 2026-11" (artboards 13–18):
https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4. Values on the artboards are samples (taken from the MSW mocks
where possible), not real results.

| Artboard | What it shows |
|---|---|
| 13 | Tokens (dark and light), type scale, signed values, status chips, buttons, meter, refresh notice, KPI strip |
| 14 | Dashboard, direction **A: quiet ledger** (recommended) |
| 15 | Dashboard, direction **B: chart-first workbench** (alternative) |
| 16 | Automation overview in the new system |
| 17 | Run results in the shell with the collapsed rail and one chart toolbar |
| 18 | Phone width: dashboard and automation |

## What I reviewed

- Skills: `trading-console-ui` (SKILL.md, the three references and its five evals), `dataviz` (palette, marks,
  anti-patterns, validator). No other UI/UX skill was added: `.claude/skills/` holds only the backend skills and
  this one (commit `e342b03`).
- Product: README (console, captures, automation, trading days, backfill), the plans
  `20261103-automation-console-ux.md`, `20261103-automation-days-backfill-ui.md`,
  `archive/20261016-console-capture-run-ux.md` and its handoff, commits `a04f77a`, `ff3560b`.
- Code: every page, shared component, chart module and mock in `frontend/src` (two read-only audits plus my own
  pass on the theme, styles, routes and layout).
- Browser: the mock server (`frontend-mocks`), Dashboard, Runs, Run detail, Run chart, Captures, Automation overview
  and Day at 1440 px, Dashboard, Runs and Automation at 375 px, Dashboard in light.

## What is wrong today

The console is already a dark terminal with good bones (exact decimals, `<Chart>`, `StateViews`, the automation
pages). The problems are consistency, data honesty in a few places, and behaviour on refresh.

### Behaviour (users hit these)

1. **A failed background refresh blanks the page.** `if (q.isError) return <ErrorState>` comes before the data check
   on almost every page. The worst cases are polled: `AutomationShared.tsx:404` (the status query wraps all six
   automation pages, so one blip blanks the section), `CapturesPage.tsx:186`, `CaptureDetailPage.tsx:1181` (loses
   rename and form state), `CaptureCases.tsx:368`, `BackfillDetailPage.tsx:363` (also closes the cancel dialog),
   `ApiUsagePage.tsx:350`, `SolanaPage.tsx:225`, and `NewRunPage.tsx:666-678` (a defaults refetch error unmounts the
   form and **loses the user's edits**). Only the Dashboard does it right.
2. **Gains show by colour only.** `<Pnl>` (`DisplayValues.tsx:66`) shows `0.028260434000` in teal with no `+`. It
   is used on Runs, Run detail, scenario cards, Compare, the Dashboard and the command palette.
3. **Selections and filters are not in the URL**: Runs filter/outcome/sort, Datasets page, dataset pair, run detail
   cycle and records tab (`?pair` is read once, never written), the automation default scored day and selected buy,
   backfills page, capture detail advanced tab.
4. **URL input checked for shape only**: `2026-02-31` passes on Days and Day; a non-numeric `page` becomes NaN on
   Sweep runs; backfill pre-fill values are not validated.
5. **Unknown shown as none**: Days says "No buys" for a day never scanned.
6. **Dialogs**: Close session and Cancel backfill can be dismissed mid-request and keep their error after reopening.

### Design and consistency

1. **Four page-header patterns**: `PageHeader`; breadcrumb + `Title` (Capture detail); a `Section` as the header
   (Run chart); `PageHeader` with tabs in `actions` and a second header card under it (Day, Backfill detail, which
   end up with two `h2`s).
2. **Two shells**: the full run chart drops the sidebar for tiny text links in the header.
3. **KPI figures have no shared style**: sizes `KPI_VALUE_SIZE`, `lg`, `xl`, plain text; the Dashboard headline
   P&L wraps mid-number at 1440 px ("−0.0134878240 / 00", "SO / L"). Run chart, Compare and the capture "Now" card
   build their own look-alike KPI blocks.
4. **Five meter bars** (`RatioBar`, `ShareBar`, the cap meter, `SignalsMeter`, `Bar`), three greens for gains,
   raw hex in a dozen files, and a parallel palette in `VariationTrend`.
5. **Amber is not the single accent**: it also means "in progress", "closing", "capturing", "paused", Compare run C
   and differing cells; blue, violet, grape and yellow are used for selection, info and edited states. Many
   amber-filled buttons compete on one screen (one "Prepare dataset" per row on Captures).
6. **Uppercase, letter-spaced mono labels** everywhere make the screens loud; the data should be the loud part.
7. **Light mode is half done**: the ECharts theme is dark only (charts fall back to ECharts defaults or keep their
   own light palette), and status words such as "UP" in light green on white fail contrast.
8. **Phone width**: every KPI strip becomes one cell per row (the automation strip is a full screen tall), fixed
   widths (`w=320/280`, `datetime-local w=205`, `LANE_COLUMNS`) squeeze, tabs wrap to two lines, no table has a
   sticky identifying column except the status grid.
9. **Words and codes**: raw `UP`, `OUT_OF_SERVICE`, `FILLED`, `SELL`, `failureCode`, `classification`,
   `unavailableReason`, camelCase keys turned into labels; "night" still appears beside "day".
10. **Charts**: the cumulative P&L line is always green, even below zero; the API usage x-axis drops empty buckets,
    so quiet hours vanish without a gap; `VariationTrend` hides days with no rows while the status grid shows them;
    paper fills are amber diamonds in code but "blue diamonds" in `references/charts.md`; tooltips in the trend and
    grid show shortened SOL without the exact value.

## Design direction: quiet ledger

The same dark trading terminal, made calmer, so the figures carry the meaning:

- **Data is loud, chrome is quiet.** Greys for structure, colour only for meaning (gain, loss, running, attention,
  neutral). Amber only for the one primary action per view, focus rings, the selected item and the chart axis
  pointer.
- **Sentence-case sans labels, mono figures.** Labels are 12 px medium in the muted ink, not uppercase mono. Every
  figure stays mono with tabular digits (the skill's rule); one hero figure per page.
- **One page anatomy everywhere**: breadcrumb (when nested) → title row (title, status chip, short meta) →
  one-line description → actions on the right → sub-navigation as underline tabs → KPI strip → sections.
- **One shell**: grouped sidebar that collapses to an icon rail; the full chart page keeps the shell (rail) instead
  of switching layout.
- **Sign and unit always readable without colour**: `+` / `−`, unit next to every figure, `—` for missing.

**Direction B** (artboard 15) is the alternative for the Dashboard only: an always-collapsed rail, a context bar
with a range control, a hero band with the P&L figure beside a full-width chart, then stat tiles and a live feed.
It reads more like a charting workbench and is better for a big screen, but the range control would need data the
dashboard does not have per range today, the tiles repeat the KPI strip, and it is a bigger departure from the
other pages. I recommend **A**, and the rest of the plan assumes it.

## Tokens and theme

Defined once in `theme.ts` and as CSS variables in `styles.css`, for both schemes; Mantine colours, the
`terminal` object and both ECharts themes read from them.

| Token | Dark | Light | Use |
|---|---|---|---|
| ground | `#0A0D10` | `#F5F6F8` | page |
| panel | `#10151A` | `#FFFFFF` | cards, sections |
| raised | `#151B21` | `#F0F2F5` | hover, selected row, segmented track |
| line / line-strong | `#1F2830` / `#2A343D` | `#E2E6EB` / `#CDD3DA` | hairlines, control borders |
| text / text-2 / muted | `#E7ECEF` / `#C3CDD5` / `#93A1AD` | `#11161B` / `#2F3943` / `#5B6773` | ink |
| accent (+ ink) | `#F2B43C` on `#1A1300` | `#B9841F` fill; `#8A6212` for amber text and filled buttons (5.5:1) | primary action, focus, selection |
| gain | `#3DDC97` | `#0C7A54` | positive P&L, success |
| loss | `#FF6B6B` | `#C93030` | negative P&L, failure |
| info | `#5AA9FF` | `#1F6FD1` | running / capturing, paper fills, meters |
| warn | `#F7A04A` | `#A85200` | rejected scenarios, stopped, needs attention |
| neutral | `#6B7885` | `#8A96A3` | skipped, not a first buy, still open |

Dark keeps today's values, so the dark console does not shift in hue. Validator (`dataviz/scripts/validate_palette.js`):
gain, loss, info and warn pass contrast (≥ 3:1 dark; ≥ 4.5:1 as text on the light panel) and CVD separation in both
schemes (worst gain↔loss ΔE 8.1 dark, 9.0 light; always with the sign and a word, as the rules require). The light
steps were darkened during implementation so they also pass as text; the automation buy-state colours keep their
earlier validation, the grey being a deliberate neutral. Status chips always carry a glyph and a word.

Compare's run identity (A, B, C) moves off blue / violet / amber to the dataviz reference slots blue, orange and
aqua (validated all-pairs for three series in both schemes), always beside the letter. Violet failed the check
against blue (ΔE 1.9 under protan), and amber is the accent.

Charts get a registered `terminal-dark` and `terminal-light` ECharts theme; `<Chart>` picks one from the computed
colour scheme. Gridlines are solid hairlines, the zero line is the one emphasised gridline, lines are 2 px,
markers have a 2 px surface ring.

## Shared components

All in `components/`, restyled once so every page follows:

| Component | Change |
|---|---|
| `AppLayout` | Grouped nav: Dashboard · Research (Runs, New run, Compare) · Data (Captures, Datasets) · Automation · System (API usage, Solana). Collapse to a 68 px icon rail (remembered in `localStorage`, wrapped in try/catch); the run chart page opens collapsed. Active item: raised background and amber icon, not an amber fill. Mobile header: burger, brand, search icon, health dot. Distinct icons for Runs and API usage. |
| `PageHeader` | Adds `breadcrumbs`, `status` (a chip), `meta` and a `tabs` slot (underline tabs, horizontal scroll on phones). Replaces the hand-made headers on Capture detail, New capture, Run chart, Day and Backfill detail. Fixes the double spacing from a `Stack` around it. |
| `SubNav` (new) | Underline tabs as real links with `aria-current`; used by Automation and the run records tabs. |
| `Section` | Same API; title 15 px, the hint visible below it, consistent padding. CycleList, RecentRunsList and the Verdict paper use it instead of look-alikes. |
| `KpiStrip` / `Kpi` | Owns the value style (mono, 22 px; `hero` 32 px), keeps unit and value on one line, two columns below `sm` (the hero spans both). A shortened figure shows the exact string in a tooltip and on tap. |
| `StatusChip` (new) | Glyph + word + tone (`success`, `failure`, `none`, `pending`, `running`, `attention`, `neutral`). Replaces the per-page badge colour maps in `lib/codes.ts`, automation and captures. |
| `Meter` (new) | One bar for single values (requests vs cap, signals vs 10, budget) and segmented shares (filled / rejected, buy states, provider share) with the 2 px gap. Replaces the five copies. |
| `Pnl`, `SignedDecimal` | Signed exact output from a new `formatSignedDecimal` in `lib/decimal.ts` (tests first). `Pnl` becomes signed everywhere. |
| `QueryState` / `RefreshNotice` (new) | Renders data first: full `ErrorState` only when `isError && !data`; otherwise the data plus "Couldn't refresh. Showing data from 14:02:10. Retry". Every page's query handling goes through it. |
| `StateViews` | Restyled; `EmptyState` used where Alerts and plain text stand in today; provider errors use `ProviderErrorState` (Solana checks too). |
| Tables | One convention: `verticalSpacing="xs"`, numeric columns right aligned, the identifying column sticky (`.console-sticky`, generalising `.automation-sticky`), clickable rows through `.console-row-button` or the row's link, faded while a new page loads. |
| Controls | Every view or period toggle is a `SegmentedControl`; buttons: one filled primary per view, `default` secondary, `subtle` tertiary, red outline for destructive. Row actions on lists become secondary. |
| `CommandPalette` | Adds API usage and New run; signed P&L. |

## Pages and areas

The routes, components that own them and their workflows stay; this is what changes on each.

- **Dashboard (`/`)**, artboard 14: health becomes one slim line (words, not `UP`) with "Check Solana";
  KPI strip with the realized P&L as the hero, by quote mint as today; cumulative P&L chart coloured by sign with a
  labelled zero line, an end label and a table view, beside Recent runs and the one primary "Start a run"; "How
  scenarios ended" as one-scale bars with rejection reasons in words; an **Automation** summary card (decision 3);
  Activity; Runtime sparklines (grey line, amber latest point, value as text).
- **Runs (`/runs`)**: filter, outcome and sort written to the URL; dataset select full width on phones; sticky Run
  column; signed P&L; theme tokens instead of hard-coded rgba and `#161A14`; compare bar restyled.
- **Run detail (`/runs/:id`)**, artboard 17: `PageHeader` with breadcrumb, copy, engine and time in `meta`; actions
  SOL/USDT, Compare, New run, Download; KPI strip; Cycles as a `Section` list with outcome chips; the chart panel
  with one toolbar (window, metric, zoom, layers, draw) and a legend; selected cycle and records tab in the URL;
  codes through `codeLabel`; Run lanes stack on phones; BUY/SELL and FILLED/REJECTED as words, with SELL no longer
  in grape and BUY no longer in gain teal.
- **Run chart (`/runs/:id/chart`)**: inside the shell with the rail; real `PageHeader` (token stepper and Back to run
  as actions); the fact grid becomes a `KpiStrip`; `<Timestamp>` instead of `toLocaleString`; event list buttons get
  the row-button focus ring; paper fills drawn as blue diamonds (decision 4).
- **New run (`/runs/new`)**: query errors no longer unmount the form; Start run stays reachable on phones (sticky
  summary bar at the bottom below `md`); "edited" uses the neutral raised tone and a word instead of yellow; codes as
  `<CodeText>`.
- **Compare (`/compare`)**: per-card errors instead of blanking the comparison; run identity A/B/C with the
  validated colours; Verdict as a KPI strip; "differs" marked with a token and a word, not amber.
- **Captures (`/captures`)**: status pills become a `SegmentedControl` with counts; row next-step buttons become
  secondary (the page keeps one primary, New capture); `StatusChip` for status; live dot on the polled table;
  "Created" header no longer looks sortable.
- **New capture**: one `PageHeader` with breadcrumb; the selected activity row uses the raised tone, not blue; the
  suggested-wallet note uses the neutral info style, not violet; `<Timestamp>`; market links from `lib/marketLinks`.
- **Capture detail**: `PageHeader` (breadcrumb, rename, status chip, actions); the "Now" card as the page's hero
  KPI; `KeyValueTable` instead of the hand-made `<dl>`; `RetryCountdown`; `formatBytes`; `Accordion` instead of
  native `<details>`; advanced tab in the URL; confirmation for "Cancel remaining batch"; raw codes labelled.
- **Datasets** list and detail: page in the URL, pair in the URL, a small KPI strip on the detail page, keyboard
  reachable rows, `EmptyState` with a next step.
- **Automation**, artboard 16: header with status chip, "Times in UTC+5" and Run discovery now; underline tabs
  under the header (no more tabs in `actions`); the status strip in the shared KPI style (two columns on phones);
  scoreboard, status grid and trend restyled with the shared tokens; the grid and trend agree on days with no rows;
  exact values in tooltips; default scored day and selected buy pinned in the URL; the selected buy polls with its
  list; Day and Backfill detail use the shared header (prev/next day as actions), no second header card; dialogs
  locked while sending and reset on open; Days says "Not scanned yet" for unscanned days; dates validated as real
  dates; backfills page in the URL; the long submit label shortened with the request count in the hint below it.
- **API usage**: full bucket range on the x-axis (empty buckets drawn as gaps, explained), chart option moved to a
  tested `.ts` module, `<Timestamp>`, right-aligned numbers, sticky provider column, `formatBytes`.
- **Solana**: `ProviderErrorState` with the countdown for check errors; outcome and cluster names in words; mainnet
  marked with a word and the neutral tone instead of orange.
- **Not found** and the automation **redirects**: rendered inside the shared frame with a retry.

## Behaviour that stays the same

- Routes, the `/console` basename, every URL parameter in use today (`?quote`, `?pair`, `?datasetId`, `?page`,
  `?status`, `?sort`, `?runs`, `?mode`, `?item`, `?from`, `?to`, `?action`, `?fill`, automation filters) and the
  old `/automation/nights/*` and `/automation/items/*` redirects.
- Every API call, request body, request id and idempotency rule; no new endpoint. Polling intervals (5 s
  dashboard, 30 s usage and automation status, 5 s while a run-now is queued, 15 s active backfill), visible-tab
  polling and when it stops.
- Data semantics: decimal strings end to end, `toChartNumber` only for coordinates, sums with `addDecimals`, P&L
  never summed across quote mints, `—` for missing, automation times in the automation zone, trading days as dates
  on that clock, the scoreboard sort, the 7-day window, PENDING as a state.
- Workflows: capture steps and controls, run start (including its fallbacks and recommendations), compare up to 3,
  chart presets, zoom, pan, drawings, keyboard shortcuts (N, ?, mod+K, /, F, C, J, K, U, chart keys), delete
  confirmations, backfill dry run by default, close session and cancel backfill confirmations.
- Dark stays the default scheme; the theme toggle stays.

## Behaviour changes, and why

| Change | Why |
|---|---|
| A failed refresh keeps the data and shows an inline notice | Skill rule; today one network blip blanks pages, closes dialogs and loses form input |
| Gains show `+` everywhere | Skill rule; colour alone fails for colour-blind readers |
| More view state in the URL (list above) | A link should reproduce what the sender saw; defaults picked from live data must not jump |
| URL dates and pages validated for meaning | `2026-02-31` and `page=abc` currently reach the API or render NaN |
| "Not scanned yet" distinct from "No buys" | Unknown is not none |
| Grouped nav, Automation as one entry, chart page inside the shell | One shell; the sub-views live in the page tabs, which also work on phones |
| Dashboard Automation card (decision 3) | The dashboard answers "is the system working" for the part that runs unattended |
| Paper fills blue (decision 4) | Matches `references/charts.md`; amber is Decu's pins and the accent |
| Row actions on Captures become secondary buttons | One primary per view |
| API usage shows empty buckets | A quiet hour should look like a gap, not vanish |
| Raw codes become words (code kept in `<CodeText>`) | Plain language rule |

## Implementation steps

Each step keeps tests, lint and build green; test first for logic.

1. **Foundations**: tokens in `theme.ts` and `styles.css` (dark and light), Mantine colour mapping, light and dark
   ECharts themes in `Chart.tsx`, `formatSignedDecimal` with tests, `Pnl` signed.
2. **Shared components**: `PageHeader` v2, `SubNav`, `Section`, `KpiStrip`/`Kpi`, `StatusChip`, `Meter`,
   `QueryState` + `RefreshNotice` (tests for the failed-refetch case), `StateViews` restyle, table classes.
3. **Shell**: `AppLayout` (groups, rail, mobile header), `CommandPalette`.
4. **Dashboard**, including the Automation card if chosen.
5. **Runs**: list, detail, run chart, new run, compare.
6. **Captures**: list, new, detail and its parts.
7. **Datasets**: list and detail.
8. **Automation**: frame, overview, days, day, backfills, backfill detail, sweep runs, redirects.
9. **API usage, Solana, Not found.**
10. **Review and QA**: the skill's review checklist over the whole change, then the browser pass below.

Steps 5–9 touch separate feature folders once 1–3 are in, so they can run in parallel. I would still do them in
this session, one after another, because they share the new components and a reviewer should see one coherent
change. No new dependencies.

## How I will evaluate it

1. **Tests**: new unit tests for `formatSignedDecimal`, `QueryState`, `Meter`, `StatusChip` tones, URL validation
   helpers and the moved chart options; page tests for a failed background refetch keeping the data (Dashboard,
   Captures list, Capture detail, Automation frame, Backfill detail, New run keeping edits), URL state round trips,
   dialogs locked mid-request and reset on reopen. Existing tests updated only where the visible text changes on
   purpose (for example `+` signs).
2. `npm test`, `npm run lint` (zero warnings), `npm run build` in `frontend/`.
3. **Browser, mocks** (`frontend-mocks`): every route at 1440 and 375 px in dark, and at 1440 px in light, including
   the `?mockState=` states (automation off, discovery only, empty). Check: no horizontal page scroll at 375 px
   (`scrollWidth`), label collisions and overflow, keyboard focus on new controls, console errors, contrast of status
   text in light. Screenshots in `frontend/qa/20261103-redesign/<page>-<width>-<scheme>.jpg` with a
   `verification.md`.
4. **Skill review checklist** (`references/review-checklist.md`) run over the diff, findings fixed before I report.
5. Not covered: a real backend (automation is off locally); I will say so.

## Decisions for you

1. **Direction**: A, quiet ledger (recommended), or B, chart-first workbench, for the Dashboard.
2. **Headline figures**: in KPI strips, drop trailing zeros (`−0.013487824` with the exact `−0.013487824000` on
   hover and tap, recommended), or always show the full stored scale. Tables keep the full scale either way.
3. **Dashboard Automation card** (adds the `/api/automation/status` and `/days` reads to the dashboard, which show
   "Automation is off" when the endpoints return 404): yes (recommended) or no.
4. **Paper fill markers**: blue diamonds as `references/charts.md` says (recommended), or keep amber and change the
   reference.
5. **Scope of the behaviour fixes** (URL state, URL validation, dialog locking, "Not scanned yet"): in this
   redesign (recommended, since the skill says to fix these in pages it touches), or only the visual redesign now
   and the fixes in a follow-up.
