# Chart data: trade actors and market-cap valuation — milestone 3

Status: Delivered 2026-10-15 (backend Parts 1–3) — see Delivered below. Part 4 (frontend) is with Codex.

Milestone 3 of the approved [capture, paper and chart improvement plan](../20261011-capture-paper-chart-improvement-plan.md) (sections 9 and 10, work unit 6; milestones 1 and 2: [cases](../20261012-capture-paper-implementation.md), [throughput](20261014-capture-throughput.md)). It adds what the chart needs from the backend: the decoded trade actor, side and amounts of every observed trade, a market-cap valuation derived from the dataset's own rates, and a bounded chart read API. The frontend is a self-contained Codex brief (Part 4).

Out of scope: new venue decoders (PumpSwap after migration), the new strategy, verified circulating supply, live rates, any RPC download, any change to replay semantics.

## Decisions

- **The actor is the decoded `TradeEvent.user`**, not the fee payer or router. Each observation also keeps side (`isBuy`: 1 BUY, 0 SELL; anything else is already `MALFORMED_EVENT`), SOL amount in lamports and token amount in raw units, all u64. A transaction with several trade events yields one observation each, with its own actor.
- **Dataset schema v4 is new; v3 is untouched.** v3 datasets keep their bytes, checksums, meaning and load path, and their trade fields are unavailable (`trade == null`), never inferred. The loader accepts 3 and 4; every other version still fails `UNSUPPORTED_SCHEMA`.
- **v4 format.** Each observation has a required `trade` object: `trader` (base58 wallet), `side` (`BUY`/`SELL`), `solLamports` and `tokenRaw` (positive raw integer strings, at most u64). A v4 observation without `trade` is refused (`TRADE_FIELDS_MISSING`). A v3 file containing `trade` is refused (`TRADE_FIELDS_IN_V3`), so v3 meaning cannot be changed by editing. The v4 checksum appends the four trade values to each observation's parts; the v3 parts list is unchanged. The checksum already includes the schema version.
- **Export writes v4 only.** Full and case export re-extract from the verified local capture artifacts (no new RPC). v3 loading is kept; re-exporting a capture therefore gives a new checksum, dataset id and run ids, while old v3 datasets and runs stay valid. The observation `source` and `id` (`pumpfun-bonding-curve-trade/v1:...`) are unchanged so replay inputs are identical; only the extractor provenance enum gains `PUMPFUN_BONDING_CURVE_TRADE_V2`.
- **Replay does not change.** Engine `dip-paper-v2`, the configuration hash, the strategy and the fill rules are untouched. `ReplayRunner` merely accepts both versions. No code in strategy, papertrading or portfolio reads `trade`.
- **Persistence** is four additive nullable columns on `market_observations`: `trade_trader` varchar(44), `trade_side` varchar(8) (`@Enumerated(STRING)`), `trade_sol_lamports` numeric(20,0), `trade_token_raw` numeric(20,0). All set or all null. Earlier rows read back with `trade == null`. No index until a wallet-history query exists.
- **Valuation.** `valuation(t) = price(SOL/token) × SOL/USDT rate at that observation's UTC minute × 1,000,000,000` (`PumpFunLayout.TOKEN_SUPPLY`), rounded with `DecimalPolicy.scale` like `DipEntryStrategy`, so it equals `DipTrigger.marketCapUsd` for the same observation. Label: "total-supply valuation (FDV), USDT". It applies only to SOL-quoted observations whose `source` is the Pump.fun trade source. Pump.fun mints are 6 decimals by protocol; v4 also cross-checks `BondingCurve.price(solLamports, tokenRaw)` against the observation price, and a mismatch makes that point unavailable. Anything else is unavailable.
- **Rates come from the run's dataset `referenceRates`** (never live, never the Binance display source). A missing minute gives a null mcap for that point and a gap, never a neighbour or current rate. Each observation is converted before aggregation, so mcap high/low are the extremes of converted points. Backend `BigDecimal`; JSON decimal strings.
- **Chart API** is read-only in the console module: price and mcap candles, observed trade markers, PAPER fills as a separate list, and a `valuationBasis` block. The contract is in the Codex brief (single copy).
- **A run chart reads its dataset from the catalog** (`RunSummary.datasetId`) for rates and tracked wallet. If the dataset was deleted, price candles still return, mcap is `RATES_UNAVAILABLE` and no marker is flagged as the tracked wallet.

## Parts

