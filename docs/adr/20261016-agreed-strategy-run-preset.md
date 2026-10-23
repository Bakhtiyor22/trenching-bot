# Agreed-strategy run preset on dip-entry v1

Accepted 2026-10-16 with the approved [console capture, run and results plan](../plans/archive/20261016-console-capture-run-ux.md) (Context, Key Decisions 2–4, Domain Rules 15–19).

## Context

The agreed strategy is `decu-dip` v2, [docs/strategy/decu-dip-v2-spec.md](../strategy/decu-dip-v2-spec.md) (last updated 2026-10-11). Its engine is planned in [20261015-decu-dip-v2-replay.md](../plans/20261015-decu-dip-v2-replay.md), which is still a draft, so nothing runs it. The only runnable engine is `dip-entry` v1 (engine `dip-paper-v2`, [dip-entry decision](20261008-dip-entry-strategy.md), [bonding-curve execution](20261009-bonding-curve-execution.md)).

New runs in the console started from `SimulationConfig.mvp()` (15% dip, +30% / −15%, 4,500 slots), not from the agreed values. The user wanted runs to start from the agreed strategy, while saying honestly what the running engine cannot do.

## Decision

`GET /api/runs/preset` returns the preset `decu-dip-v2-on-dip-entry-v1`: the spec mapped onto `dip-entry` v1 field by field. It lives in code as `AgreedStrategyPreset` (`console/domain`). `AgreedStrategyPresetTest` reads the YAML block of the spec and fails if `dipBps`, `takeProfitBps`, `stopLossBps`, `notionalSol`, `minReferenceMcapUsdExclusive` or `entryPriorityFeeSol` drift from the preset. The backend never reads `docs/` at runtime.

| `dip-entry` v1 field | Preset value | Spec source | Status | Note code |
|---|---|---|---|---|
| `dipBps` | 2000 (20%) | P6 | `AGREED` | |
| `takeProfitBps` | 5500 (+55%) | P9 | `AGREED` | |
| `stopLossBps` | 4000 (−40%) | P10 | `AGREED` | |
| `notional` | 0.1 SOL | P12 | `AGREED` | `CURVE_PAYMENT_FEES_ON_TOP`: the curve payment, fees on top, as `FillModel` does |
| `transactionCostSol` | 0.001 | `entryPriorityFeeSol` | `AGREED` | `NETWORK_FEE_NOT_ADDED` |
| `quoteMint` | wrapped SOL | P15 | `AGREED` | |
| `minMarketCapUsd` | 10000 | P5 | `AGREED_MEASURED_DIFFERENTLY` | `MEASURED_AT_DIP_INCLUSIVE`: the spec measures it at the reference buy, strictly greater; v1 measures it at the dip, `>=` |
| `latencySlots` | 1 | none | `ASSUMPTION` | `REPLAY_LATENCY_ASSUMED` |
| `horizonSlots` | 4500 | none | `MISSING` | `NO_SINGLE_WINDOW_IN_SPEC` |
| `maxAgeSlots` | 2 | none | `ENGINE_DEFAULT` | |

**The horizon stays at 4,500 slots and is flagged `MISSING`.** The spec has no single window: the dip must come within 30 s of Decu's buy, and the exit starts at `min(entry + 3 min, t0 + 4 min)`. `dip-entry` v1 has one horizon for both and no timed exit. No single number matches both, so the preset keeps the engine default and never presents it as agreed. 600 slots (about 4 minutes) was offered as the closest number and not chosen; the user can set a value later.

**Eight spec rules cannot be applied by `dip-entry` v1.** Every preset response lists them, in this order, and the preset never claims them:

| Spec ref | Code | Rule |
|---|---|---|
| P2 | `FIRST_EVER_BUY` | Decu's first-ever interaction with the token, and it is a buy |
| P3 | `SIGNAL_OVER_1_9_SOL` | The buy is over 1.9 SOL |
| P5 | `MCAP_AT_REFERENCE_BUY` | The market cap is measured at the reference buy |
| P7 | `DIP_WINDOW_30_SECONDS` | Our buy is submitted within 30 s of Decu's |
| P8 | `ENTRY_SLIPPAGE_15_PERCENT` | 15% entry slippage against the quote at submission |
| P11 | `TIMED_EXIT` | The exit starts at `min(entry + 3 min, t0 + 4 min)` |
| Section 8 | `EXIT_RETRIES_AND_UNRESOLVED` | Exit retries and the `UNRESOLVED` state |
| P13 | `ONE_POSITION_INCLUDING_IN_FLIGHT` | At most one position, entries in flight included |

**`SimulationConfig.mvp()` stays the API default.** `GET /api/config/defaults`, a `POST /api/runs` without `config`, the CLI replay commands, the wallet + token qualification and the `DECIDED` market cutoff all keep using `mvp()`. Only the new-run form loads the preset, from its own endpoint. Changing the default would change what existing callers and stored captures mean: CLI and API runs would get new run ids without asking, and windows already cut under `DECIDED` were decided by the `mvp()` levels. Which configuration the cutoff should use is a separate decision (plan Open Question 3); until then the `CUTOFF_DECIDED` recommendation carries the caveat `CUTOFF_USES_LEGACY_DEFAULTS`.

**A preset run is an ordinary run.** The preset's values are plain `SimulationConfig` values, so a run from the unchanged preset has the same `configurationHash` and run id as the same values sent in bps. `presetId` on `POST /api/runs` is validated (only this id is accepted) and written to the `console run started` log line. It is not stored, because storing it would change replay's public `SimulationRuns.start` contract.

## Alternatives

- Changing `SimulationConfig.mvp()` to the agreed values: it would silently move every existing API and CLI caller and the `DECIDED` cutoff to new rules.
- Reading the spec file at runtime: `docs/` is not part of the jar, and a document edit would change runs without a code review. The drift test catches a change instead.
- Choosing a horizon for the user: it would present an assumption as agreed.

## Consequences

The run form starts from the agreed values and shows which ones are exact, measured differently, assumed, engine defaults or missing. Results of a preset run are still `dip-entry` v1 results: they do not apply the 8 rules above, so they are not results of the agreed strategy. Run ids, configuration hashes, stored runs and the replay engine are unchanged.

## Revisit when

The `decu-dip` v2 engine ships. The preset then points to that engine, the unsupported-rule list becomes empty and the `MISSING` horizon goes away. Revisit sooner if the user picks a horizon for `dip-entry` v1 or decides which configuration the `DECIDED` cutoff uses.

## Amendment 2026-10-22: a ninth unsupported rule

The list above missed one rule. Spec Section 5 compares every price as the post-trade price: the curve's virtual SOL over virtual token reserves right after a trade. `dip-entry` v1 compares each trade's average execution price, its SOL amount over its token amount, for the reference price (`DipEntryStrategy`), the dip trigger and the take-profit and stop-loss triggers (`PositionService`). For a large buy the two differ: the reference run `fbd3…2b0b`'s first buy averaged a $9,429 market cap, while the curve right after it stood near $9,780.

The preset now lists it as the fourth rule, after `MCAP_AT_REFERENCE_BUY`:

| Spec ref | Code | Rule |
|---|---|---|
| Section 5 | `POST_TRADE_PRICE` | Every comparison uses the post-trade price, never a trade's average execution price |

`AgreedStrategyPresetTest` checks that Section 5 of the spec still states this. The configuration, run ids and stored runs are unchanged.

## Amendment 2026-10-23: the market cap is checked at the reference buy

Engine `dip-paper-v3` checks P5 as the spec states it: the wallet's buy must be valued strictly above `minMarketCapUsd`, and the dip is not checked ([decision](20261023-reference-market-cap.md)). `minMarketCapUsd` is now `AGREED` without a note (it was `AGREED_MEASURED_DIFFERENTLY` with `MEASURED_AT_DIP_INCLUSIVE`), and `MCAP_AT_REFERENCE_BUY` leaves the unsupported list, which keeps 8 rules with `POST_TRADE_PRICE` third. The preset's values and configuration hash are unchanged; its run ids change with the engine version.
