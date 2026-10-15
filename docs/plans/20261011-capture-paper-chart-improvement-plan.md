# Capture, paper trading and chart improvement plan

Status: Approved 2026-10-12 — implemented in milestones: [1 independent cases](20261012-capture-paper-implementation.md), [2 capture throughput](archive/20261014-capture-throughput.md), [3 chart data](archive/20261015-chart-trade-actors-mcap.md). Decisions since approval: provider stays on the Helius free tier (10 req/s), so no paid filtered-history adapter for now; strict first interaction (unknown history stays UNPROVEN); chart Mcap uses total supply, labelled, with the USD/USDT basis stated; a new dataset version may carry trade actor, side and amounts for wallet markers.

The recommendation is to make each complete case independently usable, add bounded automatic recovery, then improve acquisition throughput and the chart. The new strategy needs its own versioned paper implementation. Live trading, signing transactions and sending orders remain outside this plan.

This review used the source in `D:/Projects/trenching_bot`, capture-manifest metadata, the latest supplied `decu-strategy-spec (1).md`, the chart reference, and public provider documentation. No project files were changed, no application or tests were started, and no authenticated provider requests were made. Manifest counts below are metadata observations, not a fresh verification of every saved artifact. Performance estimates are theoretical unless stated otherwise.

## 1. What is causing the current experience?

| Finding | Evidence and consequence |
|---|---|
| Requests are serialized and paced at roughly two starts per second | `HttpSolanaReadRpc.kt:41,170` holds a synchronized call and uses a 500 ms interval. Slow responses and retries reduce throughput further. Buying a higher-rate plan alone will not remove this limit. |
| All market windows must finish discovery before detail fetching begins | `MarketHistoryCaptureService.kt:51` waits until no window has pending discovery. A difficult later window can delay useful work on an earlier one. |
| Detail transactions are fetched individually | The market service then walks unresolved records. Token activity determines the work: requesting one more wallet buy can add thousands of market transactions. |
| Completion is checked across the whole batch | `RecordedMarketHistoryService.kt:25` and `RecordedMarketVerification.kt:20` block downstream processing when the overall capture is incomplete. Rates, export and replay inherit that restriction. |
| Old windows require scanning newer history first | Standard signature listing starts at the newest activity. Transactions after the desired window add listing work without improving the dataset. |
| Normal time limits look like a stalled task | The RPC invocation defaults to ten minutes, while some windows necessarily require longer. Process recovery marks operations interrupted and requests explicit resume. |
| Verification may add substantial local work | The operation service inspects before and after stages; artifact loading verifies saved files, and decoding loads them again. The checks are valuable, but repeated disk reads and hashing need profiling. |
| Crash recovery can repeat completed downloads | The detail manifest checkpoints every 100 successful fetches. Raw files written after the last checkpoint may need reconciliation rather than another network fetch. |

A concrete example is `build/captures/decu-dip-markets/market-manifest.json`:

| Window | Selected transactions | Recorded as captured |
|---|---:|---:|
| First | 718 | 718 |
| Second | 625 | 451 |
| Third | 2,866 | 1 |

All three signature-discovery windows are marked complete. There are 4,207 unique transaction records overall; window memberships overlap, so their counts must not be summed as unique work. At two requests per second, fetching 4,207 transactions alone has a lower bound of about 35 minutes, before discovery, network delays, retries and verification. The largest window alone needs about 24 minutes at that pace.

The first window is a candidate for independent completion. It still needs decoding, rates, coverage and eligibility checks; captured files alone do not make it a valid paper case. This explains why the first samples can appear successful while the later ones take much longer, without assuming the provider is malfunctioning.

## 2. First resolve which strategy the results represent

The running default and the supplied specification differ materially:

| Rule | Current default implementation | Supplied Decu specification |
|---|---|---|
| Dip | 15% | 20% |
| Profit / stop | +30% / −15% | +55% / −40% |
| Timing | 4,500-slot window, one-slot fill latency | 30-second entry deadline; explicit timed exit and recording tail |
| Mcap filter | At the dip | At the reference buy |
| Reference | Existing buy/trade-price semantics | Post-trade reference price |
| Eligibility | Existing scoped first-buy verification | First interaction plus >1.9 SOL token spend |
| Position capacity | Replay processes intents independently | One position, including an entry in flight |

