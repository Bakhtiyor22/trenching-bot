# Console capture, run and results experience: backend plan

> Backend support for the redesigned console: a wallet activity API to pick a token, unique readable capture names, newest-first lists with one status/progress/next-step summary, an agreed-strategy run preset with honest gaps, percentages for every run setting, links from a run back to its capture, an API usage dashboard and a dedicated chart page.

Status: Approved 2026-10-16. Every open question takes its recommendation: sells listed from the accumulator and labelled possibly incomplete; no token names; `DECIDED` cutoff unchanged, with a caveat; `horizonSlots` stays 4,500 flagged `MISSING`; CLI usage not counted; no retention pruning; decu-dip v2 approval is a separate decision.

Delivery (2026-10-16, branch `claude/tender-heisenberg-bal0d6`): units 1–3 and 5–7 in `815fec7` (on `develop`), unit 4 (wallet activity) and unit 8 (source links, timeline, chart extent) on the branch, unit 9 docs with them: README API, configuration and usage sections; [ADR](../adr/20261016-agreed-strategy-run-preset.md) for the preset; capture console ADR addendum; `REAL_DATA_CAPTURE.md` wallet activity section; handoff contract confirmed. Deviations: `presetId` is logged, not stored (rule 19); preset decimals keep their scale-12 strings (`notional` `"0.100000000000"`); a `NAME_TAKEN` suggestion keeps the casing the user typed; `datasetName` in `source` is null once the dataset is deleted, and `source` is on dataset detail only. Archive this plan after the user's review.

Design (Claude Design canvas, 7 pages): https://claude.ai/artifact/UKXHQHKc89nvEM5K5XUAM4. The frontend work is handed to Codex in [20261016-console-capture-run-ux-codex-handoff.md](20261016-console-capture-run-ux-codex-handoff.md); this plan defines the HTTP contract it consumes.

## Context

What the user sees today (checked in the running console on 2026-10-16, read-only):

| Problem | Cause in code |
|---|---|
| Captures appear in random order | `CaptureFileStore.sessions()` (`console/capture/CaptureFileStore.kt:79`) sorts **session ids** (request UUIDs) lexicographically; `CaptureSessionService.page` keeps that order. |
| Names repeat (`runitback` ×2, `gigapump` ×2) and many are throwaway (`asddsaadasasdasdasdas`) | `CaptureSessionService.create` only checks 1–120 characters; nothing suggests or enforces a unique name. |
| "INSPECT · SUCCEEDED" says nothing about the capture | The list shows the *latest operation*; the overall state (which step, how far, what to do next) is spread over `inspection.wallet/market/rates`, `operation`, `recovery` and case views, and the frontend reassembles it. |
| Picking a token means pasting a mint | The wallet + token scope (`CaptureWalletSettings.mint`) requires a mint; no API lists a wallet's recent actions. Decu's address must be typed every time. |
| Run settings are in basis points | `SimulationConfig`, `ConfigResponse` and `RunStartRequest` use `dipBps`, `takeProfitBps`, `stopLossBps`; fills show `feeBps`, `priceImpactBps`. |
| New runs default to old values | `RunLaunchService.defaults()` returns `SimulationConfig.mvp()` (15% dip, +30% / −15%, 4,500 slots), not the agreed strategy. |
| Run results have no way back to the capture | `ReplayDatasetEntity` has no capture reference; only `CaptureSessionRecord.datasetId` / `CaptureCaseRecord.datasetId` point forward. |
| The post-run chart is cramped | The chart is a panel inside `RunDetailPage`; `GET /api/runs/{id}/chart` already supports `from`, `to`, `maxBuckets`, `markerLimit`, `cursor`, but reports no data extent and scenarios carry slots only, no instants. |
| API usage is invisible | Request counts exist only per capture operation (`CaptureBudget`, `CaptureRecoveryRecord.requestsUsed`) and in INFO log lines; console Solana probes and Binance calls (`BinanceSolUsdtRateSource`: `/api/v3/klines`, `/api/v3/ticker/price`) are not counted anywhere. |

### The agreed run strategy (recovered, not invented)

The agreed strategy is **`decu-dip` v2**, [docs/strategy/decu-dip-v2-spec.md](../strategy/decu-dip-v2-spec.md) (last updated 2026-10-11, "single source of truth"). Its engine is planned in [20261015-decu-dip-v2-replay.md](20261015-decu-dip-v2-replay.md), still **Draft for approval** and not implemented (no `DecuDip*` class exists). The only runnable engine is `dip-entry` v1 (`dip-paper-v2`). So the preset maps the spec onto `dip-entry` v1 field by field and marks what it cannot express:

| `dip-entry` v1 field | Preset value | Spec source | Status |
|---|---|---|---|
| `dipBps` | 2000 (20%) | P6 | `AGREED` |
| `takeProfitBps` | 5500 (+55%) | P9 | `AGREED` |
| `stopLossBps` | 4000 (−40%) | P10 | `AGREED` |
| `notional` | 0.1 SOL | P12 | `AGREED` (basis: curve payment, fees on top, as `FillModel` does; spec-plan Assumption 4 still open) |
| `transactionCostSol` | 0.001 | `entryPriorityFeeSol` | `AGREED` (network fee not added; spec-plan Assumption 2 proposes 0.000005 extra) |
| `quoteMint` | wrapped SOL | P15 | `AGREED` |
| `minMarketCapUsd` | 10000 | P5 | `AGREED_MEASURED_DIFFERENTLY` (spec: at the reference buy, strictly greater; v1: at the dip, `>=`) |
| `latencySlots` | 1 | none (replay latency is an assumption, spec-plan Assumption 2) | `ASSUMPTION` |
| `horizonSlots` | 4500 (engine default, kept) | none: spec has a 30 s dip window and an exit starting at `min(entry + 3 min, t0 + 4 min)`; v1 has one horizon for both and no timed exit | **`MISSING`** |
| `maxAgeSlots` | 2 (engine default) | none | `ENGINE_DEFAULT` |

