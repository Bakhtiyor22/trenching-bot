# Replay dataset v3, v4, v5 and v6

Four versions are supported side by side. v4 adds each observation's decoded `trade` (the actor, side and amounts of the Pump.fun trade behind it). v5 keeps every v4 field and adds `coverage`: for each market window, the slots it was observed through. v6 keeps every v5 field and adds each coverage entry's optional `throughTime`: the block time the window was observed through. Everything else in this document applies to all four unless it says otherwise.

| | v3 | v4 | v5 | v6 |
|---|---|---|---|---|
| `schemaVersion` | 3 | 4 | 5 | 6 |
| Observation `trade` object | must be absent (`TRADE_FIELDS_IN_V3`) | required (`TRADE_FIELDS_MISSING`, `TRADE_FIELDS_INVALID`) | required, as in v4 | required, as in v4 |
| Loaded `MarketObservation.trade` | always null: unavailable, never inferred | the decoded trade | the decoded trade | the decoded trade |
| Root `coverage` array | must be absent (`COVERAGE_BEFORE_V5`) | must be absent (`COVERAGE_BEFORE_V5`) | required (`COVERAGE_MISSING`, `COVERAGE_INVALID`) | required, as in v5 |
| Coverage entry `throughTime` | no coverage | no coverage | must be absent (`COVERAGE_INVALID`) | optional |
| Checksum parts | as listed under `DatasetFingerprint` below | the same, then `trader`, `side`, `solLamports`, `tokenRaw` per observation | the v4 parts, then each coverage entry after the rates | the v5 parts, each coverage entry followed by its `throughTime` |
| Written by | no export since 2026-10-15; bundled sample, older files and catalog snapshots of them | older files and catalog snapshots of them | older files and catalog snapshots of them | `exportRecordedReplay` and the case export |
| Replay result | `dip-paper-v4` | the same engine; no strategy, fill or exit rule reads `trade` | the same engine; a run decided beyond its coverage is refused (`MARKET_COVERAGE_ENDED`) | the same as v5; `throughTime` adds the coverage end time |

The loader accepts 3, 4, 5 and 6; any other version is refused with `UNSUPPORTED_SCHEMA`. A v3 file keeps its bytes, checksum, run ids and meaning, and re-writing a loaded v3 dataset (the catalog snapshots do) reproduces the v3 form exactly, without `trade`. v3, v4 and v5 files keep their dataset checksums and ids under v6 support: a loaded file keeps its version, so a v5 file loads, re-writes and hashes as v5, with no `throughTime`.

See the bundled sample `src/main/resources/replay/samples/dip-sample-v3.json`. This is synthetic curated data with one token per dip-entry outcome, not a raw Solana or provider capture. The synthetic zero wallet/signature is not the Decu identity. Its curve states are synthetic too (see below).

Version 3 replaced version 2 on 2026-10-09 with the [bonding-curve execution model](plans/archive/20261009-bonding-curve-execution.md). It adds each observation's full curve state: the reserves after its trade and the fee basis points the program charged. Version 2 had replaced version 1 on 2026-10-08 with the [dip-entry strategy](adr/20261008-dip-entry-strategy.md), adding a SOL/USDT rate per observation minute and `curveComplete`. The loader refuses versions 1 and 2 (`UNSUPPORTED_SCHEMA`); re-export from the captures instead, which already hold every curve field. Version 4 followed on 2026-10-15 ([decision](adr/20261015-chart-trade-actors-and-mcap.md)): it adds the trade fields below so a chart can show who traded, and re-exporting a capture as v4 gives a new checksum and dataset id because the checksum covers the schema version and the trades. Old v3 datasets and their runs stay valid.

The additive [real history capture](REAL_DATA_CAPTURE.md) saves raw transaction evidence and a separately versioned manifest. Those files are not replay datasets and cannot be uploaded as such. `exportRecordedReplay` converts a verified wallet capture, its market capture and their rate capture into a v6 dataset, with each window's coverage through its observed end and the block time the capture recorded for that end, through `ReplayDatasetWriter`, re-extracting the trades from the verified local artifacts (no RPC), and loads the result back before writing it. Neither the capture nor the export alters this format, its checksum or simulation semantics.