The engine identifier `dip-paper-v2` is not evidence that the supplied Decu v2 strategy is implemented. Preserve the existing strategy for reproducible comparisons and introduce an explicit new strategy/configuration version.

The existing fill model already uses bonding-curve reserves, integer calculations and fees. Extend this foundation rather than replacing it with “buy at the candle price.” Independent cases are useful for debugging, but summing their P&L does not reproduce a strategy that permits only one simultaneous position. Portfolio evaluation must process all eligible cases chronologically and reserve capacity during simulated in-flight entries.

## 3. Priority 0 — quick improvements and a measurable baseline

These are the smallest changes to do first once implementation is approved:

- Show each case separately, including discovery, downloaded/selected transactions, validation, rates and replay status. During discovery, show pages and the oldest scanned slot; do not invent a percentage before the denominator is known.
- Show the actual stop reason: timed yield, provider backoff, request/byte budget, unsupported transaction, missing history, validation failure or cancellation. Show last progress time, retry time and cumulative requests.
- Instrument discovery, network wait, rate-limit wait, retries, verification, decoding, rates and export separately. Measure time to first usable case as well as total batch duration.
- State the strategy version, observation window and coverage limitations prominently on every result.
- Preserve the existing Wallet + token mode as a practical way to request an isolated case while the larger batch redesign is pending.
- Plan a durable capture directory outside `build/`; build cleanup should not be able to remove valuable evidence. Existing captures need an explicit, verified migration later, not an automatic move during this review.

Acceptance: progress refreshes within two polling intervals; a timed stop is distinguishable from failure; transaction counts distinguish unique downloads from per-window membership; measurements can explain where elapsed time went. No result is labelled ready from progress counts alone.

## 4. Priority 1 — publish complete cases while the batch continues

### Unit of completion

A case consists of the wallet/mint anchor, its required market window, eligibility evidence, rates and strategy configuration. It is not one downloaded transaction. Multiple buys of one mint may share a window or form different cycles according to the selected strategy.

Give each case its own pipeline:

`discover → fetch → decode → rates → validate → publish immutable dataset → paper replay`

Advance a case as soon as its own dependencies are complete. Discovery or failure in another case must not block it. Keep shared raw transactions in a verified cache so overlapping windows do not pay for the same download twice.

Publish two separate dimensions:

- Work status: queued, running, waiting to retry, paused, blocked, finished or cancelled.
- Data/result status: preview only, incomplete, validated and ready, ineligible, replay failed, or completed strategy outcome.

A ready case gets **Open chart**, **Run paper replay** and **Export case** immediately. Incomplete cases may have a clearly marked chart preview, but cannot produce a trustworthy negative strategy verdict.

### Batch and export behavior

Keep the existing strict full-batch export. Add an explicitly scoped case export with its own window list, provenance, version and checksum. Do not make partial export by disabling the current completeness check or silently omitting bad records.

The batch view should say, for example, “1 ready, 1 downloading, 1 waiting to retry,” and list every originally requested case. Cancelling remaining work preserves published cases. Published datasets remain immutable; later recovery creates a new revision only when its contents change.

Default scheduling should favor finishing an already-started case, with bounded fairness so large cases cannot monopolize all work. Offer a way to prioritize a chosen case. Smallest-first scheduling can reduce waiting, but must not silently bias evaluation toward quiet or easy tokens.

Acceptance:

- With three cases, the first can export, chart and replay while another is incomplete or blocked.
- Published results become visible within two seconds of successful validation on the reference local machine, excluding network work.
- A missing transaction blocks only the affected case; a shared missing transaction correctly blocks every dependent case.
- Cancelling the remainder leaves ready cases and their checksums intact.
- Batch reports retain failed, cancelled and ineligible cases, preventing “successful captures only” performance statistics.

