# Post-trade prices and no entry after the wallet sells

> Measure the dip from the curve price right after the wallet's buy (the end of its "candle"), compare later trades the
> same way, never enter once the tracked wallet has sold the token, and sell our whole position when it sells while we
> hold.

Status: Delivered

## Context

Run `84ff…a72a` (dataset `06a9…ec41`, token `6AeU…pump`) ended `NO_DIP`, and it showed two gaps against what the user
wants.

1. **The reference is the middle of the wallet's own move.** `DipEntryStrategy.intent` sets P0 to
   `DecimalPolicy.swapPrice(quote, base)`, the wallet's **average** fill price. On the bonding curve a 1.96 SOL buy moves
   the price from about 7.64e-8 (before) to about 8.26e-8 (after); P0 = 7.94e-8 lies in between. The user wants the dip
   measured from the **end** of that move. Spec `docs/strategy/decu-dip-v2-spec.md` already says so: P4 is the "post-trade
   price immediately after Decu's buy", and Section 5 makes the post-trade price (`virtual_sol / virtual_token` after the
   trade) the one measure for the reference, the dip and TP/SL. `AgreedStrategyPreset` lists it as the unsupported rule
   `POST_TRADE_PRICE`.
2. **The wallet's sells are ignored.** In that run Decu bought four times, then sold 2.54 SOL at 03:34:43 (slot
   460,699,402). The price reached the old dip level only at 03:37:12. Without the 30 s window, v4 would have bought
   *after* Decu left. The user's new rule: **never buy after Decu sells.** The spec has no such rule yet.

Owning code today:
- `strategy/application/DipEntryStrategy.kt`: reference, minimum market cap, dip watch;
- `portfolio/application/PositionService.kt`: TP/SL triggers compare `observation.price` (a trade's average);
- `replay/domain/ScenarioDecision.kt`, `replay/application/DipOutcomeCutoff.kt`,
  `replay/application/TargetTokenQualificationService.kt`: decision slots, capture cutoff, qualification;
- `papertrading/application/FillModel.kt`, `papertrading/domain/FillOutcome.kt`: rejection mapping;
- `console`: config DTOs, validator, limits, preset, stored report.

Data available:
- Every `MarketObservation` carries `curve` (post-trade reserves), so the post-trade price of **any** recorded trade is
  `BondingCurve.spotPrice(curve.reserves)`. No capture change is needed.
- Since the market-window-lead ADR (2026-10-22), windows start 50 slots before the first buy, so the wallet's buy itself is
  a recorded observation. Older captures start at S+1 and do not contain it.
- Since dataset v4, each observation has `trade` (trader, side). Decu's sells are therefore visible in the market data,
  even though wallet captures with scope `pumpfun-user-buys` record buys only. v3 observations have no `trade`.

## Scope

### In scope
- An opt-in **post-trade price measure** for the reference, the minimum-market-cap check at the reference, the dip trigger
  and TP/SL triggers.
- An opt-in **wallet-sell rule**:
  - a sell of the mint by the tracked wallet before the dip triggers ends the cycle with no entry;
  - a sell while we hold triggers a `WALLET_SOLD` exit of 100% of our position, whatever amount the wallet sold.
- Both enabled in `SimulationConfig.agreed()`, so the console preset, the `DECIDED` cutoff and qualification use them.
- Engine `dip-paper-v5`, persistence, console config and report, a Codex brief for the frontend, spec amendment and ADR.

### Out of scope
- How our own fill is priced. Our entry stays the curve execution's average (spec Section 5: "Our average fill").
- Same-slot transaction order. Canonical order inside a slot is by observation id, not chain order. The rules below are
  written so they never depend on it.
- Re-capturing old datasets so they include the wallet's buy.
- The two UI issues found in run `84ff…a72a`: the "watched through" label shows the horizon end rather than the window
  end, and the timeline axis reads +0..+4 slots. Both are frontend work and get a separate Codex brief.
- Changing `SimulationConfig.mvp()`. It stays the API and CLI default, and its hash stays unchanged.

## Key Decisions

1. **Opt-in configuration with today's behaviour as the default.** This follows the timed-dip-window precedent.
   `SimulationConfig` gains two fields:
   - `priceMeasure: PriceMeasure = TRADE_AVERAGE`;
   - `followWalletSells: Boolean = false`.

   `agreed()` sets `POST_TRADE` and `true`. With both left at their defaults a v5 run's outcomes equal v4's, so the existing
   fixtures, the v3 sample and every stored run keep their meaning. Old configurations can still be rerun for comparison.
2. **The configuration hash names a new field only when it is not the default**: `priceMeasure=POST_TRADE` and
   `followWalletSells=true`. `mvp()`'s hash `be93…a329` does not change.
3. **The reference is the post-trade price of the wallet's own buy, read from its market observation.** The observation must
   satisfy all of these:
   - same pair and slot as the buy;
   - `ObservationSignature.of(it) == buy.signature`;
   - the top-level index in `sourceEventId` (`sig:topLevel:inner`) equals `buy.instructionIndex`;
   - when `trade` is recorded, `trade.trader == buy.wallet` and `trade.side == BUY`;
   - as a price the run uses, it passes `ObservationEligibility` (an ineligible match is unavailable, not a fallback).

   Exactly one match is required. Reference = `BondingCurve.spotPrice(match.curve.reserves)`. With zero or more than one
   match the cycle ends `REFERENCE_UNAVAILABLE`. There is no fallback to the average: no silent fallback, per the
   trading-domain rules. Wallet transactions carry no reserves, and adding them would need a new dataset schema and still
   leave old datasets without them. Reading the observation needs no schema change.
4. **The minimum market cap is checked at the post-trade reference** (spec P5: "at the reference price"). Same rate lookup
   and the same strictly-greater rule as today.
5. **The dip trigger compares each later trade's post-trade price**, `spotPrice(curve.reserves) ≤ dipPrice`. The window,
   eligibility, migration and slot-after-buy rules are unchanged. `DipTrigger.price` and `marketCapUsd` use that post-trade
   price.
6. **TP/SL compare each trade's post-trade price** against levels that still come from our average fill (spec Section 5 and
   Section 8). Confirmed by the user.