1. `[impl] Trade fields: contract, domain and extraction` (marketdata + protocol). Owns `marketdata/domain/MarketObservation.kt`, new `marketdata/domain/ObservedTrade.kt` (with `TradeSide`), `marketdata/infrastructure/extraction/PumpFunTradeObservationExtractor.kt`, `marketdata/MarketHistoryDecodeResult.kt` (extractor enum), `protocol/pumpfun/PumpFunLayout.kt` (the private trade source constant moves here as `TRADE_SOURCE`), `marketdata/application/RecordedMarketHistoryService.kt` (reports the V2 extractor), new `marketdata/domain/TotalSupplyValuation.kt` (the only place the valuation formula lives), a helper for an observation's signature (the `sourceEventId` prefix only when it decodes as a 64-byte signature, else null) and the marketdata tests. `MarketObservation` gets `trade: ObservedTrade?` defaulting to null so existing constructors compile.
2. `[impl] Dataset v4, export and persistence` (replay + marketdata persistence; depends on 1). Owns `replay/domain/ReplayDataset.kt` (supported versions; current = 4), `replay/application/DatasetFingerprint.kt`, `replay/infrastructure/fixture/ReplayDatasetLoader.kt`, `ReplayDatasetWriter.kt`, `replay/application/ReplayRunner.kt` (version check only), `RecordedReplayExportService.kt`, `RecordedCaseReplayExportService.kt`, `replay/api/RecordedReplayCommands.kt` and `CaptureCaseExport.kt` (docs only), `marketdata/infrastructure/persistence/MarketObservationEntity.kt`, `marketdata/mapper/MarketObservationMapper.kt`, `marketdata/application/MarketObservationStoreService.kt` (reject a partial trade), new `docs/schema/V20261015120000__market_observation_trades.sql`, `docs/REPLAY_FORMAT.md`, the v3/v4 wording in `docs/REAL_DATA_CAPTURE.md` and `README.md`, and ADR `docs/adr/20261015-chart-trade-actors-and-mcap.md`. The writer writes v3 exactly as today (catalog snapshots use it) and v4 with `trade`. The SQL adds the four columns, an all-null-or-all-set check, side in (`BUY`,`SELL`) and both amounts in 1..18446744073709551615; applied by hand before deploying.
3. `[impl] Chart read API` (console; depends on 1, integration tests need 2). Owns `console/api/ChartController.kt` (thin: `GET /api/runs/{id}/chart`, `GET /api/datasets/{id}/chart`), `console/application/ChartConsoleService.kt`, `ChartBuckets`, `ChartValuation` (pure functions over `TotalSupplyValuation`), a shared `ConsolePairs` replacing `DatasetConsoleService.parsePair`, `console/domain/ChartView.kt`, `console/api/dto/ChartResponse.kt`, `console/mapper/ChartResponseMapper.kt` (MapStruct), the additive `trade` object on `ObservationResponse` and `ObservationResponseMapper`, and console tests. Paper fills come from `ConsoleService.detail` (fill and close observations), never recomputed; the dataset chart has none. Work is in memory over at most `ReplayLimits.MAX_INPUT_RECORDS` observations; only the response is bounded.
4. `[doc] Codex frontend brief` (last section; handed to the user). No Claude agent edits `frontend/`.
5. `[review] Touched changes` of Parts 1 to 3 (numeric correctness, determinism, v3 compatibility, module boundaries, schema rules).

Order: after milestone 2 is committed (both touch `RecordedReplayExportTest`), Part 1, then Parts 2 and 3 (disjoint files; sequenced builds), then Part 5. Part 4 is ready now.

## Acceptance checks

- For fixture `marketdata/pumpfun-trade-453004997.json` the observation's `trade.trader` equals the `TradeEvent.user` read by `PumpFunInstructionReader` (not the fee payer when they differ); side and both amounts equal the event's. A transaction with two trade events gives two observations with their own actors.
- The bundled v3 sample still loads with checksum `06103b0f...eac765`; re-writing it adds no `trade` key; its trade fields read as unavailable; a v3 file with a `trade` key is refused.
- A v4 file loads; changing any trade field changes the checksum (a mismatch is refused); a v4 observation without `trade` is refused.
- Same replay result v3 vs v4: the sample's v3 dataset and a v4 twin (same observations plus synthetic trade fields) have equal `engineVersion` and `configurationHash` and equal economics for every scenario (watch, trigger, fill, position, close, exit-unavailable — all result parts except `runId` and `intent.id`, which include the dataset hash). The pinned v3 run checksums pass unchanged.
- Full and case export of the same verified capture write v4, load back and carry each trade's trader; exporting twice gives identical bytes.
- Persistence round trip: a v4 observation stored and read back is equal; a legacy row with null columns reads `trade == null`; a partial trade is refused on write.
- Mcap = price × rate × 1e9 for a known point (0.00000002 SOL × 150.5 = 3010.000000000000) and equals the run's `DipTrigger.marketCapUsd` for the sample's trigger observation.
- A candle's mcap high is the maximum of the converted points, differing from price high × one rate when the rate changes inside the bucket (two-minute test).
- A point without a rate minute gives null mcap and counts in `mcapMissingPoints`; a bucket with no rated point has `mcap: null`. A non-Pump.fun source, non-SOL quote or a failed v4 cross-check gives unavailable. A run whose dataset was deleted gives `RATES_UNAVAILABLE`.
- Bucket cap: 5,000 observations over 10 days with `maxBuckets=5000` return at most 2,000 candles; `bucketSeconds` is the smallest fixed-ladder value that fits.
- Markers: over the limit the response is clustered, `items` holds the tracked wallet's trades and, summed over all pages, `items + Σ cluster counts == total`; at or under the limit every trade is an item. `isTrackedWallet` is true exactly for the dataset's tracked wallet. v3 data returns `markers.available=false`.
- Run-chart paper fills have `kind=PAPER`, are absent from observed markers and match the run's fill and close observations; the dataset chart has none.
- Existing `/observations` responses keep every field and gain only the nullable `trade`.

