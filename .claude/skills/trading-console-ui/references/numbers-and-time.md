# Numbers, percent and time

Read this when a change shows, compares, sums, sorts, validates or sends an amount, percent, slot
or timestamp.

## Amounts are decimal strings end to end

The API types amounts as `string` (`realizedPnl`, `price`, `notional`, …). Keep them strings from
the response to the screen and back to the request. All helpers live in `src/lib/decimal.ts` and
have tests in `decimal.test.ts`; extend those tests when you extend a helper.

| Task | Do | Don't |
|---|---|---|
| Show | `formatDecimal(v)` / `<Decimal value={v}>` (groups the integer part, keeps every fraction digit) | `Number(v).toFixed(2)`, `toLocaleString` |
| Show a tiny price | `compactDecimal(v)` → `0.0₇8643` (still exact) | scientific notation |
| Show short, keep exact | shortened text + `Tooltip` with `formatDecimal(v)` | rounding with no way to see the real value |
| Sign / zero test | `decimalSign(v)`, `pnlTone(v)`, `isPositiveDecimal(v)` | `Number(v) > 0`, `v === '0'` (fails on `0.000`) |
| Sum / running total | `addDecimals(a, b, …)` | `reduce((s, x) => s + Number(x))` |
| Sort by amount | `compareDecimals(a, b)` | `Number(a) - Number(b)` |
| Drop trailing zeros for a label | `trimFractionZeros(v)` | regex on a float's string |
| Plot | `toChartNumber(v)` — the only float conversion | reuse that number in a label or tooltip |
| Validate input | `validateDecimal(input, rule)` with the backend's precision and scale | `isNaN(Number(input))` |

The backend refuses too many fraction digits rather than rounding, so validation reports
"At most N decimals" instead of silently rounding the user's input.

Missing values render `—`. `formatDecimal(null)` already does this; keep it for slots, times and
counts too (`formatSlot`, `formatCount`).

## Units

Always show the unit or make it unmistakable from the column header: `SOL`, `USDT`, `%`, `slots`.
A SOL figure converted to USDT carries its provenance (`usdtRateNote`: the rate and the Binance
1-minute open it came from). When a rate is missing, say so; don't fall back to another rate.

## Percent and basis points

- Percent strings from the server go through `formatPercent(value, { signed })`; it trims trailing
  zeros, uses the true minus sign `−`, and adds `+` when `signed`.
- Older recordings carry bps only; `levelPercent(percent, bps, signed)` and `signedBps` fall back
  to an exact bps → percent conversion. Don't divide bps by 100 as a float.
- A percent is always relative to something; label the base (entry price P0, notional, previous day).

## Signs and colour

- Gains: `+` and `teal` (`terminal.gain`). Losses: `−` and `red` (`terminal.loss`). Zero: no sign,
  `dimmed`.
- The sign must be readable without colour. `<Pnl>`, `formatSignedDecimal`, `SolValue` and `formatPercent` do
  this; a headline may drop trailing zeros (`<Pnl trim>`) as long as the exact string is in its tooltip.

## Time

- Timestamps arrive as UTC ISO strings. Show `<Timestamp iso>` (local time, UTC in tooltip, a real
  `<time dateTime>` element), or `<RelativeTime>` for "3 min ago" in lists.
- Block time is whole seconds. Sub-second positions inside a slot are backend estimates; label them
  "estimated" and show the slot alongside.
- Automation pages run on the automation's own clock, not the browser's: use `<ZoneTime iso zone>`
  and put `<ZoneNote zone>` ("Times in UTC+5") next to the heading (`features/automation/AutomationShared.tsx`).
  A trading day is a date on that clock; converting it to browser-local time would shift the day.

## Slots, ids and addresses

- Slots: `formatSlot` (grouped digits) in the mono font; ranges with `slotRange`.
- Addresses, mints, signatures and ids: `<ShortId>` / `<Mint>` (first 4 … last 4, full value in
  the tooltip, copy button). Wrapped SOL shows as `SOL`. Never truncate without a way to copy.
- Wallet input is validated with `lib/base58.ts`.