7. **Wallet sells come from the market data only.** A wallet sell is an observation of the pair with
   `trade.trader == trackedWallet` and `trade.side == SELL`. A v4+ capture records every trade of the mint within its
   window, Decu's included, while wallet captures may hold buys only. An exit trigger must also name an observation. A
   Decu sell missing from the market data would mean the capture itself is incomplete, which is a separate problem.
8. **Same-slot conservatism.** Inside the watch, the slots from the buy's slot through the trigger's slot are each checked
   for a sell **before** a trigger in the same slot. A sell in the trigger's slot therefore blocks the entry, because we
   cannot prove it came after the trigger. Only sells **before the trigger** count. A sell between the trigger and our fill
   is something we could not have seen when deciding (spec: the decision is when the buy is submitted).
9. **A run asking for the sell rule on a dataset whose observations record no trades (v3) is refused** before it runs, with
   a typed error. The rule would otherwise silently see only wallet-capture sells.
10. **Engine `dip-paper-v5`.** Every run id changes once, as with v4. v4 cutoffs reopen on resume.
11. **A wallet sell while we hold is an exit trigger, `CloseReason.WALLET_SOLD`.**
    - It sells 100% of our tokens, even when the wallet sold only part of its own.
    - It joins TP and SL in `PositionService.close`: the earliest slot with a trigger wins and fixes the reason. Within a
      slot, a wallet sell is read first, so it wins over a TP/SL crossing in the same slot, the same trade included, whatever
      their observation ids (the out-of-scope same-slot order is never relied on).
    - The sale follows the TP/SL path unchanged: into the first eligible trade at or after `trigger.slot + latencySlots`
      within the 60 s `ExitSaleWindow`, otherwise against the standing curve once the deadline is proven
      (`NEXT_TRADE` / `STANDING_CURVE`).
    - A wallet sell landing after we decided to buy but no later than our fill slot (slots `trigger.slot + 1` through
      `fill.actualSlot`) triggers the exit as soon as we hold. Its trigger is that sell's observation, and the sale is into
      the first eligible trade after the fill slot.
    - A wallet sell at or after a timed position's `exitStart` changes nothing, because the time exit has already started.
12. **Qualification:** `WALLET_SOLD` is a negative case (`NotQualified`). `REFERENCE_UNAVAILABLE` is **not** a negative
    case. It fails the verdict with `VerdictReasons.REFERENCE_UNAVAILABLE`, because the data cannot say whether the token
    qualified. A capture made before the lead must be re-captured to qualify under `agreed()`.

## Module Contracts