## Verification

Test-first, no network. Targeted: new `TradeActorExtractionTest`, `CurveStateExtractionTest`, `RecordedMarketHistoryTest`, `MarketObservationMapperTest`, a store round trip (Testcontainers), `ReplayDatasetLoaderTest`, `ReplayDatasetWriterTest`, fingerprint tests, new `ReplayV3V4EquivalenceTest`, `RecordedReplayExportTest`, `ReplayDatasetCatalogIntegrationTest`, new `ChartValuationTest`, `ChartBucketsTest`, `ChartConsoleServiceTest` and a chart MockMvc test, then `./gradlew build`. Frontend (Codex): `npm test`, `npm run lint`, `npm run build` in `frontend/`.

## Schema and compatibility

One additive database change: the four nullable columns, with manual prod SQL `docs/schema/V20261015120000__market_observation_trades.sql` applied before deploying (prod validates; local/test add them through `update`). Existing rows, v3 dataset snapshots, run snapshots, run ids and result checksums are unchanged. The run `dataset_snapshot` column is a Jackson dump and gains a `trade` property; it is not hashed or loaded as a dataset. New exports are v4; REPLAY_FORMAT.md documents both. New API paths and the extra `trade` field are additive.

## Part 4 — Codex brief

Moved to [20261015-chart-frontend-codex-brief.md](../20261015-chart-frontend-codex-brief.md) so it has a stable path while the frontend work is open.

## Delivered

2026-10-15, commits `21d59d2` (Parts 1–2), `a00471f` (Part 3) and the review-fix commit that archived this plan.

- Shipped: trade actor, side and amounts on every Pump.fun trade observation (extractor `PUMPFUN_BONDING_CURVE_TRADE_V2`); `TotalSupplyValuation` and `ObservationSignature`; dataset schema v4 (v3 loads unchanged, v3 checksums and bytes pinned); full and case export write v4 only (new refusal codes `TRADE_FIELDS_IN_V3`, `TRADE_FIELDS_MISSING`, `TRADE_FIELDS_INVALID`); four nullable `trade_*` columns; `GET /api/runs/{id}/chart` and `GET /api/datasets/{id}/chart`; nullable `trade` on `/observations`.
- Differences from the plan: per-candle `mcapUnsupportedPoints` added (review finding) so partially unsupported buckets explain their gaps; the first-page cluster invariant holds over all pages, not always on page one (brief reworded); `runChart` reads run observations once through `ConsoleService.detailWithObservations`; the export Gradle task still defaults to the file name `recorded-replay-v3.json` although the content is v4.
- Review: one `backend-reviewer` pass (no blockers, two majors where the API differed from the brief, minors); all fixed in one fix part.
- Docs updated: `docs/REPLAY_FORMAT.md` (v3 and v4), `docs/REAL_DATA_CAPTURE.md`, README, ADR `docs/adr/20261015-chart-trade-actors-and-mcap.md`, the Codex brief.
- Schema impact: four additive nullable columns on `market_observations`; production must apply `docs/schema/V20261015120000__market_observation_trades.sql` by hand before deploying (local/test create them through `ddl-auto: update`). Nothing destructive; re-exporting a capture gives a new dataset id and new run ids, old v3 datasets and runs stay valid.
- Verification: `./gradlew build` ran 945 tests; 868 pass and 77 fail only because Docker is unavailable in the agent container (19 Testcontainers classes, including the new `ChartApiIntegrationTest` and the v4 persistence round trip). Those still need a local run with Docker.
