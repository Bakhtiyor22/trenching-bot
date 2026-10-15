# Capture throughput — milestone 2

Status: In progress — bounded parallel detail fetching, one shared provider limiter and a shared transaction cache.

Milestone 2 of the approved capture and paper improvement plan (milestone 1: [independent market cases](20261012-capture-paper-implementation.md)). It removes the throughput bottlenecks found in the review: one `@Synchronized` RPC call at a time behind a hard-coded 500 ms interval, strictly sequential `getTransaction` detail loops, per-worker retry storms after HTTP 429, and the same signature being downloaded again by every capture directory.

Out of scope: the Helius filtered-history method (waits on the provider entitlement and budget decision), the new strategy, chart work, live trading.

## Decisions

- **Provider pacing is process-wide.** One limiter per RPC endpoint is shared by every capture operation in the process (console worker, case worker, CLI). It enforces a configurable minimum interval between request starts and a configurable maximum of in-flight requests.
- **Defaults:** at most 4 requests in flight and 8 request starts per second, below the 10 req/s free tier documented in `docs/PROVIDER_SPIKE.md`. Both are configuration properties, so they can be raised for a paid tier without code changes. Setting the concurrency to 1 with a 500 ms interval reproduces today's behaviour.
- **Rate limiting is cooperative.** A 429 or `-32005` response, with or without `Retry-After`, pauses new request starts for every worker on that endpoint until the retry time. Workers do not each back off independently. The existing attempt cap, deadline and request/byte budgets still apply.
- **Results are order-independent.** Parallel fetching may complete in any order, but manifests, artifacts, hashes, decoded datasets and v3 checksums must be byte-identical to a sequential capture of the same signatures. Manifest record order follows the signature listing, never the completion order.
- **The budget is shared and exact.** Request and byte accounting is thread-safe. A budget stop still records every request already sent and every completed artifact.
- **Pause and cancel are prompt.** In-flight requests may finish, no new detail request starts after a stop is requested, and completed details are checkpointed before the operation stops. Stop latency is bounded by the in-flight count, not by the 100-record checkpoint interval.
- **The shared transaction cache is content-verified.** It stores successful, non-null `getTransaction` results by signature and by the exact request parameters (encoding, commitment, max supported version), at a configurable directory. The console defaults it to `<capture root>/transaction-cache`; CLI commands accept an optional directory. A cache hit is copied into the capture's own artifact path, so each capture stays self-contained and its manifest format is unchanged. Null/not-found, failed and provider-error responses are never cached. A cache entry whose stored hash or signature does not match is discarded, refetched and logged as a WARN. A hit costs no provider request and no request budget.
- **Measurements are reported.** Each detail phase logs and returns requests sent, cache hits, rate-limited responses, retries and elapsed time, so throughput gains can be compared before and after.

## Parts

1. `[impl] Shared provider pacing and transaction cache` (chaincapture + wiring). Owns `chaincapture/**`, capture properties and configurations, `replay/CaptureWorkflows.kt`, `console/config/CaptureConfiguration.kt`, `application.yml` and CLI capture commands. Delivers the process-wide limiter with bounded concurrency and cooperative 429 pause, a thread-safe `CaptureBudget`, the shared cache, and a bounded, cancellable parallel fetch helper that returns results in input order.
2. `[impl] Parallel detail fetching` (depends on 1). Owns `marketdata/application/MarketHistoryCaptureService.kt`, `walletactivity/application/WalletHistoryCaptureService.kt`, `walletactivity/application/WalletTokenVerificationCapture.kt` and their tests. Uses the helper for market details, wallet details and verification fetches, with prompt pause/cancel and the measurements.
3. `[review] Touched changes`.

## Acceptance checks

- With concurrency N, at most N requests are in flight, and request starts never come closer than the configured interval, across two simultaneous capture operations.
- A 429 with `Retry-After` stops new starts on every worker until the retry time. The attempt cap and deadline still end the operation.
- A capture fetched with concurrency 4 produces the same manifest bytes, artifact hashes and exported v3 checksum as the same capture fetched with concurrency 1, including on resume after an interruption.
- Request and byte counters equal the requests actually sent under concurrency, and a budget stop leaves no completed artifact unrecorded.
- After pause or cancel, no new detail request starts, completed details are in the saved manifest, and resume fetches only the unresolved records.
- A signature already in the shared cache is not requested from the provider again by a second capture, and that capture's artifact hash matches the cached bytes. A corrupt cache entry is refetched. A null transaction is not cached.
- The legacy full export and milestone 1 case export, validation and recovery tests still pass unchanged.

## Verification

Test-first with fake RPC servers and fake timing; no live provider calls. Targeted: `HttpSolanaReadRpcTest`, the new limiter/cache/fetch-helper tests, `MarketHistoryCaptureTest`, `WalletHistoryCaptureTest`, `CaptureOperationServiceTest`, `RecordedReplayExportTest`. Then `./gradlew build`.

## Schema and compatibility

No database schema change. Manifest, artifact and v3 dataset formats and checksums are unchanged. New configuration properties only, with the defaults above. The cache directory is local data and can be deleted at any time.