- **`strategy.domain`** (additive):
  - `enum class PriceMeasure { TRADE_AVERAGE, POST_TRADE }` with `fun of(observation: MarketObservation): BigDecimal`. This
    is the one place that chooses `observation.price` or `BondingCurve.spotPrice(observation.curve.reserves)`;
  - `SimulationConfig + priceMeasure`, `+ followWalletSells`, and the matching named parameters on `of(...)`;
  - `DipWatchOutcome + WALLET_SOLD, REFERENCE_UNAVAILABLE`;
  - `TradeIntent + referenceObservationId: String?` (set only under `POST_TRADE`);
  - `TradeIntent + walletSell: WalletSellSignal?`;
  - `data class WalletSellSignal(val eventId: String, val slot: Slot)`, where `eventId` is the observation id or the wallet
    event id.
- **`papertrading.domain.RejectionReason`** `+ WALLET_SOLD, REFERENCE_UNAVAILABLE`. Both are entry reasons, so they are in
  `ENTRY`.
- **`portfolio.domain.CloseReason`** `+ WALLET_SOLD`. It is also used as an `ExitTrigger.reason`.
- **`portfolio.application.PositionService`**: the signature is unchanged.
  - The TP/SL comparison goes through `config.priceMeasure`.
  - Under `followWalletSells` it reads the tracked wallet from `TradeIntent` (which gains `wallet: WalletAddress`, the
    buy's wallet) for the `WALLET_SOLD` trigger.
- **`replay`**:
  - `ReplayRunner` refuses rule 9 with a replay-area domain exception that names the dataset schema version;
  - `ScenarioDecision`, `DipOutcomeCutoff` and `TargetTokenQualificationService` handle the new reasons;
  - `VerdictReasons + REFERENCE_UNAVAILABLE`.
- **Console HTTP**, all additive:
  - `ConsoleConfigRequest`, `ConfigResponse` and the limits gain `priceMeasure` (enum string) and `followWalletSells`
    (boolean);
  - the preset response gains them;
  - each run report intent gains `referenceObservationId` and `walletSell {eventId, slot}`;
  - the two new reasons appear wherever reasons are listed.

**Contract changes needing agreement:** none breaking. The dataset format is unchanged. The engine version bump changes
every run id.

## Domain Rules

1. With `TRADE_AVERAGE` and `followWalletSells = false`, every v5 outcome, price and decision slot equals v4's on the same
   dataset. Only the engine version, and therefore the run id and checksum, differ.
2. With `POST_TRADE`, `referencePrice = spotPrice(buyObservation.curve.reserves)` at `DecimalPolicy.PRICE_SCALE` and
   `DecimalPolicy.ROUNDING` (the scale and rounding `BondingCurve.price` already uses), and
   `dipPrice = price(reference × (1 − dipBps/10,000))` as today.
3. No matching buy observation, two or more, or a single ineligible one, gives `REFERENCE_UNAVAILABLE`, decided at `creationSlot`. Order of the
   buy-time checks:
   1. `UNSUPPORTED_SUPPLY`
   2. `REFERENCE_UNAVAILABLE`
   3. `REFERENCE_BELOW_MIN_MARKET_CAP`
   4. closed window → `NO_DIP`
   5. watch
4. A trade whose average is above the dip level but whose post-trade price is at or below it **triggers** under
   `POST_TRADE`. A trade with the opposite relation does not. A sell's average always lies above its post-trade price.
5. The post-trade market cap of the reference must be strictly greater than `minMarketCapUsd`. Equal is refused, as today.
6. Under `followWalletSells`:
   - a wallet sell at slot `s`, with `creationSlot ≤ s ≤ trigger.slot` (and `s` not past the dip window's end), gives
     `WALLET_SOLD` with `walletSell = (eventId, s)`, decided at `s`;
   - a sell in the trigger's own slot blocks the entry;
   - a sell after the trigger slot, or after the window ended the watch, changes nothing;
   - another trader's sell never counts;
   - the wallet's own **buy** never counts.
7. Only market-data sells count (Key Decision 7). An accepted wallet trade with `side == SELL` is not read. `eventId` is
   the sell's observation id.
8. `followWalletSells = true` on a dataset with schema below v4 is refused before any scenario runs. Nothing is
   persisted.
9. Under `POST_TRADE`, TP fires when the post-trade price is at or above `takeProfitPrice`, and SL when it is at or below
   `stopLossPrice`. `takeProfitPrice` and `stopLossPrice` still come from `fill.executionPrice`. The first trigger still
   fixes the reason.
10. **Wallet-sell exit** (Key Decision 11), under `followWalletSells` only:
    - a wallet sell at slot `s`, with `trigger.slot < s`, `s` no later than the first TP/SL crossing's slot (within a slot,
      a wallet sell is read first) and, when timed, `observedTime < exitStart`, sets `exitTrigger = (sell observation, s, its time, WALLET_SOLD)`;
    - the position then closes by the TP/SL sale rules with `reason = WALLET_SOLD`;
    - a 1% sell closes 100% of `fill.quantity`;
    - another trader's sell, or the wallet's buy, never triggers;
    - with the flag off, the wallet's sells are ignored while holding, as today;
    - decision slots are those of a TP/SL close (`NEXT_TRADE` → close slot; `STANDING_CURVE` or `UNRESOLVED` → the slot
      proving the 60 s deadline).
11. **Determinism:** no clock and no new ids. `PriceMeasure.of` is pure. Sells are sorted by `(slot, id)`. The reordered and
    duplicated replay test is extended to a `POST_TRADE` + sell-rule config.
12. **Idempotency** is unchanged: run id = hash(engine, dataset, config), guarded by the ledger's unique run id.
13. **Provenance:**
    - each intent carries `referenceObservationId` (under `POST_TRADE`) and `walletSell` (when it decided);
    - the configuration snapshot carries both new fields;
    - `resultParts` adds `referenceObservationId`, `walletSell.eventId` and `walletSell.slot`, so the result checksum covers
      them.

## Data Model

Additive, nullable columns on `trade_intents` (`TradeIntentEntity`):

| Column | Type |
|---|---|
| `reference_observation_id` | `varchar(ReplayLimits.MAX_OBSERVATION_ID_LENGTH)` null |
| `wallet_sell_event_id` | `varchar(ReplayLimits.MAX_OBSERVATION_ID_LENGTH)` null |
| `wallet_sell_slot` | `bigint` null |

New enum values:
- `trade_intents.watch`: `+ WALLET_SOLD`, `REFERENCE_UNAVAILABLE`;
- `paper_orders.rejection_reason`: `+ WALLET_SOLD`, `REFERENCE_UNAVAILABLE`;
- `positions.close_reason` and `positions.exit_trigger_reason`: `+ WALLET_SOLD`.

`TradeIntent.wallet` is the buy's wallet, already stored with the source event. It is persisted only if the intent entity
lacks it today; the implementer checks `TradeIntentEntity` and adds `wallet varchar(44)` null if it is missing.

`StoredRunConfiguration` gains `priceMeasure` (default `TRADE_AVERAGE`) and `followWalletSells` (default `false`), so
stored v1–v4 snapshots still read. `docs/schema/V20261025120000__post_trade_price_and_wallet_sell.sql` adds the columns for
production and extends the local Hibernate check constraints in place, keeping every existing value (as
`V20261024120000`). Nothing is destructive.

## Transactions & Integration

- Runs are unchanged: in memory, then one `SimulationRunTransaction`.
- No provider calls, no capture change and no new properties. Fixtures suffice.

## What To Implement

1. **Config and engine.**
   - `PriceMeasure`, the two `SimulationConfig` fields, and `agreed()` setting them;
   - the hash parts that are appended only when set;
   - `ENGINE_VERSION = "dip-paper-v5"` and its KDoc;
   - rule 8's refusal in `ReplayRunner`.
2. **Reference and dip under `POST_TRADE`.** In `DipEntryStrategy`: the buy-observation match, `REFERENCE_UNAVAILABLE`, the
   minimum market cap at the reference, and the trigger compared through `PriceMeasure` (rules 2–5). Depends on 1.
3. **Wallet-sell rule at entry.** In `DipEntryStrategy`, which reads the buy wallet's sells from the market data during the
   watch:
   - `WALLET_SOLD` and `walletSell`;
   - `FillModel` mapping;
   - `ScenarioDecision` decides `WALLET_SOLD` at `walletSell.slot` and `REFERENCE_UNAVAILABLE` at `creationSlot`.

   Depends on 1. Touches the same file as 2, so it has the same owner, done after 2.
4. **Exits** in `PositionService`:
   - TP/SL compared on the post-trade price under `POST_TRADE` (rule 9);
   - the `WALLET_SOLD` exit trigger, including sells between our trigger and our fill (rule 10);
   - `ScenarioDecision` treats a `WALLET_SOLD` close like a TP/SL close.

   Depends on 1 and 3.
5. **Cutoff and qualification.**
   - `DipOutcomeCutoff` with the new reasons under the updated `agreed()`;
   - `TargetTokenQualificationService` per Key Decision 12;
   - `CaptureCutoffModes` and recommendations text.

   Depends on 2–4.
6. **Persistence and console.**
   - entity columns, mappers, the stored report and the reference SQL;
   - config DTOs, validator, limits and `RunReportReader`;
   - `AgreedStrategyPreset`:
     - `priceMeasure` `AGREED` with spec ref `P4 / Section 5`;
     - `followWalletSells` `AGREED` with the new spec rule's ref;
     - remove `POST_TRADE_PRICE` from `UNSUPPORTED_RULES`.

   Depends on 1–3. It can run in parallel with 5, since the two touch different files.
7. **Codex brief** `docs/plans/20261024-post-trade-price-and-wallet-sell-codex-brief.md`:
   - the two form fields: a price-measure select, and a "Follow Decu's sells (no entry, and exit if holding)" checkbox;
   - the close reason `WALLET_SOLD`: "Sold because Decu sold", with Decu's sell linked;
   - the cycle card labels:
     - "Reference price (after Decu's buy)" under `POST_TRADE`;
     - `WALLET_SOLD`: "Decu sold before the dip" with the sell's slot and links;
     - `REFERENCE_UNAVAILABLE`: "Decu's buy is not in the recorded market, so the reference is unknown";
   - the HTTP contract and the verify commands.

## Verification

Test-first. Run with JAVA_HOME set to the IntelliJ JDK.

- **`DipEntryStrategyTest`:**
  - the post-trade reference comes from the matching observation;
  - each of these gives `REFERENCE_UNAVAILABLE`:
    - no buy observation;
    - two matches;
    - a match whose `trade.trader` is another wallet;
  - a v3 observation (no `trade`) matches on signature and index alone;
  - the minimum market cap is judged at the post-trade reference (one case flips from refused to accepted);
  - rule 4's two cases;
  - rule 6: a sell before the trigger, a sell in the trigger slot, a sell after the trigger, a sell after the window,
    another trader's sell;
  - a sell recorded only as a wallet trade is not read;
  - with the flag off, sells are ignored;
  - defaults reproduce v4 (rule 1).
- **`PositionServiceTest`:**
  - TP and SL each fire on the post-trade price where the average would not, and the reverse;
  - a wallet sell while holding closes 100% as `WALLET_SOLD`:
    - sold into the next trade within 60 s;
    - sold against the standing curve when no trade comes within 60 s;
    - `UNRESOLVED` when the curve cannot pay;
  - a 1% wallet sell still sells everything;
  - a sell between our trigger and our fill exits right after the fill;
  - TP or SL before the wallet sell keeps its own reason;
  - a wallet sell on the same trade as an SL crossing is `WALLET_SOLD`;
  - a wallet sell at or after `exitStart` is ignored;
  - another trader's sell is ignored;
  - with the flag off, the wallet's sells are ignored.
- **`ScenarioDecisionTest`:** decision slots for `WALLET_SOLD` and `REFERENCE_UNAVAILABLE`.
- **`ReplayRunnerTest`:**
  - `mvp()` hash unchanged;
  - engine `dip-paper-v5`;
  - the v3 sample with the sell rule is refused;
  - the checksum changes when `walletSell` changes;
  - the reorder/duplicate determinism test with the new config.
- **`DipOutcomeCutoffTest`, `TargetTokenQualificationServiceTest`:** the new reasons; `REFERENCE_UNAVAILABLE` fails the
  verdict.
- **`AgreedStrategyPresetTest`, `ConsoleConfigValidatorTest`:** the new fields, bounds and the preset rows.
- **Persistence integration test (Testcontainers):** the new intent columns and enum values round-trip.
- **Fixture:** a v6 fixture whose window includes the wallet's buy, plus a later sell by the wallet before a dip.
- **Commands:** `.\gradlew.bat test`; then rerun dataset `06a9…ec41` from the console (see Acceptance Criteria).

## Documentation Sync

- **Spec** `docs/strategy/decu-dip-v2-spec.md`:
  - add the wallet-sell rule to Section 2 (`WATCHING → SKIP_WALLET_SOLD`, `OPEN → EXITING` on a Decu sell);
  - add it to Section 4 or 6 with its same-slot rule;
  - add the `WALLET_SOLD` trigger to Section 8's trigger table;
  - bump "Last updated". This is the user's document, so the wording is for the user
  to confirm.
- **ADR** `docs/adr/20261025-post-trade-price-and-wallet-sell.md`: the reference read from the buy's observation, no
  fallback, same-slot conservatism, and the v3 refusal.
- **README:** the configuration fields, the new reasons, and the engine version.

## Acceptance Criteria

1. Rerunning dataset `06a9…ec41` with run `84ff…a72a`'s configuration plus `POST_TRADE` and the sell rule stores:
   - `referenceObservationId` = the observation of signature `vb8V…zKzN`, top-level index 2;
   - `referencePrice` ≈ 8.26e-8 (my estimate; the stored value comes from the real reserves);
   - `dipPrice` = 0.8 × that;
   - `watch = NO_DIP`, since the lowest post-trade price inside 30 s is about 21% above P0.
2. The same rerun with `dipWindowMs` cleared ends `WALLET_SOLD` with `walletSell.slot = 460,699,402`, and its order shows
   rejection reason `WALLET_SOLD`. (Expected from the averages; the test asserts the real post-trade values.)
3. Any v4 run's dataset and configuration rerun under v5 with the defaults gives identical outcomes and prices.
4. A `POST_TRADE` run on a dataset captured before the lead shows `REFERENCE_UNAVAILABLE` on every cycle and stores no
   reference observation.
5. Running the sell rule on a v3 dataset returns the typed refusal, and no run row is written.
6. The console preset lists both fields as `AGREED`. `POST_TRADE_PRICE` is no longer an unsupported rule.
7. On a fixture where the wallet sells 1% of its tokens while we hold, the position is `CLOSED` with
   `close.reason = WALLET_SOLD`, `exitTrigger.observationId` = the wallet's sell, and proceeds for 100% of
   `fill.quantity`.

## Open Questions

None. The user answered all four on 2026-10-24:

1. **TP/SL use the post-trade price: yes.** The levels stay `our average fill × 1.55` and `× 0.60`, and each later trade's
   post-trade price is compared against them (unit 4 and rule 9 stay in).
2. **A wallet sell while we hold: sell 100% of our position** (Key Decision 11), because Decu sells to get out. The user
   first said "not now" and then changed it.
3. **Any sell amount counts: yes.** Even a 1% sell blocks the entry. There is no threshold.
4. **A sell in the trigger's own slot blocks the entry: yes** (Key Decision 8). A live bot sees a slot's transactions
   together, so it would already know about that sell when it decides to buy.

## Delivered

2026-10-29.

What shipped:
- engine `dip-paper-v5`, with `priceMeasure` and `followWalletSells` (both on in `agreed()`);
- the post-trade reference, dip, TP and SL;
- `WALLET_SOLD` as an entry rejection and as a full exit;
- `REFERENCE_UNAVAILABLE`;
- the v3 refusal (409 in the console);
- persistence and `V20261025120000__post_trade_price_and_wallet_sell.sql`;
- console config and report fields;
- README, `docs/REAL_DATA_CAPTURE.md`, `docs/REPLAY_FORMAT.md`, ADR `20261025-post-trade-price-and-wallet-sell`, and the spec amendment.

Where it differs from the plan:
- **Wallet sells come from market data only.** Wallet-transaction sells are not read.
- **A sell before our fill** exits with the trigger dated at the fill, so the sale is at `fill + latencySlots`.
- **Within a held slot, a wallet sell is read before a TP/SL crossing** (review), so the reason never depends on id order.
- **The reference observation must pass `ObservationEligibility`** (review).
- **Qualification treats `REFERENCE_UNAVAILABLE` and `SELL_CHECK_NEEDS_TRADES` as failed verdicts**, logged at WARN.
- **Old intents without a stored wallet read it from their source trade.**
- **No new fixture file.** The fixtures are shaped in code instead (`AgreedDipSample`).
- **Frontend.** The Codex brief `20261024-post-trade-price-and-wallet-sell-codex-brief.md` was written. On 2026-10-29 the user moved frontend work to Claude, so a separate session implements it.

Verification: `./gradlew build` with Docker up ran 1594 tests, all passing except 5 that also fail on HEAD (SharedTransactionCacheTest, TransactionCacheOptionTest, WalletActivityConfigurationTest, WalletHistoryParallelCaptureTest ×2).

Not verified: rerunning dataset `06a9…ec41` in the console (acceptance criteria 1–2).