Spec rules `dip-entry` v1 cannot apply (8), listed on every preset response: P2 first-ever buy, P3 signal over 1.9 SOL, P5 measured at the reference buy, P7 30-second dip window, P8 15% entry slippage, P11 timed exit, Section 8 exit retries and `UNRESOLVED`, P13 one position including in-flight entries. The preset never claims these.

## Scope

### In scope

1. Wallet activity API: a wallet's recent Pump.fun buys and sells, paged, with annotations for picking a token.
2. Suggested wallet (Decu) and recent wallets in the capture form config.
3. Capture names: generated, unique, editable; legacy duplicates disambiguated on read.
4. Capture list: newest first by default, sortable, filterable by status, with status counts.
5. One capture summary (status, reason, current step, progress, next action) computed on the backend.
6. Recommendations for capture setup and run configuration.
7. The agreed-strategy run preset (`GET /api/runs/preset`) with per-field status and unsupported rules.
8. Percentages: percent strings beside every bps value in responses; run start accepts percent strings.
9. Source links: run and dataset responses name their capture and case.
10. Chart page support: data extent on chart responses, instants on run scenarios.
11. API usage tracking: counted at the transport, stored in 5-minute buckets, reported per provider, call, task and capture.

### Out of scope

- Any file under `frontend/` (Codex, via the handoff).
- The `decu-dip` v2 engine itself (its own Draft plan). This plan only labels its absence.
- Changing `SimulationConfig.mvp()`, `ConsoleConfigValidator` semantics, config hashing, run ids, dataset formats or the replay engine.
- Automatic chaining of capture steps; token name/symbol lookup (Open Question 2); counting usage from Gradle CLI commands (Open Question 5); billing-credit estimates.
- Changing which configuration the `DECIDED` market cutoff uses (Open Question 3).

## Key Decisions