The root has `schemaVersion` (3, 4, 5 or 6), `trackedWallet`, `transactions`, `observations`, `referenceRates`, for v5 and v6 `coverage`, and a SHA-256 `checksum`. Transactions and observations are bounded to 100,000 records each. Times must be exact at microsecond precision, prices and liquidity must fit `numeric(78,38)`, `source` is at most 128 characters and observation `id`/`sourceEventId` at most 255; the loader rejects anything persistence would. Addresses must decode from base58 to 32 bytes, signatures to 64 bytes. Slots are nonnegative Longs. Monetary values and raw token deltas are decimal strings, never floating-point JSON numbers. Decimals are 0..255; accepted absolute raw amounts fit u64.

Transactions contain wallet, signature, instruction index, slot, optional UTC block time, base/quote mints, signed base/quote deltas, decimals, success/route/signer flags, source and commitment. The route flag reflects prior curation; this loader does not decode on-chain instructions or prove that a route is supported. The normalizer classifies opposing deltas as buy/sell, preserves raw amounts and provenance, and retains ignored outcomes. Repeated external identity is deduplicated; conflicting copies fail.

Observations contain ID, pair, observed slot, price slot (for age checks), UTC observed time, positive price, optional nonnegative quote liquidity, source, source event ID, commitment and quality (`LAST_TRADE`, `MIDPOINT`, `INVALID`). Missing liquidity means depth is unknown; the simulator cannot enforce an unprovided depth constraint. Quality and the source observation reference are retained on fills. Each observation also has a required boolean `curveComplete`: true when its trade left the bonding curve's real token reserves at zero, so the token migrated with it.

Each observation has a required `curve` object: the Pump.fun bonding curve after the observation's trade, read from its `TradeEvent`. All six fields are raw integer strings (digits only, no sign, no decimal point), never JSON numbers:

| Field | Unit | Rule |
|---|---|---|
| `virtualSolReserves` | lamports (SOL has 9 decimals) | positive, at most u64 max |
| `virtualTokenReserves` | raw token units (6 decimals) | positive, at most u64 max |
| `realSolReserves` | lamports | 0..`virtualSolReserves` |
| `realTokenReserves` | raw token units | 0..`virtualTokenReserves` |
| `feeBps` | protocol fee basis points | 0..10,000 |
| `creatorFeeBps` | creator fee basis points | 0..10,000 |

A missing `curve` or field, a negative, non-integer or non-string value, real reserves above virtual ones, zero virtual reserves, or basis points above 10,000 is refused with `CURVE_STATE_INVALID`. `curveComplete` must equal `realTokenReserves == 0`; a contradiction is refused with `CURVE_STATE_INCONSISTENT`. The curve state is carried and persisted for the curve execution model; the fill and exit math do not read it yet.

The bundled sample's curve states are synthetic but consistent with its prices. They start from Pump.fun's standard initial virtual reserves (30 SOL and 1,073,000,000 tokens, so k = 30e9 × 1.073e15 in raw units). For an observed price P in SOL per token, `virtualSolReserves` = round(sqrt(P × 10^3 × k)) and `virtualTokenReserves` = floor(k / `virtualSolReserves`), so the spot price equals P. `realSolReserves` is the virtual SOL less the initial 30 SOL and `realTokenReserves` the virtual tokens less 279,900,000 tokens, both clamped at zero; an observation with `curveComplete` true has `realTokenReserves` 0. Every observation has `feeBps` 95 and `creatorFeeBps` 30, values chosen to match captured trades, not read from a real event. The sample's checksum is `06103b0faebf5d3e95468fc250232c333a0e6b665612c977fc76441883eac765`.

### Schema v4: the `trade` object

