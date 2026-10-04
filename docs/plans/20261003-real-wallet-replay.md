# Real wallet history and bounded replay

> Capture Decu's latest 100 mainnet transactions locally, decode supported swaps, and replay them with recorded slot-aware market observations in the existing console.

Status: Approved (2026-10-03, user approved in chat)

## Context

The user supplied wallet `4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9` and selected mainnet, latest 100 transactions. The user configured Helius locally and shared a console screenshot showing a successful mainnet-beta connectivity check; the newest-five address diagnostic reported no successful transaction. No agent called the provider or read the environment file. Ownership of this public address has not been independently verified.

The console and deterministic replay are delivered. `WalletNormalizer` consumes curated `WalletTransaction` inputs; it does not decode Solana instructions. `ReplayDatasetWriter` and `DatasetFingerprint` already produce canonical v1 input. `ReplayDatasetCatalog` imports that input, and `SimulationRuns` persists immutable experiments. The console's address probe retrieves only a small diagnostic sample; it is not a history importer. README explicitly identifies the bundled sample as synthetic.

This is a new increment beyond the [delivered console plan](archive/20261002-console-and-monitoring.md). The weekly plan's historical backfill item requires provider retention and replay fidelity evidence first. This proposal delivers a bounded research capture rather than claiming complete wallet history or a production feed.

## Scope

### In scope

- A user-run, opt-in command to capture up to 100 distinct confirmed transactions referencing the supplied wallet on mainnet. Freeze the selected signature list before fetching details. Include failed transactions in the history audit; only accepted signer buys trigger the existing strategy.
- Raw transaction artifacts, an integrity/coverage manifest and an offline history inspection summary. No provider URL, key, request headers or unsafe provider error text in artifacts.
- A narrow swap decoder selected from actual captured evidence, with unsupported/ambiguous cases explicitly retained in the audit.
- Bounded historical block capture around supported buys and deterministic extraction of same-pair trade observations for entry/exit windows.
- Offline export of an existing v1 dataset, import through the console's existing Datasets screen, and replay through its existing run flow. Clear instructions explaining coverage and modeled results.

### Out of scope

Full lifetime trading history, actual wallet realized P&L/cost-basis accounting, every DEX/route, token-account history expansion, continuous tracking, streaming, notifications, custody or on-chain execution. No new charts, required production integration, database reset, schema migration or dependency.

## Approved key decisions