1. **Owners.** `walletactivity` owns recent activity (decoding and annotation of the wallet's own trades); a new feature module `apiusage` owns usage recording and reporting; `console` owns everything else (it already owns captures, names, run launch and chart DTOs). `chaincapture`, `marketdata` (Binance adapter) and `console/solana` depend on `apiusage`'s public recorder port. No other module changes its public contract.
2. **No contract break.** Every HTTP change is additive: new endpoints, new optional request fields, new response fields. `RunStartRequest` without `config`, or with bps fields only, behaves exactly as today. `GET /api/config/defaults` still returns `mvp()` values (plus percents); the form loads the preset from a separate endpoint, so CLI/API callers and `DECIDED` cutoffs see no change.
3. **Preset is code, pinned to the spec by a test.** `AgreedStrategyPreset` is a constants object in `console/domain`. `AgreedStrategyPresetTest` parses the YAML block of `docs/strategy/decu-dip-v2-spec.md` and fails if `dipBps`, `takeProfitBps`, `stopLossBps`, `notionalSol`, `minReferenceMcapUsdExclusive` or `entryPriorityFeeSol` drift. The backend never reads `docs/` at runtime. `horizonSlots` is returned as the engine default with status `MISSING` and is never chosen by the backend.
4. **Percent conversion is exact and server-authoritative.** Stored and hashed values stay integer bps, so run ids, `configurationHash` and stored runs are unchanged. Responses add `…Percent` decimal strings; requests may send `…Percent` instead of `…Bps`. Rejected alternative: frontend-only conversion. The backend already validates exact decimals, and one tested converter avoids two implementations drifting.
5. **Wallet activity source = the wallet's Pump.fun user volume accumulator listing** (`VolumeAccumulatorAddress`, the `pumpfun-user-buys` scope). The wallet's own listing is mostly other bots' transactions (REAL_DATA_CAPTURE: 1 of the latest 100 signed). Decu's accumulator listing already contained a `sell_v3` in the user's `qwertyu` capture, so sells appear there too. Completeness for sells is not proven (Open Question 1). Each listed transaction is read with `getTransaction` through the shared limiter and `SharedTransactionCache`, and every Pump.fun `TradeEvent` whose `user` is the wallet becomes one action.
6. **Capture summary is derived, not stored.** A pure `CaptureStatusRules` turns a `CaptureSessionView` plus its case views into `CaptureSummary`. Captures stay file-based; no session file format changes beyond an optional `nameKey`.
7. **Name uniqueness is enforced in the file store's lock**, case-insensitive after trim and whitespace collapse. Captures are local-only files in one process, so there is no database to hold a unique constraint (strict rule 18 applies to database identity; recorded here as the deliberate exception). Legacy duplicates are never renamed on disk; reads add ` (2)`, ` (3)` by `createdAt`, then id.
8. **API usage is counted where the HTTP request is sent**, so retries, discovery pages and probes are all included. It is held in memory and flushed every 15 s as additive upserts into 5-minute UTC buckets. Rejected: per-call row inserts (10 req/s of writes for no benefit) and Micrometer only (lost on restart, no per-capture view).
9. **Usage units are fixed and named in every response:** `requests` = HTTP requests sent (retries included), `failedRequests` = transport errors, non-2xx other than 429, and JSON-RPC errors, `rateLimitedResponses` = HTTP 429 replies, `responseBytes` = response body bytes received, `cacheHits` = `getTransaction` answers served by `SharedTransactionCache` with no request. Periods are explicit `from`/`to` instants. They are not billing credits.

## Module Contracts

### `apiusage` (new module, `com.trenching.bot.apiusage`)

Public:
- `enum class ApiProvider { SOLANA_RPC, BINANCE, TELEGRAM }`. `TELEGRAM` is reserved for the notification adapter, which records nothing today.
- `enum class ApiTask { WALLET_STEP, MARKET_STEP, RATES_STEP, CASE_RATES, WALLET_ACTIVITY, CONNECTION_CHECK, LIVE_PRICE, UNATTRIBUTED }`
- `data class ApiUsageContext(val task: ApiTask, val captureId: String?)`. `captureId` matches `[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}` or is null.
- `data class ApiCall(val provider: ApiProvider, val operation: String, val context: ApiUsageContext, val outcome: ApiCallOutcome, val responseBytes: Long)`. `operation` is the JSON-RPC method or the Binance path (`/api/v3/klines`), 1–64 characters, never a URL, query string or key.
- `enum class ApiCallOutcome { OK, FAILED, RATE_LIMITED, CACHE_HIT }`
- `fun interface ApiUsageRecorder { fun record(call: ApiCall) }`, with `ApiUsageRecorder.NONE` for tests and CLI processes.
- `ApiUsageReports` (query service contract): `fun report(period: ApiUsagePeriod, captureId: String?): ApiUsageReport`
- `enum class ApiUsagePeriod(val duration: Duration?) { LAST_HOUR, LAST_24_HOURS, LAST_7_DAYS, ALL }`
- `ApiUsageReport(from: Instant, to: Instant, bucketSeconds: Int, recordingStartedAt: Instant?, totals: ApiUsageTotals, byCall: List<ApiCallUsage>, byTask: List<ApiTaskUsage>, series: List<ApiUsagePoint>, pacing: List<ApiPacing>)`
- `ApiCallUsage(provider, operation, purpose: ApiPurpose, totals: ApiUsageTotals, sharePercent: String, lastCallAt: Instant?)`; `ApiPurpose` is a closed catalog keyed by `(provider, operation)` (table below), `UNKNOWN` otherwise.
- `ApiUsageTotals(requests: Long, failedRequests: Long, rateLimitedResponses: Long, responseBytes: Long, cacheHits: Long)`
- `ApiPacing(provider, minRequestIntervalMillis: Long, maxConcurrentRequests: Int)` (from non-secret `SolanaRpcSettings`; never the endpoint).

Internal: `application/ApiUsageAccumulator` (thread-safe deltas), `application/ApiUsageFlusher` (single-thread scheduled executor, 15 s, plus flush on `@PreDestroy`), `application/ApiUsageReportService`, `infrastructure/persistence/ApiUsageBucketEntity` + `ApiUsageBucketRepository`, `mapper/ApiUsageMapper`.

Purpose catalog (`ApiPurpose`, one plain sentence each, returned as a code; the frontend holds the copy):

| Provider · operation | Purpose code |
|---|---|
| RPC `getTransaction` | `READ_TRANSACTION` (full transaction for wallet buys, market trades and first-buy checks) |
| RPC `getSignaturesForAddress` | `LIST_SIGNATURES` (a wallet's, accumulator's or curve's transactions, up to 1,000 per page) |
| RPC `getGenesisHash` | `MAINNET_CHECK` |
| RPC `getAccountInfo` | `MINT_PROGRAM_CHECK` |
| RPC `getHealth`, `getVersion`, `getSlot` | `CONNECTION_CHECK` |
| Binance `/api/v3/klines` | `MINUTE_RATES` |
| Binance `/api/v3/ticker/price` | `LIVE_RATE` |

### `chaincapture` (changed internals, no public break)

- `CaptureBudget` gains `val usage: ApiUsageContext = ApiUsageContext(ApiTask.UNATTRIBUTED, null)`. Every existing constructor call compiles unchanged.
- `HttpSolanaReadRpc` takes an `ApiUsageRecorder` (default `NONE`) and records one `ApiCall` per HTTP send, including each retry, with the budget's context. `CachingSolanaReadRpc` records `CACHE_HIT` for cache-served reads. `CaptureRpcs.create` gains a `recorder` parameter (default `NONE`).
- `CaptureOperationService` / `CaptureCaseService` build budgets with the right `ApiTask` and the session id.

### `marketdata` and `console/solana`

- `BinanceSolUsdtRateSource` and `JsonRpcSolanaProbe` record calls (`MINUTE_RATES` with `RATES_STEP`/`CASE_RATES` when called from a capture, otherwise `LIVE_RATE`/`LIVE_PRICE`; probes with `CONNECTION_CHECK`).

### `walletactivity` (adds to its public contract)

- `data class WalletActivityRequest(val wallet: WalletAddress, val limit: Int, val before: TransactionSignature?)`, `limit` 1–50, default 20.
- `sealed interface WalletActivityResult { data class Found(page: WalletActivityPage); data class Unavailable(reason: WalletActivityUnavailable, retryAfter: Duration?) }`, with `WalletActivityUnavailable { RPC_NOT_CONFIGURED, PROVIDER_RATE_LIMITED, PROVIDER_UNAVAILABLE, NOT_MAINNET }`.
- `data class WalletActivityPage(wallet, actions: List<WalletAction>, unreadable: Int, scanned: Int, nextBefore: TransactionSignature?, requestsUsed: Int, source: String = "pumpfun-user-volume-accumulator")`
- `data class WalletAction(signature, instructionIndex: Int, innerIndex: Int?, slot: Slot, blockTime: Instant?, side: TradeSide, mint: MintAddress, solLamports: BigInteger, tokenRaw: BigInteger, tokenDecimals: Int = 6, venue: String = "PUMPFUN_CURVE")`
- `fun interface WalletActivityLookup { fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult }`, implemented by `application/RecentWalletActivityService`, which reuses `PumpFunInstructionReader` / `TradeEvent` (protocol) unchanged.

### `console` (HTTP, all additive)

New or extended endpoints, with full field lists in the handoff document:

| Method · path | Purpose |
|---|---|
| `GET /api/wallets/{address}/activity?limit=&before=&refresh=` | Recent actions with annotations: `earlierActionInPage`, `capturedIn: [{captureId, displayName}]`, `overSignalThreshold` (curve payment > 1.9 SOL, with `signalBasis = CURVE_PAYMENT_EXCLUDING_FEES`), plus `cachedAt` when served from the cache. Results are cached in memory for 60 s per `(wallet, limit, before)`; `refresh=true` bypasses the cache. |
| `GET /api/captures/config` (extended) | `suggestedWallets: [{label, address, reasonCode}]` from `trenching.console.suggested-wallets`; `recentWallets: [{address, label?, lastUsedAt}]` (up to 5, from sessions). |
| `GET /api/captures?page&size&sort&status` (extended) | `sort` = `createdAt,desc` (default) \| `createdAt,asc` \| `name,asc` \| `attention`; `status` filter; response adds `statusCounts: {status: count}` across all sessions. Every item gains `displayName` and `summary`. |
| `GET /api/captures/{id}` (extended) | Adds `displayName` and `summary`. |
| `POST /api/captures` (extended) | `name` becomes optional. Blank means generated. Adds optional `timeZone` (IANA, default `UTC`) for the generated time part. A taken name returns 409 `NAME_TAKEN` with `suggestion`. |
| `GET /api/captures/names/check?name=` | `{available, normalized, suggestion?}` for live validation. |
| `PATCH /api/captures/{id}` | `{requestId, name}` rename, with the same rules. Idempotent per `requestId`. |
| `GET /api/captures/recommendations?wallet=&scope=&mint=` | Capture-setup recommendations. |
| `GET /api/runs/preset` | The agreed-strategy preset. |
| `GET /api/runs/recommendations?datasetId=` | Run-setup recommendations. |
| `GET /api/config/defaults` (extended) | Percent strings beside bps in `defaults` and `limits`. |
| `POST /api/runs` (extended) | `config.dipPercent`, `takeProfitPercent`, `stopLossPercent` (strings) accepted instead of the bps fields; optional `presetId`, validated and logged (see rule 19). |
| `GET /api/runs/{id}` (extended) | `config` percents; `source: {datasetId, datasetName, captureId?, captureDisplayName?, caseId?}`; each scenario gains `timeline: {firstBuyAt, dipAt?, entryAt?, exitTriggerAt?, exitAt?, horizonEndSlot}` (instants are observation block times) and fill/close `feePercent`, `priceImpactPercent`. |
| `GET /api/datasets/{id}` (extended) | `source` as above. |
| `GET /api/runs/{id}/chart`, `GET /api/datasets/{id}/chart` (extended) | `extent: {firstTime, lastTime, firstSlot, lastSlot}` of the pair's observations, independent of `from`/`to`. |
| `GET /api/usage?period=&captureId=` | The usage report. |

`CaptureSummary` (console DTO and domain):

- `status`: `NOT_STARTED`, `RUNNING`, `WAITING_RETRY`, `PAUSED`, `STOPPED_LIMIT`, `STOPPED_DEADLINE`, `NEEDS_ATTENTION`, `READY_TO_REPLAY`, `DONE`, `CANCELLED`
- `reasonCode`: the existing stage or operation reason, e.g. `REQUESTS`, `MARKET_DETAILS_PENDING`, `PROVIDER_UNAVAILABLE`
- `currentStep`: `{index: 1..4, stage: WALLET|MARKETS|RATES|DATASET}`; `steps: [{stage, state: DONE|ACTIVE|STOPPED|FAILED|TODO}]`
- `progress`: `{processed, selected, percent}`, present only when `selected > 0` is known. `percent` is a string with scale 0, rounded `FLOOR`, so 100 appears only when complete.
- `nextAction`: `{kind, stage?, attemptId?, caseId?, maxRequests?}`, with `kind` ∈ `START_WALLET`, `RESUME_WALLET`, `RETRY_WALLET`, `START_MARKETS`, `RESUME_MARKETS`, `CAPTURE_RATES`, `REGISTER_DATASET`, `START_RUN`, `VIEW_RESULTS`, `INSPECT`, `WAIT`, `NONE`
- `cases`: `{total, ready, blocked, downloading}`
- `lastProgressAt`

Recommendation DTO: `{id, appliesTo: CAPTURE|RUN, setting: {field, value}?, facts: {name: value}, caveatCode?}`. Ids:

- Capture: `SCOPE_WALLET_TOKEN`, `CUTOFF_DECIDED` (caveat `CUTOFF_USES_LEGACY_DEFAULTS`), `ENABLE_RECOVERY` (facts: default totals), `DECU_PUMPFUN_SCOPE`
- Run: `USE_AGREED_STRATEGY`, `ADD_LEGACY_COMPARISON`, `SINGLE_TOKEN_DATASET`

The frontend holds the copy for each id.

## Domain Rules

### Wallet activity

1. Only successful transactions from the listing are read. Failed listing entries are counted in `scanned` and never fetched, as in wallet capture.
2. An action is a Pump.fun `TradeEvent` with `user == wallet`. One transaction with two such events yields two actions, ordered by `(slot desc, signature, instructionIndex, innerIndex)`. Identity is `(signature, instructionIndex, innerIndex)`, so a re-fetch never duplicates.
3. A transaction with no decodable wallet `TradeEvent` (PumpSwap, unknown layout, `NON_SOL_QUOTE`) increments `unreadable` and yields no action. It is never guessed.
4. `solLamports` and `tokenRaw` are `BigInteger` from the event; SOL display is lamports/10⁹ at scale 9, exact. `overSignalThreshold` = `solLamports > 1_900_000_000`, strictly; exactly 1.9 SOL is false. It is a curve-payment reading, labelled as such, and never decides a run (spec-plan Assumption 1 is open).
5. `earlierActionInPage` is true when the same mint has an older action in the same page. It is never presented as first-buy proof; proof stays with the wallet + token capture (`ActualFirstBuyRule`).
6. The request cost is bounded: 1 listing page of at most `max(limit × 3, 100)` entries + at most `limit` `getTransaction` calls (cache hits are free) + 1 genesis check per process lifetime, at most 152 requests. `requestsUsed` reports the actual number. The budget is enforced by a `CaptureBudget` with `maxRequests` = that bound.
7. Negative cases: an invalid base58 address returns 400 `INVALID_ADDRESS`. A blank `SOLANA_RPC_URL` returns 409 `RPC_NOT_CONFIGURED` (variable name only). A 429 returns 429 `PROVIDER_RATE_LIMITED` with `retryAfterSeconds` from `Retry-After` when present. A timeout or 5xx returns 502 `PROVIDER_UNAVAILABLE`. A non-mainnet genesis returns 409 `NOT_MAINNET`. An empty listing returns 200 with `actions = []`, `nextBefore = null`. `before` unknown to the provider returns 200 with an empty page.

### Names, ordering, summary

8. Generated name = `{label} · {mintShort} · {d MMM HH:mm}` in the request's zone, e.g. `Decu · 7mup…pump · 16 Oct 20:13`. `label` is the suggested-wallet label, else `walletShort` (`4vw5…9Ud9`). `mintShort` is the first 4 + `…` + last 4 characters; without a mint (Pump.fun buys scope) it is `Pump.fun buys`. Time comes from the injected `Clock`.
9. The name key is `name.trim()` with runs of whitespace collapsed to one space, then `lowercase(Locale.ROOT)`. Two non-deleted sessions never share a key for names created or renamed after this change. A generated name that collides gets ` (2)`, ` (3)`, … (the first free one). A typed name that collides returns 409 `NAME_TAKEN` with that suggestion.
10. Length stays 1–120 after normalization. Generated names are truncated in the mint part, never mid-suffix.
11. `displayName` = stored name, plus ` (n)` for legacy duplicates numbered by `(createdAt, id)` ascending. The first one keeps its bare name. Stored files are not rewritten.
12. Default order is `createdAt` descending, ties by `id` ascending. `attention` order = `NEEDS_ATTENTION`, `STOPPED_LIMIT`, `STOPPED_DEADLINE`, `WAITING_RETRY`, `PAUSED`, `RUNNING`, `READY_TO_REPLAY`, `NOT_STARTED`, `DONE`, `CANCELLED`, then newest first. `total` and `statusCounts` cover all sessions, not the page.
13. `CaptureStatusRules` is total over every combination of stage states, operation states (`RUNNING`, `SUCCEEDED`, `INCOMPLETE` + stop code, `FAILED`, `INTERRUPTED`, `WAITING_RETRY`, `PAUSED`, `CANCELLED`, `*_REQUESTED`) and case readiness. It uses exhaustive `when` without `else`. Examples that must hold:
    - an `INCOMPLETE` market operation with stop `REQUESTS` gives `STOPPED_LIMIT` and next action `RESUME_MARKETS`;
    - `FAILED` gives `NEEDS_ATTENTION` with the step's retry action;
    - any ready case without a dataset gives `READY_TO_REPLAY` with `REGISTER_DATASET`;
    - a registered dataset with a run gives `DONE` with `VIEW_RESULTS`;
    - no operation yet gives `NOT_STARTED` with `START_WALLET`.
14. Progress never invents a denominator. During discovery (`selected` unknown), `progress` is absent and the summary carries the phase only.

### Preset, percentages, recommendations

15. `GET /api/runs/preset` returns exactly the table in Context: values, statuses, `specRef`s and the unsupported-rule list. Starting a run from the unchanged preset produces the same `configurationHash` as sending the same values in bps.
16. Percent → bps: the input is a plain decimal string matching `^[0-9]+(\.[0-9]{1,2})?$`. `bps = percent × 100` must be an integer (`BigDecimal.movePointRight(2)`, `intValueExact`), with no rounding. `20` gives 2000, `15.5` gives 1550, `0.01` gives 1, `20.255` returns 400 `INVALID_PERCENT`. Ranges are the existing bps ranges (1–9999); `0`, `100` and `99.995` are rejected with the range in the message.
17. Bps → percent: `BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString()`, so 2000 gives `"20"`, 1550 gives `"15.5"`, 1 gives `"0.01"`. Signed values such as impact keep their sign (`-0.11`). One converter (`console/domain/PercentConversion`) is used by every mapper.
18. A request that sends both `dipBps` and `dipPercent` is accepted only if they are equal after conversion; otherwise 400 `CONFLICTING_FIELDS`.
19. `presetId` is provenance only: validated (only the agreed preset id) and written to the `console run started` log line. It is not stored, because storing it would change replay's public `SimulationRuns.start` contract; a run from the unchanged preset is recognisable because its config equals the preset. It never changes the hash or run id.
20. Recommendations are pure functions of request parameters, capture config (`ratesConfigured`, defaults) and dataset facts (pair count). Same inputs always give the same list and order. `CUTOFF_DECIDED` is not offered when rates are not configured.

### Usage tracking

21. Every HTTP request sent to the RPC or Binance produces exactly one `ApiCall`; a retry is another call. A cache-served `getTransaction` produces a `CACHE_HIT` and no request.
22. Buckets are UTC 5-minute windows. `bucketStart = floor(epochSecond / 300) × 300` from the injected `Clock`.
23. Bucket identity is `(bucketStart, provider, operation, task, captureScope)`, where `captureScope` is the capture id or `""`. Flushing adds deltas (`requests = requests + :delta`), so flushing twice from two delta snapshots never double-counts, and concurrent flushes converge.
24. `report(period)` covers `from = floor5m(now − duration)` to `to = now`. In-memory unflushed deltas are included, so the report matches what has been sent. `ALL` starts at the oldest bucket; `recordingStartedAt` is that bucket's start, or null if none. `sharePercent` = call requests / total requests × 100, scale 1, `HALF_EVEN`; the total-zero case returns `"0"`.
25. Nothing secret is recorded. `operation` is validated against the catalog pattern `^[A-Za-z/0-9_.]{1,64}$`; a URL, query string or host is rejected by the recorder (counted under `operation = "other"`, with a WARN without the value).
26. Losing up to 15 s of counts on a hard kill is accepted and documented. A clean shutdown flushes.

## Data Model

New table, owned by `apiusage`, declared on `ApiUsageBucketEntity` (plain class, `var` properties):

| Column | Type | Constraint |
|---|---|---|
| `id` | `varchar(64)` | PK: SHA-256 hex of the identity tuple (rule 23) |
| `bucket_start` | `timestamptz` | not null |
| `provider` | `varchar(16)` | not null, `@Enumerated(STRING)` |
| `operation` | `varchar(64)` | not null |
| `task` | `varchar(24)` | not null, `@Enumerated(STRING)` |
| `capture_scope` | `varchar(80)` | not null, `''` when none |
| `requests`, `failed_requests`, `rate_limited_responses`, `cache_hits` | `bigint` | not null, default 0 |
| `response_bytes` | `bigint` | not null, default 0 |
| `last_call_at` | `timestamptz` | null |

- `@Table(name = "api_usage_buckets", uniqueConstraints = [UniqueConstraint(name = "uq_api_usage_bucket_identity", columnNames = ["bucket_start","provider","operation","task","capture_scope"])], indexes = [Index(name = "idx_api_usage_buckets_start", columnList = "bucket_start"), Index(name = "idx_api_usage_buckets_capture", columnList = "capture_scope, bucket_start")])`
- Flush: one `@Modifying` native upsert per key, `INSERT … ON CONFLICT (id) DO UPDATE SET requests = api_usage_buckets.requests + EXCLUDED.requests, …, last_call_at = GREATEST(...)`, inside one service-level transaction per flush.
- Reads are aggregate SQL over a bounded `bucket_start` range (`ALL` is bounded by aggregation, not row reads).

Production SQL, applied by hand before deploying:

```sql
CREATE TABLE api_usage_buckets (
  id varchar(64) PRIMARY KEY,
  bucket_start timestamptz NOT NULL,
  provider varchar(16) NOT NULL,
  operation varchar(64) NOT NULL,
  task varchar(24) NOT NULL,
  capture_scope varchar(80) NOT NULL,
  requests bigint NOT NULL DEFAULT 0,
  failed_requests bigint NOT NULL DEFAULT 0,
  rate_limited_responses bigint NOT NULL DEFAULT 0,
  cache_hits bigint NOT NULL DEFAULT 0,
  response_bytes bigint NOT NULL DEFAULT 0,
  last_call_at timestamptz,
  CONSTRAINT uq_api_usage_bucket_identity UNIQUE (bucket_start, provider, operation, task, capture_scope),
  CONSTRAINT ck_api_usage_nonnegative CHECK (requests >= 0 AND failed_requests >= 0 AND rate_limited_responses >= 0 AND cache_hits >= 0 AND response_bytes >= 0)
);
CREATE INDEX idx_api_usage_buckets_start ON api_usage_buckets (bucket_start);
CREATE INDEX idx_api_usage_buckets_capture ON api_usage_buckets (capture_scope, bucket_start);
```

Captures (files, not database): `CaptureSessionRecord` gains optional `nameKey: String? = null`, written on create and rename and readable by old files (absent means computed). No other schema change. Nothing is destructive; no local reset is needed.

## Transactions & Integration

- Wallet activity: no database transaction. Provider calls go through `CaptureRpcs.create(…, transactionCache, recorder)` and the shared `RpcLimiterRegistry`, with the existing `SolanaRpcSettings` timeouts and retry/backoff (`HttpSolanaReadRpc`) and a 30 s overall deadline (`CaptureBudget.operationTimeout`). Annotation (`capturedIn`) reads the capture file store after the remote calls.
- Usage flush: a short `@Transactional` service method per flush; never during a provider call (the recorder only touches memory).
- Run launch with percent fields: conversion happens in `ConsoleConfigValidator` before the existing launch transaction.
- The source lookup (`CaptureSourceLookup`, console/capture, `local & !prod` like `CaptureController`) scans session and case records for a `datasetId`. It is cached per request. When the capture console is absent, `source.capture*` is null.
- New configuration:
  - `trenching.console.suggested-wallets` (list of `{label, address}`, validated base58 and 1–24 character labels, bound in `ConsoleProperties`). It defaults to empty in code. `application.yml` gets `[{label: Decu, address: 4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9}]`; this is a public on-chain address, and `application.yml` is freely editable.
  - `trenching.api-usage.flush-interval` (default `PT15S`, 1–300 s) in `ApiUsageProperties`.
  - No secret, no prod-only property. `SOLANA_RPC_URL` is unchanged and only its presence is checked.
- Live provider calls happen only when the user clicks Load/Refresh in the console. No test, build or agent run calls a provider.

## What To Implement

Ordered deliverables. Units 1, 2 and 4 touch disjoint files and can run in parallel; 3 needs 2; 5–8 need 1 only where noted; 9 is last.

1. **Percent conversion and the agreed preset** (`console/domain/PercentConversion`, `AgreedStrategyPreset`, `ConsoleConfigValidator`, config/run mappers, `RunStartRequest`, `RunController` preset endpoint). Run start accepts percents exactly or rejects them; every bps in config, limits and run detail has its percent twin; `/api/runs/preset` returns the table with the `MISSING` flag and unsupported rules; the spec drift test passes.
2. **API usage recording and storage** (`apiusage` module, `chaincapture` budget context and recorder hooks, Binance and probe hooks, entity, upsert, flusher). Every request sent is counted once under the right provider, operation, task and capture; cache hits are counted apart.
3. **API usage report endpoint** (`ApiUsageReportService`, `UsageController`, DTOs, mapper). `/api/usage` returns periods with explicit `from`/`to`, totals, by-call with purpose codes, by-task, 5-minute series and pacing. Needs 2.
4. **Wallet activity** (`walletactivity` contract and service; `console` `WalletController`, annotation, 60 s cache). Paged recent buys and sells with bounded cost and every negative case mapped. It records usage once unit 2 lands (until then `ApiUsageRecorder.NONE`).
5. **Capture names** (`CaptureNamePolicy`, create/rename/check endpoints, `displayName`, `nameKey`). Generated unique names, 409 with suggestion, legacy duplicates numbered on read.
6. **Capture summary, ordering and filters** (`CaptureStatusRules`, `CaptureSessionService.page` sort and filter, `statusCounts`). Newest-first lists with status, progress and next action for every session.
7. **Capture config and recommendations** (suggested and recent wallets in `/api/captures/config`; `/api/captures/recommendations`; `/api/runs/recommendations`). Needs 1 for `USE_AGREED_STRATEGY`.
8. **Source links and chart page data** (`CaptureSourceLookup`; `source` on run and dataset; `extent` on chart responses; scenario `timeline` instants). A run and its chart name their dataset, capture and case, and the chart knows its full extent.
9. **Docs and the Codex handoff refresh**: README API/config tables, an ADR for the preset mapping, the capture console ADR addendum, and the exact contract confirmed in the handoff.

## Verification

Test-first, fixtures only, no network:

- `PercentConversionTest`: `20`→2000, `15.5`→1550, `0.01`→1, `99.99`→9999; rejects `20.255`, `-1`, `1e2`, `0`, `100`, blank; bps→percent round trip for 1..9999; signed impact `-11`→`-0.11`.
- `AgreedStrategyPresetTest`: parses the spec YAML and pins values; `horizonSlots` status `MISSING`; the unsupported-rule list has the 8 spec refs.
- `ConsoleConfigValidatorTest` (extended): percent and bps equivalence gives the same `SimulationConfig` and `configurationHash`; `CONFLICTING_FIELDS`; old bps-only requests unchanged.
- `RunControllerTest` / `ConfigControllerTest` (extended): percent fields present; `/api/runs/preset` JSON shape.
- `ApiUsageAccumulatorTest`: concurrent records from 8 threads sum exactly; bucket flooring at 299/300/301 s; secrets rejected (an `operation` containing `https://` lands under `other`).
- `ApiUsageBucketPersistenceIntegrationTest` (Testcontainers): two flushes add up; concurrent flushes converge; unique key enforced; the report includes unflushed deltas; periods give the expected `from`/`to` with a fixed `Clock`.
- `HttpSolanaReadRpcUsageTest` (stub HTTP server, as in existing RPC tests): 200, 429 + retry, 500, timeout give the expected outcomes and request counts; `CachingSolanaReadRpc` hit → `CACHE_HIT`, no request.
- `BinanceSolUsdtRateSourceTest` (extended): klines and ticker recorded with the right operation and task.
- `RecentWalletActivityServiceTest`: fixtures from existing Pump.fun buy/`sell_v3` transaction JSON in `src/test/resources`. Covers buys and sells by the wallet; other users' events ignored; two events in one transaction; unreadable counted; failed listing entries skipped; `before` paging; request bound respected; exactly 1.9 SOL is not over the threshold; `earlierActionInPage`; each `Unavailable` reason.
- `WalletControllerTest`: status codes and error codes per rule 7; `capturedIn` annotation from a fixture file store.
- `CaptureNamePolicyTest`: generation format, collision suffixes, normalization, truncation, legacy display numbering.
- `CaptureSessionServiceTest` (extended): newest-first default, each sort, status filter and counts, create without a name, `NAME_TAKEN`, rename idempotency.
- `CaptureStatusRulesTest`: table-driven over operation states × stage states × case readiness, including every example in rule 13 and "no denominator during discovery".
- `CaptureSourceLookupTest`: dataset from session export, from case export, absent, deleted capture.
- `ChartConsoleServiceTest` (extended): `extent` independent of `from`/`to`; empty pair → null extent.
- Existing suites unchanged, including the pinned `dip-sample-v3` run id and checksum, and `ConcurrentRunStartIntegrationTest`.

Commands (one Gradle build at a time, `JAVA_HOME` set as in memory/README):

```powershell
$env:JAVA_HOME = 'C:/Users/user/.jdks/ms-21.0.10'
.\gradlew.bat test --tests "*PercentConversion*" --tests "*AgreedStrategyPreset*" --tests "*ApiUsage*" --tests "*WalletActivity*" --tests "*CaptureStatusRules*" --tests "*CaptureNamePolicy*"
.\gradlew.bat build
```

## Documentation Sync

- README: console API table (new endpoints), configuration table (`trenching.console.suggested-wallets`, `trenching.api-usage.flush-interval`), the API usage section (units, periods, CLI not counted), and the production SQL step.
- ADR `docs/adr/20261016-agreed-strategy-run-preset.md`: mapping decu-dip v2 onto dip-entry v1, statuses, `MISSING` horizon, why `mvp()` stays the API default; revisit when decu-dip v2 ships.
- ADR addendum to `20261010-capture-console.md`: unique names, derived summary, sort order.
- `docs/REAL_DATA_CAPTURE.md`: picking a token from wallet activity and its request cost.
- Archive this plan with a Delivered note after review.

## Acceptance Criteria

1. `GET /api/captures` with no parameters returns every session newest first (a session created 16 Oct 20:13 before ones from 10 Oct), ties by id; `total` equals the number of session directories and `statusCounts` sums to `total`.
2. Creating a capture with a blank name for Decu and mint `7mupUtwu17wevEfnyrE6XsWa1X9WWntAPeqdckd2pump` at a fixed clock of 2026-10-16T20:13Z (UTC) stores `Decu · 7mup…pump · 16 Oct 20:13`. Repeating it with another `requestId` stores `… (2)`. Sending that name explicitly returns 409 `NAME_TAKEN` with suggestion `… (3)`.
3. The two legacy `runitback` sessions list as `runitback` and `runitback (2)`; their `session.json` files are byte-identical before and after.
4. A session whose market operation is `INCOMPLETE` with stop `REQUESTS` shows `summary.status = STOPPED_LIMIT`, `currentStep.index = 2`, `progress = {processed 1272, selected 1697, percent "74"}` and `nextAction.kind = RESUME_MARKETS` with `maxRequests = 400`.
5. `GET /api/runs/preset` returns `dipBps 2000 / dipPercent "20"`, `takeProfitPercent "55"`, `stopLossPercent "40"`, `notional "0.100000000000"`, `horizonSlots 4500` with status `MISSING`, and 8 unsupported rules.
6. `POST /api/runs` with `dipPercent "20", takeProfitPercent "55", stopLossPercent "40"` and the other preset values creates the same run id as the bps request; `dipPercent "20.255"` returns 400 `INVALID_PERCENT` on field `dipPercent`.
7. With a stub RPC fixture of Decu's accumulator listing (3 buys, 1 `sell_v3`, 1 failed entry, 1 PumpSwap transaction), `GET /api/wallets/4vw5…/activity?limit=20` returns 4 actions newest first, `unreadable 1`, `scanned 6`, `requestsUsed` ≤ 7, and annotates the mint already in a fixture capture with that capture's `displayName`.
8. After that call, `GET /api/usage?period=LAST_HOUR` reports `getSignaturesForAddress` 1 and `getTransaction` ≤ 5 under task `WALLET_ACTIVITY`, `from`/`to` 5-minute aligned and an hour apart, and each call row carries its purpose code.
9. `GET /api/runs/{id}` of a run on a dataset registered from a capture case returns `source.captureId`, `captureDisplayName` and `caseId`; `GET /api/runs/{id}/chart?from=…&to=…` returns an `extent` equal to the full pair range.
10. No request in any test reaches a network host; `.\gradlew.bat build` passes with the legacy pinned checksums unchanged.

## Open Questions

1. **Are Decu's sells always in the accumulator listing?** The `qwertyu` capture shows one `sell_v3` there, but no rule guarantees every sell route passes the accumulator. *Recommendation:* ship buys and sells from the accumulator, label the list "Pump.fun buys and sells (sells may be incomplete)", and confirm with one sanitized sample of a Decu sell from the user.
2. **Token names and symbols.** They are not in the trade transactions; showing them needs one extra read per mint (Metaplex metadata or Token-2022 metadata extension). *Recommendation:* not in this milestone; show the short mint plus a pump.fun link, and revisit with a bounded opt-in lookup.
3. **The `DECIDED` market cutoff still uses `SimulationConfig.mvp()`** (15% / +30% / −15%). A run with the agreed preset (20% / +55% / −40%) on a window cut under old rules can end early and come out `INCOMPLETE` or truncated. *Recommendation:* decide in a follow-up whether cutoffs use the agreed preset; until then the recommendation card shows caveat `CUTOFF_USES_LEGACY_DEFAULTS`.
4. **Agreed value for the observation window (`horizonSlots`) in dip-entry v1.** The spec has no equivalent. *Recommendation:* you choose; 600 slots (~4 min, the spec's latest exit start) is the closest single number but lets dips through for 4 minutes instead of 30 s. Until decided, the preset keeps 4,500 flagged `MISSING`.
5. **Count Gradle CLI captures in API usage?** CLI tasks don't start the database. *Recommendation:* not now; the dashboard states that CLI captures are not counted.
6. **Usage retention.** Rows are small (5-minute buckets, only when calls happen). *Recommendation:* keep everything for now and revisit if the table passes 1 million rows.
7. **Should the `decu-dip` v2 replay plan (Draft) be approved next?** The preset can only approximate the agreed strategy until it ships. *Recommendation:* yes; the run page then switches the preset to that engine and drops the unsupported-rule notice.