Each v4 observation has a required `trade` object, the decoded Pump.fun `TradeEvent` behind it. All three value fields are strings, never JSON numbers:

| Field | Meaning | Rule |
|---|---|---|
| `trader` | the event's `user`, the wallet that traded (not the fee payer or a router) | base58 public key, 32 bytes, at most 44 characters |
| `side` | `BUY` or `SELL` (the event's `isBuy`) | exactly `BUY` or `SELL` |
| `solLamports` | SOL paid or received, in lamports | raw integer string, 1..18446744073709551615 |
| `tokenRaw` | tokens bought or sold, in raw units (6 decimals) | raw integer string, 1..18446744073709551615 |

Refusal codes: `TRADE_FIELDS_IN_V3` (a `trade` key, even `null`, in a v3 observation, so v3 meaning cannot change by editing), `TRADE_FIELDS_MISSING` (a v4 observation without `trade`, or with `trade: null`) and `TRADE_FIELDS_INVALID` (a missing or non-string field, an unknown side, a non-integer, zero or above-u64 amount, or a trader that is not a 32-byte base58 key). A transaction with several trade events yields one observation, with its own `trade`, per event. The observation `source` and `id` (`pumpfun-bonding-curve-trade/v1:...`) are the same as in v3, so replay inputs are identical; only the capture's extractor provenance becomes `PUMPFUN_BONDING_CURVE_TRADE_V2`.

### Schema v5: `coverage`

A v5 dataset says how far each market window was observed, so a slot nobody fetched can never read as a slot without trades ([decision](adr/20261015-outcome-bounded-market-capture.md)). The root `coverage` array has one entry per window, written after `referenceRates` and ordered by base mint, then quote mint:

| Field | Meaning | Rule |
|---|---|---|
| `baseMint`, `quoteMint` | the window's market pair | base58 addresses that differ; one entry per pair |
| `fromSlot` | the first recorded slot: S−50 for captures from market manifest version 4, S+1 before, S being the wallet's first buy | integer, nonnegative |
| `throughSlot` | the last slot whose trades the dataset holds completely | integer, at least `fromSlot - 1` |

A window decided at the buy itself records through S: `[S−50, S]` with the lead, and `throughSlot = fromSlot - 1` (no slot observed) for a capture from before it. The lead's trades, the tracked wallet's own buy in slot S among them, are recorded but never read by a run, which watches from S+1. A full-window capture has `throughSlot = S + horizon`. Observations past `throughSlot` may be present, but nothing promises they are all of that slot's trades.

Refusal codes: `COVERAGE_MISSING` (no `coverage` key in a v5 or v6 file, or an observation whose pair has no entry), `COVERAGE_INVALID` (not an array, a missing or non-integer field, a negative slot, equal mints, `throughSlot` below `fromSlot - 1`, or two entries for one pair) and `COVERAGE_BEFORE_V5` (a `coverage` key in a v3 or v4 file, so their meaning cannot change by editing).

### Schema v6: coverage `throughTime`

A v6 coverage entry may add `throughTime`, the time its window was observed through, so a run can prove that the recording reached a moment in time, not only a slot ([plan](plans/archive/20261023-timed-dip-window-and-exit.md)):

| Field | Meaning | Rule |
|---|---|---|
| `throughTime` | the block time of the nearest produced block at or below `throughSlot`, as the market capture recorded it (market manifest version 5 `throughBlockTime`) | optional; an ISO-8601 UTC instant string, exact at microsecond precision |

The block time of the nearest earlier block is never later than the true time of `throughSlot`, so it is a conservative proof: the window's trades are complete at least through that moment. The time is read once, at capture, and stored; a run reads no clock. An entry has no `throughTime` when its capture recorded none: a market capture from before manifest version 5, or a block time read that has not yet succeeded. Such an entry proves slots only, as in v5. The writer omits a missing `throughTime` rather than writing `null`; the loader reads an absent or `null` one as none.

A run uses `throughTime` only for a coverage that ends within the cycle's horizon. Coverage that extends past the horizon proves the horizon end, whose own time the entry does not give, so the run treats it as recorded through the horizon end with no time. A `FULL` capture (4,500 slots) replayed with a shorter `horizonSlots` therefore cannot prove a dead token's time exit or take profit/stop-loss deadline by time alone; without a later trade the position stays open as `HORIZON_REACHED`.

Refusal codes: `COVERAGE_INVALID` also covers a `throughTime` that is not a string, not an instant or finer than a microsecond, and any `throughTime` in a v5 file, so v5 meaning cannot change by editing.

**Runs beyond coverage are refused.** Each scenario has a decision slot: the last slot whose trades its outcome depends on. Under `dip-paper-v4` it is:

- the close's slot for a `TAKE_PROFIT` or `STOP_LOSS` sold into the next trade (`NEXT_TRADE`, its sale slot);
- for a `TAKE_PROFIT` or `STOP_LOSS` sold against the standing curve (`STANDING_CURVE`), and for one left `UNRESOLVED`, the slot proving that no trade can land within 60 s of its trigger: the first recorded trade of the pair at or after the sale slot whose block time is later than the trigger's plus 60 s, or else the coverage end slot (`throughSlot`, when its `throughTime` is later than that deadline);
- for a `TIME_EXIT`, the slot proving the exit start is covered: the first trade at or after `exitStart`, or else the coverage end slot; a time exit left `UNRESOLVED` is decided at that same slot;
- the fill trade's slot for `EXCEEDS_CURVE` and `ZERO_QUANTITY`;
- the first `curveComplete` observation's slot for `MIGRATED_BEFORE_ENTRY`, `MIGRATED` and a `NO_FILL_AFTER_TRIGGER` caused by migration;
- the buy slot S for `UNSUPPORTED_SUPPLY` and `REFERENCE_BELOW_MIN_MARKET_CAP`, and for a windowed `NO_DIP` when the buy has no block time `t0`;
- for a windowed `NO_DIP` (a `dipWindowMs` is set), the first trade after the dip window, or the horizon end when there is none;
- the horizon end for an unwindowed `NO_DIP`, `DIP_BELOW_MIN_MARKET_CAP`, `HORIZON_REACHED`, `NO_EXIT_FILL` (produced only by `dip-paper-v3` and earlier) and any other `NO_FILL_AFTER_TRIGGER`.

The replay is causal, so a scenario decided at or before its pair's `throughSlot` has the outcome the whole window would give. A run with any scenario decided later, including a horizon longer than the coverage, is refused with `MARKET_COVERAGE_ENDED` naming the mint, the decision slot and `throughSlot`; nothing is stored and no run id is reserved (the console answers 409). Coverage must start no later than S+1 for the scenario's anchor buy S. A missing entry or a later start provides no usable prefix (`throughSlot=none` in the refusal), so only a scenario decided at its buy runs. Wallet + token qualification classifies the same refusal as `INCOMPLETE_CAPTURE` at the `MARKET` stage with reason `MARKET_COVERAGE_ENDED`, replay not run. v3 and v4 datasets carry no coverage and are never refused this way.

`referenceRates` holds `{minute, rate, source}` entries: a UTC minute (whole minute), a positive SOL/USDT rate as a decimal string, and its source (for captures, `binance:SOLUSDT:1m:open`). Every observation's minute must have a rate (`REFERENCE_RATE_MISSING` otherwise); a minute may appear only once, or with an equal rate. Only the dip-entry market-cap rule reads them ([decision](adr/20261008-reference-rates-in-dataset.md)).

`DatasetFingerprint` is the canonical checksum specification: deduplicate identical records, sort transactions by slot/signature/instruction observations by slot/ID and rates by minute, include each observation's `curveComplete` and all six curve fields as decimal integer strings (for v4 to v6 then its `trader`, `side` name, `solLamports` and `tokenRaw`; the v3 parts are unchanged), then for v5 and v6 each coverage entry ordered by base and quote mint as `coverage`, base mint, quote mint, `fromSlot` and `throughSlot` after the rates (for v6 followed by the entry's `throughTime` as an ISO-8601 instant, or an empty string when it has none), normalize decimals by stripping insignificant zeros, and hash each specified field as UTF-8 prefixed by its four-byte big-endian byte length. Times normalize through Instant. The algorithm includes schema version and tracked wallet. JSON whitespace, property order and duplicate delivery do not change the hash. Conflicting identities are rejected by the runner/catalog.