## 5. Priority 1 — automatic recovery with limits

Persist the next operation, case cursor, attempt count, next retry time and cumulative budget. On restart, reconcile saved artifacts, reclaim abandoned work safely and continue eligible jobs automatically. Use a lease or equivalent single-owner mechanism so an old worker cannot keep writing after a new worker takes over.

| Situation | Proposed behavior |
|---|---|
| Timeout, connection reset, HTTP 429 or transient server error | Bounded exponential backoff with jitter; respect Retry-After; display the next attempt. |
| Invocation time slice expires | Save progress and schedule another slice if the overall job budget remains. This is a yield, not a terminal failure. |
| Process restarts | Reconcile durable progress and resume automatically unless paused, cancelled or blocked. |
| Provider budget or overall job limit reached | Stop with a visible reason. Another attempt must not silently reset the budget. |
| Identity/hash mismatch, unsupported schema or reproducible decode failure | Quarantine or block with actionable evidence; do not retry indefinitely. |
| Provider temporarily returns no transaction | Retry within a bounded policy; unresolved absence remains a data gap. |

Persist each downloaded artifact atomically and acknowledge it in a durable journal before moving on. Periodically compact the journal into the manifest. Reconcile intact orphan artifacts after a crash, and re-fetch only missing or invalid evidence. Preserve request-ID idempotency already present in the console.

Use separate identities for a raw transaction and its trade events. One transaction can contain several swaps: deduplicating by signature alone must not discard valid events. Event identity includes signature, instruction location, mint and source/chain context.

Provide **Pause**, **Resume**, **Retry failed** and **Cancel remaining**. Cancellation should stop scheduling new requests, safely settle or time out the current bounded request, and checkpoint before reporting completion. It must not erase evidence or claim an unfinished case is complete.

Acceptance: crash-injection tests at artifact write, journal commit, checkpoint and export publication produce the same final dataset hash and replay result as uninterrupted execution. Repeated requests create no duplicate trades or jobs. Retry storms respect a shared rate limit and job budget. Paused/cancelled work stays stopped after restart.

## 6. Priority 2 — improve throughput without weakening validation

First remove the all-window discovery gate and repeated network work. Then replace the serialized RPC call with bounded concurrency controlled by one provider-wide limiter. Start conservatively and tune from measured latency, quota and response size; a separate limiter per worker would accidentally multiply the permitted request rate.

Cache immutable raw evidence by chain, signature, commitment and representation. Keep decoder versions in derived-data identities. A verified snapshot can be reused within one operation, avoiding redundant full-file scans; invalidate verification when the content changes. Do not simply remove integrity checks.

Add a provider-history interface supporting standard RPC and an optional filtered/bulk implementation. Both must normalize into the same raw evidence and validation path. Bound response bytes and page sizes as well as request counts. Larger pages reduce calls but increase memory use and retry cost.

Use strategy-aware observation horizons. The supplied strategy's short entry and exit rules may need much less history than today's 4,500 slots, but four minutes cannot be substituted blindly: unresolved exits can require a tail, and same-slot events after the anchor matter. Never stop merely because a favorable outcome has appeared unless the selected dataset contract explicitly supports that terminal boundary.

Benchmark representative quiet, busy, older and migrated tokens with identical windows. Record first-ready time, total duration, request count, billed units, bytes, retries, missing events and decoded-event parity. Provider evaluation should use a separately approved bounded request budget; no such calls were made for this plan.

Acceptance: all adapters produce matching canonical events on the benchmark fixtures or report explained discrepancies. Select a faster adapter only after coverage parity is established. Establish measured targets before promising a particular number of minutes.

## 7. Data quality required for credible paper results

The project already has useful safeguards: hashes, transaction identity checks, coverage/exclusion rules, deterministic dataset export and integer curve calculations. Retain them and address these gaps:

| Area | Required validation |
|---|---|
| First buy versus first interaction | Keep the exact scope of proof. Curve-buy routes and accumulators do not establish absence of earlier passive transfers or activity on every venue. Unknown evidence makes the new rule unproven, not satisfied. |
| Mint creation and history bounds | Decode actual creation evidence; the oldest returned signature alone is insufficient. Record provider/index coverage and cursor continuity per source. |
| Market coverage | Define supported venues and addresses. Validate that the selected address index contains the required trade routes; migration to another pool requires explicit continuation. “No trades returned” is not automatically complete coverage. |
| Event ordering | Preserve slot, transaction position when available, instruction order and signature. Lexicographic signature order is not execution order. Mark consequential same-slot ambiguity explicitly. |
| Reference price | Current observations use trade amount ratios and retain curve state. The new spec wants the post-trade reference. Store both with clear semantics and version the change. |
| Entry deadline | Use the spec's time rule and distinguish block time from observed/received time. A historical replay cannot recover actual historical network latency; simulate and label that assumption. |
| Mcap | Record supply basis, decimals, timestamp and rate provenance. Current fixed-supply and SOL/USDT assumptions must be explicit, not silently treated as verified circulating USD market cap. |
| Migration and missing tails | Unsupported post-migration execution or an unfinished position is an unsupported/incomplete result, not NO_DIP or a zero-profit exit. |
| Paper execution | Model placement latency, quote-relative price limits, impact, fees, failed attempts and timed exits. Keep requested notional separate from total spend including costs. |
| Portfolio behavior | Enforce the one-position rule across tokens, including in-flight entries. Record skipped signals and the reason. |

Completeness means complete **within the declared source, route, time and commitment scope**. A checksum proves file integrity, not that a provider returned every on-chain event. Use cross-provider or independently indexed spot checks for representative cases, including boundary transactions and migration. Discrepancies block qualification until explained.

Create offline fixtures for: known dip/no dip, multiple swaps in one transaction, same-slot ordering, missing transaction, missing rate, unknown supply, earlier transfer, migration, delayed entry, rejected fill, timed exit and overlapping signals. Assert deterministic replay, balance conservation, fee accounting and unchanged legacy results. Market aggregates and explorer/chart comparisons are secondary cross-checks; they are not replacements for transaction-level replay evidence.

## 8. Provider and service options

Public USD monthly prices checked 11 October 2026; exclude overages and taxes. Current account entitlements were not inspected. Rates are published limits, not measured throughput or completeness guarantees.