1. Use Helius standard HTTP RPC for the initial user-run capture, starting with its free account. This follows the provisional [provider spike](../PROVIDER_SPIKE.md). Account entitlements, historical block availability and budgets must be checked by the user before a larger capture; no paid upgrade is implied. Standard RPC keeps the decoder independent of vendor parsing. See [Helius quickstart](https://www.helius.dev/docs/quickstart).
2. Interpret "latest 100" as transactions referencing this wallet, not 100 successful trades. Standard [`getSignaturesForAddress`](https://solana.com/docs/rpc/http/getsignaturesforaddress) searches address references; it does not establish a complete history of current and closed token accounts. Report the discovery scope and counts of signer/non-signer/failed/unsupported outcomes.
3. Keep accepted strategy, fill eligibility, costs, concurrency and exit behavior from [ADR 0001](../adr/0001-fixture-simulation-semantics.md) and [configurable runs](../adr/20261002-configurable-replay-runs.md). New capture does not establish those model assumptions as actual execution costs or Decu's exit policy. CLAUDE's current no-Flyway convention supersedes ADR 0001's historical migration paragraph.
4. Propose `LAST_TRADE` observations from proven supported swap execution amounts in historical blocks. Price is quote quantity divided by base quantity, using exact raw integers and decimals, scale 38, HALF_EVEN. This is an observed trade price, not an executable bid/ask quote. Document its fee treatment and limitations in a new ADR before implementing the extractor. Apply the simulator's existing fee/slippage model explicitly. Do not use native SOL balance changes without separating transaction fees, rent and wrapping; do not assign transaction-wide multi-swap deltas to individual instructions.
5. Missing historical data, unsupported routes and ambiguous attribution remain explicit gaps. Wallet buy prices alone cannot fill later +1/+2/+3 slots. No current-price substitution, minute candles, interpolation, arbitrary pool state reconstruction or invented liquidity.
6. Capture and conversion are separate operations. A saved capture can be decoded and replayed with no provider or credentials. Default application startup, tests and builds never capture data.

## Module contracts

All additions are additive. Existing `WalletActivitySource`, `MarketDataSource`, `ReplayDatasetCatalog`, `SimulationRuns` and v1 schema stay unchanged.

- `walletactivity` owns discovery, RPC transaction DTOs, evidence-backed swap decoding and the history audit. Proposed public `WalletHistoryCapture.capture(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult` and `RecordedWalletHistory.decode(directory: Path, wallet: WalletAddress): WalletHistoryDecodeResult`. Capture results expose artifact references and vendor-free coverage metadata; decode results expose `List<WalletTransaction>` and finite accepted/ignored/ambiguous outcomes. They never expose vendor DTOs or JPA entities. The source directory is a versioned artifact format owned by this module. Recorded/fake implementations make the boundary testable offline.
- `marketdata` owns market block capture and observation extraction. Proposed public `MarketHistoryCapture.capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult` and `RecordedMarketHistory.decode(directory: Path, windows: List<MarketCaptureWindow>): MarketHistoryDecodeResult`. Windows contain pair and inclusive slot bounds; results expose coverage and `List<MarketObservation>`. Raw block DTOs and route/pool decoding remain internal. Fake/recorded implementations accompany the capture adapter.
- `replay` owns the thin CLI entry points and coordination through those public contracts, capture-window calculation, v1 export and loader round-trip validation. Reuse `ReplayDatasetWriter`, never duplicate checksum logic. Proposed opt-in Gradle tasks `captureWalletHistory`, `captureReplayMarkets` and offline `importRecordedReplay`; final arguments and exact run instructions ship with implementation. No command currently exists under these names.
- `console` continues to import generated JSON through the catalog and start runs through `SimulationRuns`. No imports from its internal diagnostic RPC client into other modules. History inspection initially uses the offline audit artifact; no new HTTP job API or persisted job state.

## Domain rules

1. Verify the configured endpoint's genesis identity as mainnet before capturing. Record network/genesis, confirmed commitment, frozen signature selection, capture time from an injected Clock, decoder version and raw artifact hashes. Acquisition time is audit metadata, never a replay business identifier.
2. Freeze up to 100 unique signatures in newest-first discovery order, then fetch [`getTransaction`](https://solana.com/docs/rpc/http/gettransaction). Null/not-retained details, incompatible transaction versions, conflicting identity or slot and malformed responses are explicit outcomes. Do not replace missing members with newer transactions on retry. Preserve the selection for resumable acquisition.
3. Derive signer status from transaction evidence, success from metadata and swap identity from recognized instructions plus owned token-account changes. Resolve versioned account keys and inner instructions correctly. Define a collision-free instruction identity for the selected decoder before coding. Never set `supportedRoute=true` merely because two balances oppose each other.
4. Preserve raw quantities as BigInteger with u64 bounds and actual mint decimals. Decode missing pre/post accounts carefully; absence is not permission to invent decimals, amounts or swap attribution. Reject contradictory decimals, unknown routes, zero base quantity and ambiguous multi-hop/netted transactions explicitly.
5. A transaction without a proven pair is retained in the history audit, not forced into v1 with fabricated mints or amounts. Thus exported transaction count can be below the selected history count; the audit accounts for every selected signature.
6. Calculate entry/exit capture windows using the chosen simulation parameters and all three offsets. For default holding=5, wait=2, potentially needed observations run through source slot +12. Fetch the union of inclusive windows, bound it before requests, and record requested/available/skipped/unavailable slots. Other console configurations may require a new capture; the audit names the supported window parameters.
7. Decode only proven same-pair market trades, retaining signature/instruction identity, exact slot, commitment, price construction and decoder version. Stable observation IDs include source identity and pair. Missing block time is an explicit unusable observation if an honest timestamp cannot be supplied; do not substitute capture time as historical trade time.
8. Keep liquidity absent when depth is unknown. Preserve `LAST_TRADE` quality and document unknown depth. Same-slot prices follow the existing stable-ID tie-break; no intra-slot execution-order guarantee is claimed.
9. Canonical ordering, existing identity checks and fingerprints apply. Identical capture conversion yields identical dataset checksum. Conflicting duplicates fail. Same dataset/config/engine returns the existing database run through the catalog/ledger's existing constraints; no new persistence path bypasses them.
10. Preserve v1 bounds (10,000 transactions/observations, 20 MB import and exact persistence ranges). If normalization exceeds them, fail with counts and an instruction to narrow the capture; never silently truncate observations. Remote failure cannot publish a capture as complete or overwrite a previous good dataset.
11. Existing engine behavior remains: missing entry observations reject, missing exits leave positions open with an explicit reason; closed-position P&L is quote-denominated paper P&L. No result is labeled actual Decu profit or verified executable performance.

## Data model

No schema change, production SQL, JPA entity changes or database reset. Existing `replay_datasets` checksum primary key and simulation run identity constraints provide persisted idempotency. The existing per-run input snapshot stays intact.

Raw captures and audits live under ignored `build/captures/` by default. Manifest format is separately versioned, references relative artifact paths and records byte counts/hashes, selected signatures, decoder status counts, market gaps and dataset checksum. Paths must stay within the chosen capture directory; retries validate hashes before reusing artifacts and reject conflicting contents. Write files atomically and preserve incomplete status on interruption. Local artifacts are user-managed research evidence; document that Gradle clean removes build output and that required evidence must be exported before cleaning. No automatic retention/deletion job.

## Transactions and integration

- A standalone capture command makes reads outside Spring database transactions; offline export opens no database. Existing console registration and run persistence keep their short service-owned transactions after all remote acquisition is finished.
- Use existing Spring RestClient/Jackson 3; no SDK or new dependency. Dedicated adapters stay in the owning module, with shared vendor-free transport configuration only if needed to avoid duplication and preserve module boundaries.
- Proposed module-owned validated properties `walletactivity.capture` and `marketdata.capture`: optional endpoint from the existing `SOLANA_RPC_URL` environment variable, connect timeout 5 seconds, response timeout 15 seconds, one in-flight request, maximum 2 requests/second, maximum 3 attempts per transient read. Capped exponential backoff with injected jitter/delay, honoring Retry-After within a total operation deadline. Authentication/malformed responses fail without retries. Unconfigured capture fails clearly; ordinary startup remains unaffected. No local/prod YAML edit or new required production secret.
- Before market capture, show requested slot count and require a bounded request/byte budget supplied by the user. Enforce it across retries and streamed response bodies. No unlimited historical loop or automatic paid fallback. The command reports request/byte usage, not guessed billing credits.
- Allow only genesis/history/transaction/block read methods needed by these commands. Never build, simulate, sign or send transactions. Treat provider exception messages and URL-containing response text as secret-bearing; emit only sanitized operation/category/status/attempt metadata.
- The agent implements and tests with sanitized fixtures/fakes only. The user runs all live acquisition and shares sanitized payloads or audit summaries if decoding needs investigation. No live-provider call is authorized for the agent by approving this plan.

## What to implement

1. **Auditable real history capture.** Write failing tests for frozen selection, bounded pagination, detail acquisition, mainnet verification and safe artifacts; deliver user-run capture plus offline inspection counts. User supplies a sanitized successful swap and failed/unsupported sample from the captured set. This resolves the actual route evidence without speculative all-DEX support.
2. **Verified supported swap decoding.** Record provider/scope, price construction and instruction identity in a new ADR. Select the first narrow decoder from the evidence and document exact supported program/version/route shape. Write its tests before implementation. If no captured route can be verified, present the evidence and revised scope; do not claim this unit complete or substitute fixture data.
3. **Slot-aware market evidence.** After unit 2 identifies pairs/routes, acquire bounded historical block windows using a separate user-run command. Test extraction and gaps offline. Validate availability for at least one recognized buy before claiming the provider satisfies real replay requirements. Missing retention/route coverage is reported rather than prompting an automatic paid upgrade.
4. **Real replay through the existing console.** Export canonical v1 input and audit, validate loader round-trip, import in Datasets and start the existing immutable run. Supply manual instructions for inspecting source events, +1/+2/+3 fills, exact observation references, exits/rejections, report and repeat-run idempotency.
5. **Review and verification.** Review all additions against strict rules, module boundaries, slot-price evidence and secret handling. Run backend/frontend verification and update docs. Archive only after deliverables and evidence are complete; retain a limitation record if the real capture lacks sufficient observations.

## Verification

Test-first offline classes: `WalletHistoryCaptureTest` (selection/dedupe/pagination/null/details/version/limits/network), `SwapTransactionDecoderTest` (actual sanitized supported/failed/unsupported/ambiguous cases, signer, inner instructions, token accounts, decimals and u64), `MarketHistoryCaptureTest` (window union, skipped/unavailable slots, budgets), `RecordedMarketHistoryTest` (price math, correct pair, source identity, timestamps, missing depth), `RecordedReplayImportTest` (canonical round-trip, checksums, no silent truncation, manifest gaps). Fake HTTP adapter tests prove timeouts, 429/Retry-After, bounded retries, no unsafe logs/files and no writes. No test contacts a live provider.

PostgreSQL Testcontainers integration reuses the replay/catalog flow with a sanitized captured example and asserts persisted provenance, exact hand-computed paper economics, repeat/concurrent registration and run idempotency, and rollback without partial records. Missing-price fixtures assert explicit rejection/open-position outcomes. Frontend tests cover any instructional behavior actually added; chart behavior stays covered by the delivered console tests.

Run serially with Java 21 and Docker: `export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10"` then `./gradlew build`. Frontend: `npm.cmd --prefix frontend run lint`, `npm.cmd --prefix frontend test -- --run`, `npm.cmd --prefix frontend run build`. Report actual commands/results. User-run live evidence is separately labeled, with no provider credential in the report.

## Documentation sync

README: real capture command/environment setup, artifact lifetime, history scope, supported routes, budgets, console import/run steps and limits. `docs/REPLAY_FORMAT.md`: canonical export/provenance references without changing v1. `docs/PROVIDER_SPIKE.md`: adopted research scope and user-observed retention/coverage evidence, keeping unverified capabilities explicit. New ADR for capture scope, verified decoder identity and LAST_TRADE construction; implementation status/weekly roadmap updated consistently with the additive increment.

## Acceptance criteria

- The user can capture the selected mainnet wallet history locally; audit outcomes account for every selected signature, including unavailable/failed/non-signer/unsupported records.
- At least one supported real buy is decoded from preserved evidence with actual decimals, signature, instruction identity and slot. If none is supported, that is a scope blockage, not a successful real-data delivery.
- Market observations used in the demonstration can be traced to preserved same-pair historical transactions and exact slots. Available data demonstrates at least one filled scenario with an evidence-backed exit; insufficient coverage is reported and does not fabricate closed P&L.
- Generated v1 JSON imports in the current console and produces inspectable +1/+2/+3 outcomes. Repeating conversion/import/run preserves the checksum/identities and row counts.
- Default builds/startup remain provider-free; tests pass offline. No secrets in artifacts/logs, no Solana writes, no schema/dependency/profile changes.

## Amendment A — successful-reference discovery

Status: Approved (2026-10-03, user approved in chat). Implemented and reviewed on 2026-10-03; the user's live capture then completed with it (see Progress).

### Context

The user's live captures (see Progress) showed the problem. The latest 100 references to the wallet are all failed transactions, packed into 9 consecutive slots. In the one inspected sample, the wallet is a read-only non-signer in another account's failed transaction: an unidentified program (`7JwTi9…`) alongside Pump.fun failed before any swap. Key decision 2 ("latest 100 references, failed included") therefore selects third-party noise and is unlikely to contain a signer swap for the decoder gate. Today `WalletHistoryCaptureService.discover` selects every reference in 50-signature pages and uses the last *selected* signature as its cursor.

### Decision (replaces key decision 2 and domain rule 2's selection sentence)

Select the latest **N successful** transactions referencing the wallet (N = `limit`, 1..100, default 100). Discovery scans `getSignaturesForAddress` newest-first in pages of 1,000 signatures. It skips every entry whose `err` is non-null, so no detail request is spent on failed transactions, and counts each skipped entry in the audit.

The scan is bounded by `scanPages` (default 20, maximum 50, so at most 50,000 signatures). Discovery stops at the first of:
- N successful references selected (`LIMIT_REACHED`),
- a short or empty page (`HISTORY_END`),
- the page bound (`SCAN_BOUND`).

`SCAN_BOUND` with fewer than N selected is a valid, completed discovery for the bounded scope. The audit names the stop reason and does not call it a full history.

Failed transactions are no longer fetched or stored. The audit keeps their count and the scanned range, not their signatures. Successful references by other signers are still fetched and recorded as `NON_SIGNER`, so the audit shows how many selected transactions the wallet actually signed. Detail fetching, classification, artifacts, safety guards, budgets and the v1 version support are otherwise unchanged.

### Rejected alternatives
- **More pages of all references:** spends detail requests on failed noise.
- **Helius-specific filtered history APIs:** these return a vendor-parsed contract, which conflicts with key decision 1's independence from vendor parsing.
- **Signer-only selection:** no standard RPC filters by signer at discovery, so it would need a detail request per candidate.

### Module contracts (all in `walletactivity`; replay/api is the only consumer)

- `WalletHistoryCaptureRequest` gains `scanPages: Int`. The default and maximum go in `WalletHistoryCaptureLimits` (`DEFAULT_SCAN_PAGES = 20`, `MAX_SCAN_PAGES = 50`).
- `WalletHistorySelection` drops `discoveryFailed`: every selected entry is successful by construction.
- `WalletHistoryManifest` gains:
  - `scanPageLimit: Int`
  - `scannedSignatures: Int`
  - `skippedFailed: Int`
  - `scanCursor: WalletHistoryScanCursor?` (signature and slot of the last *scanned* entry)
  - `discoveryStop: WalletHistoryDiscoveryStop?` (enum `LIMIT_REACHED`, `HISTORY_END`, `SCAN_BOUND`; null while discovery is incomplete)

These are additive public type changes plus one removed field. They do not change the replay v1 dataset schema.
- **Manifest schema version 2.** Version 1 directories (the two diagnostic captures already made) are rejected for resume and inspection with category `MANIFEST_VERSION`. Captures go into a new directory.
- **CLI:** `--scan-pages <1..50>` and Gradle property `-PscanPages` (default 20). The summary prints `scope=LATEST_SUCCESSFUL_WALLET_REFERENCES`, `scanned=`, `skippedFailed=`, `scanPages=` and `discoveryStop=`.

### Domain rules

1. Only entries with `err == null` in the signature listing enter the selection. The selection keeps newest-first order, distinct signatures, and at most N entries.
2. **Cursor and resume.** The cursor is the last *scanned* entry, not the last selected one. Every page checkpoint saves the selection, counters and cursor together. A resume continues `before` that cursor and never admits newer signatures. A page that holds only failed entries still advances and checkpoints the cursor.
3. **Deterministic page handling.** When the Nth success is found mid-page, scanning stops at that entry. `scannedSignatures`, `skippedFailed` and the cursor count entries only up to and including it. Repeating the same pages produces an identical manifest.
4. **Ordering checks span skipped entries too.** A page whose first slot is newer than the cursor's slot, or with ascending slots inside it, fails with `SIGNATURE_ORDER`. A non-advancing cursor fails with `NON_PROGRESSING_PAGE`. A duplicate signature with a conflicting slot or error fails with `CONFLICTING_SIGNATURE`.
5. **Discovery and detail must agree.** If a listing entry was successful but its fetched detail has a non-null `meta.err`, the detail contradicts discovery. It is recorded as `INVALID_TRANSACTION` with no artifact, and the capture stays incomplete.
6. **Page bounds.** A listing page larger than requested fails with `SIGNATURE_PAGE`. Each page is still bounded by the 8 MiB response cap and the shared request/byte budgets. Exhausting the budget mid-scan leaves discovery incomplete and resumable from the saved cursor without rescanning.
7. **Request validation.** `scanPages` outside 1..50 is rejected before any provider call, by both the argument parser and the service.
8. **Memo text** in signature listings is still never persisted.

### Data model, transactions and integration

No schema, entity, dependency, profile or Gradle-dependency change. The new Gradle property is added to the existing `captureWalletHistory` task arguments only. Remote reads stay outside any database transaction. The read-only methods, rate limit, retries, timeouts and credential handling are unchanged.

A default run costs at most 1 genesis request + 20 listing pages + 100 detail requests = 121 requests, within the 400-request default budget.

### Verification (test-first, offline fakes only)

`WalletHistoryPaginationTest` / `WalletHistoryCaptureTest` must prove:
- failed entries are skipped and counted, and are never detail-fetched;
- the selection stops at exactly N, with deterministic counters and cursor when that happens mid-page;
- each stop reason: `HISTORY_END`, `SCAN_BOUND` with fewer than N, and `LIMIT_REACHED`;
- a page of only failed entries advances and checkpoints the cursor;
- an interrupted scan resumes from the cursor without admitting newer signatures or rescanning;
- the ordering, duplicate and conflict checks still hold across skipped entries;
- `scanPages` bounds are enforced, with no provider call;
- a discovery-successful but detail-failed transaction becomes `INVALID_TRANSACTION`;
- a version 1 manifest is rejected with `MANIFEST_VERSION`.

`WalletHistoryCommandsTest` covers `--scan-pages` validation and the new summary lines. Then run `./gradlew build` with Java 21 and Docker.

### Documentation sync

- **REAL_DATA_CAPTURE.md:** scope, `-PscanPages`, cost, stop reasons, "use a new directory".
- **README** capture paragraph.
- **New ADR** `docs/adr/<date>-successful-reference-discovery.md` that supersedes only the selection bullet of the bounded-capture ADR. The accepted ADR is not rewritten.
- **This plan:** key decision 2 and domain rule 2 marked as amended.

### Acceptance criteria

- A user-run capture of the wallet reports `scope=LATEST_SUCCESSFUL_WALLET_REFERENCES`, the scanned and skipped-failed counts, and a `discoveryStop` reason. `selected` equals N, unless the stop is `HISTORY_END` or `SCAN_BOUND`.
- No failed signature from the listing is detail-fetched.
- Every selected signature has a resolved outcome or keeps the capture incomplete.
- Offline inspection verifies the version 2 manifest and every artifact hash.

### Open questions and evidence gate

- **Copy-bot successes may still dominate:** successful third-party transactions referencing the wallet could make most of the 100 `NON_SIGNER`. Recommendation: run the amended capture and read the `NON_SIGNER` / `UNCLASSIFIED` split. If no signer transaction appears within 50 pages, report it as evidence and revisit scope with the user. Do not keep raising bounds automatically.
- **Scan defaults:** recommend 20 default and 50 maximum pages. A larger bound or a time/slot window instead of a page bound is a separate user decision.

## Amendment B — mint-referenced market capture (unit 3)

Status: Approved (2026-10-04, user approved in chat). Implemented and reviewed on 2026-10-04; waiting on the user's live market capture.

### Context

Unit 2 decodes one real buy, the Pump.fun bonding-curve buy `35Vnpu9D…` at slot 453004996 for mint `5bYUih…`. Accepted semantics need same-pair observations in slots S+1..S+5 for the +1/+2/+3 entries (2-slot wait and age), and up to S+12 for exits (holding 5, same wait). Unit 3 as approved fetches whole historical blocks. Measured mainnet traffic makes that impractical:
- A fully parsed block is regularly far above the 8 MiB per-response cap.
- Parsing a whole block into memory risks running out of memory.
- Almost all of a block's transactions are unrelated to the pair.

The capture transport, budgets, artifact store and parsed-structure validation, and the Pump.fun trade-event reader, all live inside `walletactivity` today. `marketdata` cannot import them, and copying them would break the no-duplication rule.

### Decisions (replace "bounded historical block capture" for unit 3)

1. **Discovery by mint, not by block.**
   - For each decoded buy, discovery scans `getSignaturesForAddress(<mint>)` newest-first in 1,000-signature pages, with `until` = the source buy's signature, so the scan never goes older than the source trade.
   - It skips failed entries and collects successful signatures whose slot falls in the window [S+1, S+12].
   - It stops once a page reaches below S+1, or at history end. The page bound is `scanPages` (default 20, maximum 50; raised to 500 and made extendable by Amendment C).
   - Every Pump.fun bonding-curve trade passes the mint account, so this finds exactly the same-pair trades.
   - Hitting the page bound before reaching the window marks it `COVERAGE_INCOMPLETE`. It is never treated as "no trades".
2. **Window parameters are recorded.** The window comes from the accepted defaults (offsets 1–3, wait 2, age 2, holding 5). Other console settings that need a wider window require a new capture, and the manifest names the supported parameters. Overlapping windows on the same mint are merged.
3. **Bounded detail fetch.** Successful in-window signatures are fetched with `getTransaction` (v1, parsed), with the same structure validation, credential-echo and unsafe-text guards, and hashed atomic artifacts as wallet capture. There is a total cap on details (`maxTransactions`, default 300, maximum 1,000). A window with more in-window signatures than the cap is recorded as `OVER_CAP` and the capture is incomplete, never silently truncated. Budgets, rate limit, retries, timeouts, lock, resume and manifest verification work as in wallet capture.
4. **Observation construction.** This needs a new ADR, `docs/adr/<date>-pumpfun-last-trade-observations.md`, before the extractor ships. Each valid Pump.fun `TradeEvent` instruction (buy or sell, any user) in a fetched transaction becomes one `LAST_TRADE` observation:
   - `pair`: event mint vs `WRAPPED_SOL`, the same representation the user chose in unit 2.
   - `price` = (`sol_amount` × 10^decimals) ÷ (`token_amount` × 10^9) at scale 38, HALF_EVEN. That is the curve amounts with fees excluded, the same basis as unit 2.
   - Token decimals come from the transaction's token balances for that mint. If they are absent or conflicting, the observation is excluded and counted.
   - `slot` = `priceSlot` = the trade slot.
   - `observedTime` = the block time. If the block time is null, the trade is excluded and counted; capture time is never substituted.
   - `liquidityQuote` absent (depth unknown); `commitment` confirmed.
   - `source` `pumpfun-bonding-curve-trade/v1`.
   - Stable `id` and `sourceEventId` from signature + top-level index + inner index + mint.
   - Zero amounts or an `is_buy` other than 0/1 are excluded and counted. Several events in one transaction each give their own observation. Same-slot ties use the existing stable-ID ordering.
   - A graduated token (PumpSwap) or any other route yields no observations. The simulator then records missing-observation rejections or open positions as it already does.
5. **Module structure.** Move the existing read-only capture infrastructure (transport, budget, timing, artifact store primitives, parsed-structure validation, guards) into a new vendor-neutral module `com.trenching.bot.chaincapture`. Move the Pump.fun instruction/event layout and reader into `com.trenching.bot.protocol.pumpfun`. Both get narrow public APIs and no Spring or database dependencies.
   - `walletactivity` keeps wallet discovery, its manifest, classification and the buy decoder, and uses the shared modules.
   - `marketdata` adds `MarketHistoryCapture` / `RecordedMarketHistory` (public), its market manifest and the observation extractor.
   - This is a behaviour-preserving move. Every existing capture/decoder test must stay green unchanged except for package imports.
6. **Commands.**
   - `captureReplayMarkets` (user-run; needs `SOLANA_RPC_URL`). Arguments: `-PwalletCaptureDirectory`, `-PcaptureDirectory`, `-PscanPages`, `-PmaxTransactions`, `-PrequestBudget`, `-PbyteBudget`. Before any request, it validates that the wallet capture is complete and decodes it.
   - `inspectReplayMarkets` (offline). It verifies artifacts and prints per-window coverage, signature, transaction and observation counts, exclusion reasons, and up to 3 sample observations (id, slot, price).
   - Neither starts Spring Boot or PostgreSQL.

### Out of scope for this amendment

Export to replay schema v1, console import and running the simulation (unit 4). Additional routes (sells by the wallet, PumpSwap, other DEXes). Reserve-derived spot prices or depth. History-scope changes for more wallet trades.

### Verification (test-first, offline fakes and the two existing real fixtures only)

- The move refactor keeps all existing tests passing.
- `MarketHistoryCaptureTest`:
  - window derivation and merging; `until` and stop rules; skipping failed and out-of-window entries;
  - `COVERAGE_INCOMPLETE` at the page bound, `OVER_CAP`, budgets, and resume without newer admissions;
  - an incomplete or wallet-mismatched wallet capture refused before any request.
- `RecordedMarketHistoryTest`:
  - price math at scale 38 against hand calculations; decimals from token balances;
  - null block time, zero-amount, malformed-event and multi-event exclusions; stable ids;
  - log-only events ignored; manifest and artifact tamper rejection.
  - The real buy fixture must yield an observation whose price equals 244,444,444 × 10^6 ÷ (1,238,520,682,656 × 10^9) at scale 38, HALF_EVEN.
- Command tests cover argument validation, offline inspection output and never printing raw JSON.
- `./gradlew build` with Java 21 and Docker.

### Acceptance criteria

- A user-run `captureReplayMarkets` against the decoded buy reports the window [453004997, 453005008]: its coverage status, in-window successful signatures fetched, and the observations extracted with exclusion counts.
- `inspectReplayMarkets` re-verifies offline.
- No observation without an exact slot, block time, mint decimals and event identity.
- The default build stays provider-free.

### Open questions

- **Availability:** whether the account can still list and fetch these mint signatures is verified by the user's first run. Recommendation: one buy first (the only one), which is the plan's own "validate one before the batch" gate.
- **Timing:** recommend running the market capture soon. An active token's later trades add listing pages before the scan reaches the window. The page bound makes that explicit, not silent.

## Amendment C — extendable market scan

Status: Approved (2026-10-04, user approved in chat).

### Context

The user's first market capture (`-PscanPages=50`, 51 requests, about 11.9 MB) stopped at the page bound with the window [453004997, 453005008] `COVERAGE_INCOMPLETE`. The mint listed 50,000 signatures (31,527 newer than the window, 18,473 failed, none in the window) and the cursor reached slot 453009699, about 4,700 slots short. A `COVERAGE_INCOMPLETE` window was terminal and a rerun with a different `scanPages` was refused, so the only way forward was a new directory with the same 50-page limit. The wallet capture holds no later transaction on this mint to start a narrower scan from.

### Decisions (amend Amendment B decision 1)

1. **Market page bound 500.** `scanPages` for market capture is 1–500 (default 20). The wallet capture bound stays 1–50; a busy mint lists far more signatures after a window than a wallet does.
2. **Extend on resume.** A rerun may pass a `scanPages` at least the saved one. A larger value raises the manifest's `scanPageLimit` and reopens every `COVERAGE_INCOMPLETE` window to `PENDING`. It continues before its saved cursor with its counts carried on. A smaller value stays `RESUME_IDENTITY`. Windows, window parameters and `maxTransactions` must still match.
3. **Lossless bound.** A window that reaches the page bound keeps its in-window selection (and `selected` equals its size) so an extension never drops signatures. It is still never fetched or admitted while `COVERAGE_INCOMPLETE`.
4. **Record order.** Records are always the distinct selections of `COMPLETE` windows in window order, whichever window completed first. The manifest check that windows start in order now requires only that windows not yet started form a suffix, since an extended window can reopen before later finished ones.
5. **Unchanged:** coverage rules and their effect on observations; budgets as totals across runs (maximum 1,000 requests and 500 MiB); the RPC methods; manifest schema version 1. An existing manifest stays valid.

### Rejected alternative

Fetching the window's 12 blocks with `getBlock` (accounts only). Fixed cost of about 13 requests, but it adds a fourth RPC method and each response can approach the 8 MiB cap. It remains a fallback if 500 pages do not reach the window.

### Verification

`MarketHistoryCaptureTest`: an extension continues from the saved cursor without relisting; a bounded window keeps in-window selections and admits them once extended; an earlier window extended after a later one completed keeps records in window order; 500 pages accepted and 501 refused; a smaller `scanPages` still refused. `RecordedMarketHistoryTest`: a `COVERAGE_INCOMPLETE` window with a kept selection still yields no observations. `ReplayMarketCommandsTest`: `--scan-pages 501` refused.

## Progress

Plan remains active; the feature is not delivered.

- **Unit 1 — auditable real history capture: implemented, reviewed and fixed (2026-10-03); waiting on the user's live capture.** `captureWalletHistory` and `inspectWalletHistory` are delivered as described in [REAL_DATA_CAPTURE.md](../REAL_DATA_CAPTURE.md). The review fixed seven findings:
  - The transport now refuses any response that repeats a configured endpoint fragment (raw or decoded, case-insensitive).
  - Public on-chain text containing links or credential-like keywords no longer blocks discovery or later signatures. Signature memos are never persisted, and such transactions become the resolved, artifact-free gap `WITHHELD_UNSAFE_TEXT`.
  - String-form transaction errors are `FAILED`.
  - Typed `jsonParsed` structure validation keeps malformed data `INVALID_TRANSACTION`, and classification never throws.
  - Legacy, v0 and v1 transactions are requested. The review first narrowed this to v0, which was wrong; see the live capture notes below. Later versions are `UNSUPPORTED_VERSION`.
  - Unreferenced crash-orphan artifacts are replaced atomically.
  - An exclusive directory lock refuses concurrent captures.
- **Unit 1 offline verification:** `./gradlew build` (Java 21, Docker 28.0.4) passed with 234 tests, 0 failures, 0 skipped. That includes 60 capture tests: `WalletHistoryCaptureTest` 16, `HttpWalletHistoryRpcTest` 17, `WalletTransactionEvidenceTest` 9, `WalletHistoryCommandsTest` 12, `WalletHistoryPaginationTest` 4, `WalletHistoryCaptureConfigurationTest` 2. No test or agent contacted a live provider.
- **User-run live capture evidence (2026-10-03).** These are the user's runs; no agent contacted the provider.
  - **Run 1 (v0 requests):** 103 requests. All 100 latest references were `UNSUPPORTED_VERSION`, because mainnet carries v1 transactions, as `postman/README.md` had already recorded for devnet.
  - **Run 2 (v1 requests):** 103 requests, about 1.37 MB. All 100 were `INVALID_TRANSACTION`.
  - **Cause of run 2:** the user fetched one sample and confirmed it does not contain their key. It showed two fields the allowlist did not expect: `result.transactionIndex`, and the v1 `message.transactionConfig` (`priorityFee`, `computeUnitLimit`, `loadedAccountsDataSizeLimit`, `heapSize`). Both are now type-checked and accepted. `./gradlew build` passed with 234 tests.
- **Scope gate found by the live capture.** The 100 latest references are all failed transactions, packed into 9 consecutive slots (452996852–452996860). In the inspected sample, the wallet is a read-only non-signer, and the signer is another account calling an unidentified program (`7JwTi9…`) alongside Pump.fun (`6EF8rr…`). The program failed with custom error 7 before any swap. This is consistent with third-party copy-trading activity, though that is not verified. Under that pattern, "latest 100 references" is unlikely to contain a signer swap. Changing discovery (for example, selecting successful references only, with a bounded deeper scan and audited skip counts) changes approved key decision 2 and needs the user's approval.
- **Amendment A implemented (2026-10-03).**
  - Capture now selects the latest successful references with a bounded 1,000-signature page scan (`-PscanPages`, default 20, maximum 50).
  - The manifest is now version 2 and records scanned and skipped-failed counts, the scan cursor and the stop reason. Version 1 directories are refused with `MANIFEST_VERSION`.
  - A transaction whose details contradict a successful listing is `INVALID_TRANSACTION`.
  - New decision record: [successful-reference discovery](../adr/20261003-successful-reference-discovery.md).
  - Review of the change found no further issues. `./gradlew build` passed with 246 tests, 0 failures, 0 skipped. The 72 capture tests: `WalletHistoryCaptureTest` 20, `WalletHistoryPaginationTest` 12, `HttpWalletHistoryRpcTest` 17, `WalletHistoryCaptureConfigurationTest` 2, `WalletHistoryCommandsTest` 12, `WalletTransactionEvidenceTest` 9.
- **Unit 2 — verified swap decoding: implemented (2026-10-03).**
  - The user-run successful-reference capture (6,911 scanned, 6,811 failed skipped, 100 selected, about 40 seconds of history) contained 99 `NON_SIGNER` and 1 `UNCLASSIFIED`. That one is a Pump.fun bonding-curve buy through router `FLASHX8…`.
  - The user chose the representation: wrapped SOL as the quote, curve `sol_amount` as the price basis with fees excluded, and Pump.fun buys accepted through any router. It is recorded in [the decoder ADR](../adr/20261003-pumpfun-bonding-curve-buy-decoder.md).
  - `RecordedWalletHistory.decode` verifies the capture, then emits `WalletTransaction`s. The real buy decodes to +1,238,520,682,656 raw (6 decimals) against −244,444,444 lamports, and the existing `WalletNormalizer` accepts it as a BUY.
  - `inspectWalletHistory` prints the decode counts and the decoded trades.
  - `./gradlew build` passed with 267 tests, 0 failures, 0 skipped.
- **History-scope gate (open, user decision).** Decu's own trades are rare among the wallet's references: 1 in 6,911, over about 40 seconds. A dataset with several entries needs a different history scope, such as the wallet's token accounts or a provider-filtered history; see the decoder ADR's Consequences.
- **Unit 3 — slot-aware market evidence: implemented per Amendment B (2026-10-04); waiting on the user's live market capture.**
  - **Part 1:** behaviour-preserving extraction of `chaincapture` (read-only transport, budgets, artifact store, structure checks) and `protocol.pumpfun` (layout and reader). A byte-for-byte before/after fingerprint was identical.
  - **Part 2:**
    - `marketdata` `MarketHistoryCapture` / `RecordedMarketHistory`. The decode signature is `decode(directory)`: windows come from the verified manifest.
    - `replay` window planning: [S+1, S+12] from `CopyBuyStrategy.ENTRY_OFFSETS` and `SimulationConfig.mvp()`, with same-mint windows merged.
    - Commands `captureReplayMarkets` (user-run) and `inspectReplayMarkets` (offline).
  - **Observations** follow [the observation ADR](../adr/20261004-pumpfun-last-trade-observations.md). Two cases the ADR did not name were added to it at review:
    - an extra exclusion reason, `EXCEEDS_REPLAY_BOUNDS`;
    - a window with a detail gap yields no observations.
  - **Real fixture:** the buy yields exactly one observation, price `0.00000019736807582073671197813450558296` at slot 453004996.
  - **Verification:** `./gradlew build` passed with 317 tests, 0 failures, 0 skipped.
- **Amendment C implemented (2026-10-04)** after the user's first market capture stopped `COVERAGE_INCOMPLETE` at 50 pages (cursor slot 453009699). Waiting on the user's extended capture.
- **Live market capture evidence (2026-10-04, user-run).** The 50-page run stopped `COVERAGE_INCOMPLETE` (51 requests). The extended run (`-PscanPages=500`, 216 requests in total, about 48.3 MB) completed: 196,130 signatures scanned, 110,192 failed and 85,921 newer skipped, 17 in-window signatures, all captured. They yield 17 observations in slots 453004997–453005007, with no exclusions and no detail gaps.
- **Unit 4 — real replay through the existing console: export implemented (2026-10-04); waiting on the user's console import.**
  - `exportRecordedReplay` is offline. The plan's proposed name `importRecordedReplay` was changed because the command writes a file and the console does the import. It reuses the wallet planning of `captureReplayMarkets`, extracted into `RecordedWalletPlanning`. It requires the market capture to hold exactly the planned windows and parameters (`MARKET_CAPTURE_MISMATCH`), complete and gap-free (`MARKET_CAPTURE_INCOMPLETE`), refuses more than 10,000 records (`DATASET_BOUNDS`), writes through `ReplayDatasetWriter`, loads the JSON back, and writes atomically without replacing a different file (`OUTPUT_EXISTS`). Refused commands now also print the validation reason code.
  - Real dataset `3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d` (1 transaction, 17 observations). Default run: +1, +2 and +3 all fill at their target slot and close on `MAX_HOLDING_SLOTS`, realized −0.469035946332, −0.689058352601 and −2.422146778200 (total −3.580241077133 in wrapped SOL on 100 notional), hand-checked from the observed prices. The +3 exit waited one slot because slot 453005004 has no trade.
  - Tests: `RecordedReplayExportTest` (real buy and real in-window trade round-trip, repeat export, refusals), `RecordedReplayCommandsTest`, and `RecordedReplayIntegrationTest` (PostgreSQL: import by checksum, exact closes, repeat run with no new rows). `./gradlew build` passed with 333 tests, 0 failures, 0 skipped.
- **Unit 5:** not started. Unit 4 exports the decoded wallet buys and market observations to replay schema v1, then imports and runs them in the console. Unit 5 is the final review and verification.

## Open questions and evidence gates

- **Approval resolved:** the user approved this plan on 2026-10-03. Helius research capture, wallet-reference discovery with confirmed signer-buy strategy, additive contracts and LAST_TRADE price construction are approved. Agent live calls and purchases remain prohibited.
- **Decoder evidence:** which programs/route shapes occur in these 100 transactions? Unanswered until the user runs capture and supplies sanitized examples. Recommendation: support one verified route first; separately approve any fidelity-changing treatment for more complex swaps.
- **Historical availability/budget:** can this account retrieve relevant blocks and supported market trades within an acceptable budget? Recommendation: validate one supported trade's windows first, then run the bounded batch. If this fails, choose a narrower evidenced capture or separately decide a market source/paid entitlement. Do not claim a provider key alone solves this.