Engine `dip-paper-v4` (since `dip-paper-v2`) executes against each fill trade's post-trade `curve` ([bonding-curve execution decision](adr/20261009-bonding-curve-execution.md)); `liquidityQuote` stays in the format and the checksum but no longer affects a run. The configuration hash includes the strategy/version, every numeric model parameter (notional, observation age, dip, take profit, stop loss, minimum market cap, horizon, `latencySlots`, `transactionCostSol`), quote mint, scale/rounding, the token supply, the latency fill rule, the curve rule `curve-post-trade-floor-fees-ceil` and confirmed signer-buy scope; fee and slippage are no longer parameters. Each timed clock that is set is appended after those parts as `dipWindowMs=<ms>`, `maxHoldMs=<ms>` and `hardExitStartMs=<ms>`, in that order; an unset one adds nothing, so an untimed configuration (`SimulationConfig.mvp()` included) keeps its `dip-paper-v3` hash. The run ID combines engine version, dataset hash and configuration hash. The result checksum includes each cycle's P0, dip level, watch outcome and trigger, its ordered fill/rejection, selected observation, fill price, quantity, fee, fee basis points, transaction cost (`priorityFee`) and price impact, position state, take-profit and stop-loss levels, the position's exit start (an ISO-8601 instant, or an empty string without one), the exit trigger's observation, slot, time (or an empty string) and reason, and close economics including the close's fee basis points, transaction cost, price impact, reason and price assumption (`NEXT_TRADE` or `STANDING_CURVE`). The buy's block time `t0`, which the clocks count from, comes from the dataset and is covered by its checksum. Increment the engine version if simulation semantics change. `dip-paper-v3` checks the minimum market cap at the wallet's buy instead of at the dip ([decision](adr/20261023-reference-market-cap.md)). `dip-paper-v4` adds the optional block-time clocks, the `TIME_EXIT` sold against the curve standing at the exit start, and standing-curve sales of a take profit or stop loss with no trade within 60 s of its trigger (spec Section 8's active phase, an engine rule not in the configuration hash), which replace `NO_EXIT_FILL` ([decision](adr/20261024-timed-dip-window-and-exit.md)).