| Option | Cost | Speed and coverage | Reliability and integration | Recommendation |
|---|---|---|---|---|
| Existing standard RPC, improved locally | No new subscription | Biggest initial gains come from removing local gates, concurrency and caching. Still requires signature scans and individual transaction retrieval. | Lowest integration effort; app owns recovery and gap checks. | Do first. |
| Helius Developer | $49/month; 10M credits; 50 RPC requests/s. Free tier lists 1M credits and 10 requests/s. | More headroom, but benefit depends on local limiter changes. | Low effort for standard RPC; same application validation required. | First paid tier to consider after checking existing entitlement. [Pricing](https://www.helius.dev/pricing) |
| Helius filtered/full history method | Entitlement to confirm; current metering is 10 credits per 100 full transactions returned, rounded up | Slot/time filters, oldest-first order and up to 1,000 full transactions per call reduce both newer-page scanning and detail-call overhead. | Medium adapter effort; verify field/version/commitment parity and index coverage. | Most promising capture-specific evaluation. [Method and billing](https://www.helius.dev/docs/rpc/gettransactionsforaddress) |
| Birdeye | Lite $39, Starter $99, Premium $199/month; WebSockets start at Premium in the published table | Market-data endpoints can simplify historical charts and screening; exact token/venue/history entitlement needs checking. | Medium effort; derived market data alone may lack the raw evidence needed by the curve fill model. | Optional chart service, not the first capture purchase. [Pricing](https://birdeye.so/data-api/pricing), [data types](https://birdeye.so/data-api/types-of-data) |
| Bitquery | Pro $99/month; archive add-ons extra. Solana OHLCV archive is listed at $300/month. | Indexed queries and streams are useful for broader venue/wallet analysis. Exact historical trade dataset access needs a quote/check. | Medium/high effort; GraphQL mappings, metering and decoder semantics need validation. | Consider if multi-venue history remains a gap; OHLCV alone cannot reproduce fills. [Pricing](https://bitquery.io/pricing) |
| Helius LaserStream gRPC | Mainnet included from Business, $499/month | Continuous ingest with documented replay up to 48 hours; useful for later real-time paper collection. | Higher integration effort; at-least-once delivery still requires deduplication and recovery beyond the replay horizon. | Defer until cheaper capture and paper ingestion are measured. [Pricing](https://www.helius.dev/pricing), [stream documentation](https://www.helius.dev/docs/laserstream) |

For scale, 2,866 transactions could fit in 29 pages of 100 full records, versus 2,866 individual detail requests. At the documented full-history rate, those pages would total about 290 credits, excluding other queries. This is an illustrative call/billing comparison, not a latency guarantee or proof that every desired transaction is indexed. Start with bounded pages rather than maximum-size responses. [History method](https://www.helius.dev/docs/rpc/gettransactionsforaddress), [standard RPC billing](https://www.helius.dev/docs/billing/credits)

The earlier conversation's provider pricing assumptions should not be used for purchasing decisions; the current documentation differs. My proposed order is existing tools → filtered-history trial → $49 tier if needed. Avoid buying a $499 streaming tier just to compensate for an all-or-nothing local workflow.

## 9. Chart proposal based on the reference image

### Wallet markers

Use circular wallet markers at observed trades, like the supplied image. The default view highlights the tracked wallet and a selected watchlist. An optional all-captured-traders layer is clustered at low zoom; it does not claim to show every trader on every venue.

- Use saved aliases/avatars or deterministic identicons. Never infer a person's identity from an address.
- Distinguish buys and sells using a ring plus B/S or an icon, so color is not the only cue.
- Show address, side, token/SOL amount, time, slot, venue and transaction link on hover or selection.
- Multiple traders at the same time use a cluster count or an expanded stack. Anchor the cluster to the actual event coordinates; visual offsets must not suggest a different execution price or time.
- Pin the tracked wallet, add wallet filters and retain selection while zooming. On mobile, tapping opens a detail panel; keyboard users need the same information.
- Draw simulated entries/exits differently and label them Paper. They must not resemble observed on-chain wallet trades.

The raw Pump.fun TradeEvent decoder already exposes trader address, side and amounts. The current observation model drops these fields. Re-extraction of verified local artifacts can therefore populate many pre-migration markers without another RPC download. New venue decoders are still required for broader coverage. Use the decoded trade actor, not automatically the fee payer or router.

### Market-cap axis

Replace the left price axis with a compact Mcap scale, such as $10k and $1.2m, with the valuation basis visible in the legend or tooltip:

`valuation at time t = SOL/token price(t) × SOL/USD rate(t) × chosen token supply(t)`

Circulating market cap and total-supply valuation/FDV are different. When verified circulating supply is unavailable, label the value as estimated or total-supply-based rather than silently substituting it. If only SOL/USDT is available, disclose USDT valuation or explicitly document the USD approximation.

Convert each trade at its applicable rate and supply before aggregating Mcap candles. Multiplying price OHLC by one final exchange rate can produce incorrect highs and lows. Retain precise backend decimals and use floating-point values only for display.

Missing supply/rates create an explicit gap or unavailable state. No current exchange rate should be applied retrospectively. SOL-denominated P0/dip/TP/SL levels can move on a USD Mcap chart as exchange rates change; derive their time-varying paths or clearly label a fixed-reference approximation.

### Rendering and data access

Reuse ECharts, already installed. Add optional candles to match the reference, using the same canonical observations as replay. Keep last-trade chart prices distinct from the strategy's post-trade reference price. Use wall-clock time for the primary axis and expose slot/order details in tooltips.

Add a bounded chart read API with viewport-based OHLC aggregation and trade-marker pagination. Downsampling is for display only; replay continues using full events. Avoid fetching an entire run or rendering a scatter point for every tick when zoomed out.

Acceptance: markers reconcile to decoded transactions; actor/side/amount are correct; watchlist filters and clusters preserve counts; paper fills are distinguishable; Mcap has a declared supply/rate basis; missing data remains visible. Establish a chart benchmark, initially targeting smooth interaction with 10,000 displayed bars and 1,000 visible markers on an agreed reference device, using clustering/aggregation for larger datasets.

## 10. Implementation boundaries and sequence

These are proposed work units, not changes made by this review.

| Order | Work unit | Owner/contracts | Dependencies and size |
|---|---|---|---|
| 1 | Progress, metrics and explicit strategy/coverage labels | `console`, capture query responses, frontend captures | Quick; establishes baseline. |
| 2 | Per-case validation and immutable publication | `marketdata`, `replay` application contracts, capture manifests | Medium/large; retain legacy full-batch contract. |
| 3 | Durable recovery, cancellation and budget accounting | `console` orchestration and `chaincapture` infrastructure | Medium; coordinate with case states and artifact ownership. |
| 4 | Bounded concurrency, verified cache and filtered-history adapter | `chaincapture` plus public market-history contract | Medium; requires parity fixtures and instrumentation. |
| 5 | Versioned Decu strategy and portfolio paper runner | `strategy`, `papertrading`, `replay` | Large; settle semantics below and obtain complete case evidence. |
| 6 | Trade actor metadata, Mcap projection and chart API/UI | `protocol` extraction → `marketdata` contracts → `console` DTOs → frontend | Medium/large; can follow validated metadata before the new strategy is complete. |
| 7 | Continuous paper-data ingestion | `walletactivity`, `marketdata`, paper orchestration | Later/large; historical pipeline and recovery must be reliable first. |

Keep controllers and frontend-facing services dependent on public application contracts, not replay internals. Keep provider-specific pagination and billing outside strategy code.

Prefer extending the existing file-based capture store with durable journal/checkpoint semantics first; a new database is not necessary solely to resume local capture jobs. Chart trade projections may need additive entities/indexes if persisted in the existing database. Follow the repository's schema conventions, preserve old records and specify deployment changes separately.

New capture fields and canonical dataset fields require explicit schema versions and backward readers. Existing v3 datasets must retain their checksums and meaning. Old datasets without trader or supply evidence should show unavailable metadata or be re-derived from verified raw evidence, not receive invented values.

For eventual real-time paper mode, reuse the historical normalizer, persist source and arrival timestamps, repair disconnect gaps and suppress new paper decisions when required data is stale. Backfilled data can repair history without pretending a missed real-time signal was observed on time. No signing or transaction submission is needed.

Update the capture guide, strategy ADR, provider adapter contract and UI glossary with each delivered unit. A paper-readiness milestone should require deterministic offline fixtures, restart/recovery tests, provider parity checks and a bounded real-data soak with no unexplained gaps. It does not authorize live trading.

## 11. Assumptions and questions before implementation

1. **Target strategy:** I assume the supplied Decu specification is the next paper strategy while the existing one remains available. Confirm the outstanding distinctions: strict first interaction versus scoped supported-route proof; 0.1 SOL token spend versus total cost allowance; and a finite paper exit/retry policy when the nominal recording horizon expires.
2. **Priority:** I recommend reliable historical cases first, then continuous real-time paper monitoring. Both are paper trading; neither requires real order execution.
3. **Provider budget:** I suggest evaluating the current entitlement first, then considering a $49/month capture tier. Confirm the monthly/overage ceiling before a paid trial; no purchase is part of this plan.
4. **Chart meaning:** Default to the tracked wallet plus a watchlist, with an optional all-captured-traders layer. Confirm whether “Mcap” should match a particular platform's total-supply convention or require verified circulating supply. Until settled, show the supply basis explicitly.

The principal tradeoff is deliberate: independent completion should reduce waiting, while each published case keeps the existing evidence standards. Faster downloading and better-looking charts are useful only if the paper result remains reproducible and its limitations are visible.
