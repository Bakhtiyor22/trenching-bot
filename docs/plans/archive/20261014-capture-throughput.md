# Capture throughput — milestone 2

Status: Delivered 2026-10-15 — see Delivered below.

Milestone 2 of the approved capture and paper improvement plan (milestone 1: [independent market cases](../20261012-capture-paper-implementation.md)). It removes the throughput bottlenecks found in the review: one `@Synchronized` RPC call at a time behind a hard-coded 500 ms interval, strictly sequential `getTransaction` detail loops, per-worker retry storms after HTTP 429, and the same signature being downloaded again by every capture directory.

Out of scope: the Helius filtered-history method (waits on the provider entitlement and budget decision), the new strategy, chart work, live trading.

## Decisions

- **Provider pacing is process-wide.** One limiter per RPC endpoint is shared by every capture operation in the process (console worker, case worker, CLI). It enforces a configurable minimum interval between request starts and a configurable maximum of in-flight requests.
- **Defaults:** at most 4 requests in flight and 8 request starts per second, below the 10 req/s free tier documented in `docs/PROVIDER_SPIKE.md`. Both are configuration properties, so they can be raised for a paid tier without code changes. Setting the concurrency to 1 with a 500 ms interval reproduces today's behaviour.
- **Rate limiting is cooperative.** A 429 or `-32005` response, with or without `Retry-After`, pauses new request starts for every worker on that endpoint until the retry time. Workers do not each back off independently. The existing attempt cap, deadline and request/byte budgets still apply.
- **Results are order-independent.** Parallel fetching may complete in any order, but manifests, artifacts, hashes, decoded datasets and v3 checksums must be byte-identical to a sequential capture of the same signatures. Manifest record order follows the signature listing, never the completion order. The one exception is token-account verification: results fetched after a verdict are discarded, so `requestsUsed` and `bytesUsed` may exceed a sequential capture's, while every other manifest field and every artifact is identical.
- **The budget is shared and exact.** Request and byte accounting is thread-safe. A budget stop still records every request already sent and every completed artifact.
- **Pause and cancel are prompt.** In-flight requests may finish, no new detail request starts after a stop is requested, and completed details are checkpointed before the operation stops. Stop latency is bounded by the in-flight count, not by the 100-record checkpoint interval. Wallet details checkpoint every 25 records, so a resume after a forced kill refetches at most 24, served from the shared cache when it is enabled.
- **The shared transaction cache is content-verified.** It stores successful, non-null `getTransaction` results by signature and by the exact request parameters (encoding, commitment, max supported version), at a configurable directory. The console defaults it to `<capture root>/transaction-cache`; CLI commands accept an optional directory. A cache hit is copied into the capture's own artifact path, so each capture stays self-contained and its manifest format is unchanged. Null/not-found, failed, provider-error and malformed responses (anything that is not a valid transaction artifact for its signature) are never cached, so a transient bad answer is not pinned. A cache entry whose stored hash or signature does not match is discarded, refetched and logged as a WARN. A hit costs no provider request and no request budget.
- **Measurements are reported.** Each detail phase logs and returns requests sent, cache hits, rate-limited responses, retries and elapsed time, so throughput gains can be compared before and after.

## Parts

1. `[impl] Shared provider pacing and transaction cache` (chaincapture + wiring). Owns `chaincapture/**`, capture properties and configurations, `replay/CaptureWorkflows.kt`, `console/config/CaptureConfiguration.kt`, `application.yml` and CLI capture commands. Delivers the process-wide limiter with bounded concurrency and cooperative 429 pause, a thread-safe `CaptureBudget`, the shared cache, and a bounded, cancellable parallel fetch helper that returns results in input order.
2. `[impl] Parallel detail fetching` (depends on 1). Owns `marketdata/application/MarketHistoryCaptureService.kt`, `walletactivity/application/WalletHistoryCaptureService.kt`, `walletactivity/application/WalletTokenVerificationCapture.kt` and their tests. Uses the helper for market details, wallet details and verification fetches, with prompt pause/cancel and the measurements.
3. `[review] Touched changes`.

## Acceptance checks

- With concurrency N, at most N requests are in flight, and request starts never come closer than the configured interval, across two simultaneous capture operations.
- A 429 with `Retry-After` stops new starts on every worker until the retry time. The attempt cap and deadline still end the operation.
- A capture fetched with concurrency 4 produces the same manifest bytes, artifact hashes and exported v3 checksum as the same capture fetched with concurrency 1, including on resume after an interruption. A wallet verification that reaches its verdict mid round is the exception for `requestsUsed` and `bytesUsed` only: requests whose results were discarded after the verdict still count.
- Request and byte counters equal the requests actually sent under concurrency, and a budget stop leaves no completed artifact unrecorded.
- After pause or cancel, no new detail request starts, completed details are in the saved manifest, and resume fetches only the unresolved records.
- A signature already in the shared cache is not requested from the provider again by a second capture, and that capture's artifact hash matches the cached bytes. A corrupt cache entry is refetched. A null transaction is not cached.
- The legacy full export and milestone 1 case export, validation and recovery tests still pass unchanged.

## Verification

Test-first with fake RPC servers and fake timing; no live provider calls. Targeted: `HttpSolanaReadRpcTest`, the new limiter/cache/fetch-helper tests, `MarketHistoryCaptureTest`, `WalletHistoryCaptureTest`, `CaptureOperationServiceTest`, `RecordedReplayExportTest`. Then `./gradlew build`.

## Schema and compatibility

No database schema change. Manifest, artifact and v3 dataset formats and checksums are unchanged. New configuration properties only, with the defaults above. The cache directory is local data and can be deleted at any time.

## Delivered

2026-10-15, commits `2d75cb8` (part 1), `99d8fc8` (part 2) and the review-fix commit that archived this plan.

- Shipped: one process-wide limiter per endpoint (default 4 in flight, 125 ms between starts; `max-concurrent-requests` and `min-request-interval` under `marketdata.capture` and `walletactivity.capture`), cooperative rate-limit holds, a thread-safe budget, the verified shared transaction cache (`<capture root>/transaction-cache`, CLI `-PtransactionCacheDirectory`), and bounded parallel detail fetching for market, wallet and verification capture with prompt pause/cancel and per-phase measurements in the logs.
- Differences from the plan: a hold that outlasts the operation deadline ends as `RATE_LIMITED` with `retryAfter` = remaining hold (not `DEADLINE`), so console auto-retry waits for the provider. Both `Retry-After` forms are capped at 30 minutes. Malformed transactions are never cached. Wallet verification discards fetches made after a verdict, so its request/byte counters can exceed a sequential capture's (everything else is identical). Wallet details checkpoint every 25 records. `CaptureCheckpointObserver` gained a default `stopRequested()` so services see pause/cancel between items without depending on the console.
- Review: one `backend-reviewer` pass (no blockers, two majors on the shared hold, several minors); all findings fixed in one fix part, including moving shared fetch/measurement helpers into `chaincapture`.
- Docs updated: README pacing rows, `docs/REAL_DATA_CAPTURE.md` pacing, cache and parallel-fetch sections.
- Schema impact: none. Manifest, artifact and dataset formats and checksums unchanged.
- Verification: `./gradlew build` ran 803 tests; 731 pass and 72 fail only because Docker is unavailable in the agent container (18 Testcontainers classes, "Could not find a valid Docker environment"). Those, and the console Spring wiring they cover, still need a local run with Docker.
