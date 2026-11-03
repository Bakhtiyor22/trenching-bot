# Console UI review checklist

Use this when reviewing a frontend change. Report each finding with the file, line, what a user
would see go wrong, and the fix. Skip items the change does not touch.

## Behaviour over time (check first — these are the bugs users hit)

Walk each query in the change through first load, a successful refresh, a **failed** refresh, a
parameter change and the watched work finishing.

- [ ] A failed background refetch keeps the data on screen with an inline notice; the full
      `ErrorState` only when there is no data (`isError && !data`).
- [ ] Detail panels and secondary queries refresh with the list they belong to.
- [ ] Defaults picked from live data are pinned (URL) so the selection doesn't jump.
- [ ] URL params validated for meaning (real dates, known ids); a missing id is reported, not
      silently replaced.
- [ ] "None", "unknown / not scanned yet", "future" and "still running" are distinct states.
- [ ] Mutations: pending state, error cleared on reopen, dialog not dismissable mid-request.

## Correctness of figures

- [ ] No `Number`, `parseFloat`, `toFixed`, `Math.*` or `Intl.NumberFormat` on an amount that is
      shown, compared, summed, sorted or sent. `toChartNumber` only for coordinates.
- [ ] Sign/zero decided with `decimalSign` / `pnlTone`, not `Number(x) === 0`.
- [ ] Every shortened figure has its exact value one hover away.
- [ ] Units shown; percents say what they are relative to; SOL→USDT shows the rate provenance.
- [ ] Missing shows `—`, not `0` or an empty cell.

## Readability

- [ ] P&L sign readable without colour (`+` / `−`); colours from `pnlColor` / `terminal`.
- [ ] Numeric columns right-aligned, mono, tabular.
- [ ] Times are `<Timestamp>` with UTC available; estimates labelled; automation pages use `<ZoneTime>` + `<ZoneNote>`.
- [ ] Addresses and ids use `<ShortId>` / `<Mint>` with copy.
- [ ] No raw backend code as the main text; jargon wrapped in `<Term>` with a glossary entry.

## States and live data

- [ ] Loading, error, empty and provider-unavailable states exist and say what to do next.
- [ ] Refetches keep previous data (`keepPreviousData`); nothing blanks or reorders under the cursor.
- [ ] Polling stops when the tab is hidden and when the watched work has finished.
- [ ] Live regions marked; changes flashed; animations off under `prefers-reduced-motion`.

## Layout

- [ ] Works at 1440 and 375 px; wide tables scroll inside their card; long decimals wrap in-cell.
- [ ] Reuses `PageHeader`, `Section`, `KpiStrip`, `StateViews`, `DisplayValues` instead of new
      look-alikes. No duplicated helper (CLAUDE.md: extract and reuse).
- [ ] Literals used more than twice are constants (`paths.ts`, `lib/codes.ts`, …).
- [ ] Amber stays the single accent.

## Charts

- [ ] Through `<Chart>` with a meaningful `ariaLabel`; option built in a tested `.ts` module.
- [ ] No lines across gaps; gaps explained; no substituted values.
- [ ] Marker meanings consistent with the rest of the console; clickable markers are real buttons.
- [ ] View state that matters is in the URL.

## Accessibility

- [ ] Every control is a real button/link, reachable by Tab, with a visible focus ring.
- [ ] Icon-only buttons have `aria-label`; popovers close on Escape and return focus.
- [ ] Text contrast ≥ 4.5:1 on its panel in both schemes.

## Verification

- [ ] Tests cover new formatting/model logic and page behaviour (MSW fixtures, `?mockState=` for
      hard-to-reach states).
- [ ] `npm test`, `npm run lint`, `npm run build` pass in `frontend/`.
- [ ] Checked in the browser at both widths; screenshots in `frontend/qa/<yyyyMMdd>-<topic>/`.