In-memory replay is stateless and repeatable. Persisted replay writes source outcomes, observations, intents, orders/fills and positions together with run/configuration/input/report snapshots in one transaction. Dataset/configuration/engine identity has a database unique constraint. Concurrent duplicate writers converge on the original run; a conflicting result checksum fails. Process restart and repeated input create no duplicate lifecycle records. Stored datasets are identified by their checksum: registering identical content again, under any name, returns the existing dataset, and each persisted run also registers its dataset.

A position without an exit within the horizon stays open with an explicit reason: `HORIZON_REACHED` (no exit, or one the recording cannot prove), `UNRESOLVED` (no curve could pay the sale) or `MIGRATED`, and `NO_EXIT_FILL` in runs of `dip-paper-v3` and earlier only. Observations after the horizon never close it. Runs are immutable experiments, not a live checkpoint stream. Adding new observations changes dataset/run identity.

Persistence supports exact microsecond source times, observed decimals fitting `numeric(78,38)`, and modeled quote amounts and quantities fitting `numeric(38,12)` and modeled prices fitting `numeric(78,38)`. It retains original observation decimal scales and rejects finer source timestamps or unrepresentable values rather than allowing PostgreSQL to round. Wallet events and observations are bounded to 100,000 records per run; intents, orders, fills and positions to 100,000, at most one cycle per buy.
