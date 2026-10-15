# Replay dataset v3 and v4

Two versions are supported side by side, and they differ in one thing: v4 adds each observation's decoded `trade` (the actor, side and amounts of the Pump.fun trade behind it). Everything else in this document applies to both unless it says otherwise.

| | v3 | v4 |
|---|---|---|
| `schemaVersion` | 3 | 4 |
| Observation `trade` object | must be absent (`TRADE_FIELDS_IN_V3`) | required (`TRADE_FIELDS_MISSING`, `TRADE_FIELDS_INVALID`) |
| Loaded `MarketObservation.trade` | always null: unavailable, never inferred | the decoded trade |
| Checksum observation parts | as listed under `DatasetFingerprint` below | the same parts, then `trader`, `side`, `solLamports`, `tokenRaw` |
| Written by | no export since 2026-10-15; bundled sample, older files and catalog snapshots of them | `exportRecordedReplay` and the case export |
| Replay result | `dip-paper-v2` | the same engine; no strategy, fill or exit rule reads `trade` |

The loader accepts 3 and 4; any other version is refused with `UNSUPPORTED_SCHEMA`. A v3 file keeps its bytes, checksum, run ids and meaning, and re-writing a loaded v3 dataset (the catalog snapshots do) reproduces the v3 form exactly, without `trade`.

See the bundled sample `src/main/resources/replay/samples/dip-sample-v3.json`. This is synthetic curated data with one token per dip-entry outcome, not a raw Solana or provider capture. The synthetic zero wallet/signature is not the Decu identity. Its curve states are synthetic too (see below).

Version 3 replaced version 2 on 2026-10-09 with the [bonding-curve execution model](plans/archive/20261009-bonding-curve-execution.md). It adds each observation's full curve state: the reserves after its trade and the fee basis points the program charged. Version 2 had replaced version 1 on 2026-10-08 with the [dip-entry strategy](adr/20261008-dip-entry-strategy.md), adding a SOL/USDT rate per observation minute and `curveComplete`. The loader refuses versions 1 and 2 (`UNSUPPORTED_SCHEMA`); re-export from the captures instead, which already hold every curve field. Version 4 followed on 2026-10-15 ([decision](adr/20261015-chart-trade-actors-and-mcap.md)): it adds the trade fields below so a chart can show who traded, and re-exporting a capture as v4 gives a new checksum and dataset id because the checksum covers the schema version and the trades. Old v3 datasets and their runs stay valid.

The additive [real history capture](REAL_DATA_CAPTURE.md) saves raw transaction evidence and a separately versioned manifest. Those files are not replay datasets and cannot be uploaded as such. `exportRecordedReplay` converts a verified wallet capture, its market capture and their rate capture into a v4 dataset through `ReplayDatasetWriter`, re-extracting the trades from the verified local artifacts (no RPC), and loads the result back before writing it. Neither the capture nor the export alters this format, its checksum or simulation semantics.

The root has `schemaVersion` (3 or 4), `trackedWallet`, `transactions`, `observations`, `referenceRates` and a SHA-256 `checksum`. Transactions and observations are bounded to 100,000 records each. Times must be exact at microsecond precision, prices and liquidity must fit `numeric(78,38)`, `source` is at most 128 characters and observation `id`/`sourceEventId` at most 255; the loader rejects anything persistence would. Addresses must decode from base58 to 32 bytes, signatures to 64 bytes. Slots are nonnegative Longs. Monetary values and raw token deltas are decimal strings, never floating-point JSON numbers. Decimals are 0..255; accepted absolute raw amounts fit u64.

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

`referenceRates` holds `{minute, rate, source}` entries: a UTC minute (whole minute), a positive SOL/USDT rate as a decimal string, and its source (for captures, `binance:SOLUSDT:1m:open`). Every observation's minute must have a rate (`REFERENCE_RATE_MISSING` otherwise); a minute may appear only once, or with an equal rate. Only the dip-entry market-cap rule reads them ([decision](adr/20261008-reference-rates-in-dataset.md)).

`DatasetFingerprint` is the canonical checksum specification: deduplicate identical records, sort transactions by slot/signature/instruction observations by slot/ID and rates by minute, include each observation's `curveComplete` and all six curve fields as decimal integer strings (for v4 then its `trader`, `side` name, `solLamports` and `tokenRaw`; the v3 parts are unchanged), normalize decimals by stripping insignificant zeros, and hash each specified field as UTF-8 prefixed by its four-byte big-endian byte length. Times normalize through Instant. The algorithm includes schema version and tracked wallet. JSON whitespace, property order and duplicate delivery do not change the hash. Conflicting identities are rejected by the runner/catalog.

Engine `dip-paper-v2` executes against each fill trade's post-trade `curve` ([bonding-curve execution decision](adr/20261009-bonding-curve-execution.md)); `liquidityQuote` stays in the format and the checksum but no longer affects a run. The configuration hash includes the strategy/version, every numeric model parameter (notional, observation age, dip, take profit, stop loss, minimum market cap, horizon, `latencySlots`, `transactionCostSol`), quote mint, scale/rounding, the token supply, the latency fill rule, the curve rule `curve-post-trade-floor-fees-ceil` and confirmed signer-buy scope; fee and slippage are no longer parameters. The run ID combines engine version, dataset hash and configuration hash. The result checksum includes each cycle's P0, dip level, watch outcome and trigger, its ordered fill/rejection, selected observation, fill price, quantity, fee, fee basis points, transaction cost (`priorityFee`) and price impact, position state, take-profit and stop-loss levels, exit trigger and close economics including the close's fee basis points, transaction cost and price impact. Increment the engine version if simulation semantics change.

In-memory replay is stateless and repeatable. Persisted replay writes source outcomes, observations, intents, orders/fills and positions together with run/configuration/input/report snapshots in one transaction. Dataset/configuration/engine identity has a database unique constraint. Concurrent duplicate writers converge on the original run; a conflicting result checksum fails. Process restart and repeated input create no duplicate lifecycle records. Stored datasets are identified by their checksum: registering identical content again, under any name, returns the existing dataset, and each persisted run also registers its dataset.

A position without an exit within the horizon stays open with an explicit reason (`HORIZON_REACHED`, `NO_EXIT_FILL` or `MIGRATED`); observations after the horizon never close it. Runs are immutable experiments, not a live checkpoint stream. Adding new observations changes dataset/run identity.

Persistence supports exact microsecond source times, observed decimals fitting `numeric(78,38)`, and modeled quote amounts and quantities fitting `numeric(38,12)` and modeled prices fitting `numeric(78,38)`. It retains original observation decimal scales and rejects finer source timestamps or unrepresentable values rather than allowing PostgreSQL to round. Wallet events and observations are bounded to 100,000 records per run; intents, orders, fills and positions to 100,000, at most one cycle per buy.
