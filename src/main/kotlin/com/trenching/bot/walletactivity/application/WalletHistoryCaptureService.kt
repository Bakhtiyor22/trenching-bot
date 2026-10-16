package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.BoundedParallelFetch
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureStop
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.DetailBatches
import com.trenching.bot.chaincapture.DetailFetch
import com.trenching.bot.chaincapture.FetchedItem
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.common.MintAddress
import com.trenching.bot.protocol.solana.SolanaPrograms
import com.trenching.bot.walletactivity.*
import com.trenching.bot.walletactivity.infrastructure.capture.TokenTarget
import com.trenching.bot.walletactivity.infrastructure.capture.WalletCaptureFormat
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import java.time.Clock
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

internal class WalletHistoryCaptureService(
    private val rpc: SolanaReadRpc,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val concurrency: Int = 1,
    private val observer: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE,
) : WalletHistoryCapture {
    private val log = LoggerFactory.getLogger(WalletHistoryCaptureService::class.java)
    private val store = WalletHistoryArtifactStore(objectMapper)
    private val evidence = WalletTransactionEvidence()
    private val detailBatches = DetailBatches(BoundedParallelFetch(), observer, concurrency, WalletCaptureFormat.DETAIL_CHECKPOINT_INTERVAL, "wallet_capture")
    private val verification = WalletTokenVerificationCapture(rpc, store, CapturedArtifactReader(store, PumpFunBuyDecoder()), concurrency, observer)

    override fun capture(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult {
        validate(request)
        return store.lock(request.directory).use { acquire(request) }
    }

    private fun acquire(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult {
        val saved = store.loadIfPresent(request.directory)?.copy(schemaVersion = WalletCaptureFormat.version(request.scope))
        if (saved != null && (saved.wallet != request.wallet.value || saved.scope != request.scope ||
                saved.limit != request.limit || saved.scanPageLimit != request.scanPages || saved.targetMint != request.targetMint?.value)) {
            throw CaptureValidationException("RESUME_IDENTITY")
        }
        val budget = CaptureBudget(request.requestBudget, request.byteBudget, saved?.requestsUsed ?: 0, saved?.bytesUsed ?: 0, request.operationTimeout, request.usage)
        val genesis = ChainCaptureReads.requireMainnet(rpc, budget)
        var manifest = saved ?: started(request, genesis, budget)
        checkpoint(request, manifest, budget)
        val stop = CaptureStop()
        if (!manifest.discoveryComplete) {
            manifest = discover(request, manifest, budget, stop)
            checkpoint(request, usage(manifest, budget), budget)
        }
        if (!manifest.discoveryComplete) return finish(request, manifest, budget, stop)
        manifest = usage(fetchDetails(request, manifest, budget, stop), budget)
        if (manifest.targetMint != null && stop.reason == null && WalletCaptureFormat.recordsResolved(manifest)) {
            checkpoint(request, manifest, budget)
            manifest = verification.verify(request.directory, manifest, budget, stop) { next ->
                usage(next, budget).also { checkpoint(request, it, budget) }
            }
        }
        return finish(request, manifest, budget, stop)
    }

    private fun fetchDetails(request: WalletHistoryCaptureRequest, saved: WalletHistoryManifest, budget: CaptureBudget, stop: CaptureStop): WalletHistoryManifest {
        val unresolved = saved.selection.withIndex().filter { (index, _) -> saved.records[index].outcome in WalletCaptureFormat.UNRESOLVED_OUTCOMES }
        return detailBatches.run(
            items = unresolved, initial = saved, budget = budget, stop = stop,
            signatureOf = { it.value.signature },
            fetchOne = { fetchDetail(it.value, saved, budget) },
            apply = { manifest, batch -> withDetails(request, manifest, batch.completed, batch.unavailable) },
            checkpoint = { checkpoint(request, usage(it, budget), budget) },
            report = { phase ->
                log.info("wallet_capture details wallet={} scope={} requestsSent={} cacheHits={} rateLimitedResponses={} retries={} elapsedMs={} concurrency={}",
                    saved.wallet, saved.scope, phase.requestsSent, phase.cacheHits, phase.rateLimitedResponses, phase.retries, phase.elapsed.toMillis(), concurrency)
            },
        )
    }

    private fun fetchDetail(selection: WalletHistorySelection, manifest: WalletHistoryManifest, budget: CaptureBudget): DetailFetch<WalletHistoryOutcome> =
        DetailFetch.request(rpc, selection.signature, budget) { evidence.classify(it, selection, manifest.wallet, manifest.listingAddress) }

    /** Applies the completed details to their records in selection order; the record that failed on the provider stays retryable. */
    private fun withDetails(
        request: WalletHistoryCaptureRequest,
        saved: WalletHistoryManifest,
        completed: List<FetchedItem<IndexedValue<WalletHistorySelection>, DetailFetch<WalletHistoryOutcome>>>,
        unavailable: IndexedValue<WalletHistorySelection>?,
    ): WalletHistoryManifest {
        val records = saved.records.toMutableList()
        for (done in completed) {
            val (index, selection) = done.item
            records[index] = detail(request, selection, done.result)
        }
        unavailable?.let { (index, selection) ->
            records[index] = WalletHistoryRecord(selection.signature, selection.slot, WalletHistoryOutcome.PROVIDER_UNAVAILABLE)
        }
        return saved.copy(records = records.toList())
    }

    private fun detail(request: WalletHistoryCaptureRequest, selection: WalletHistorySelection, fetched: DetailFetch<WalletHistoryOutcome>): WalletHistoryRecord = when (fetched) {
        DetailFetch.UnsupportedVersion -> WalletHistoryRecord(selection.signature, selection.slot, WalletHistoryOutcome.UNSUPPORTED_VERSION)
        is DetailFetch.Fetched ->
            if (fetched.outcome in WalletCaptureFormat.ARTIFACT_OUTCOMES) {
                val artifact = store.writeTransaction(request.directory, selection.signature, fetched.raw)
                WalletHistoryRecord(selection.signature, selection.slot, fetched.outcome, artifact.path, artifact.sha256, artifact.bytes)
            } else WalletHistoryRecord(selection.signature, selection.slot, fetched.outcome)
    }

    /** A new capture; a token-account capture first learns which token program owns the mint, before any listing. */
    private fun started(request: WalletHistoryCaptureRequest, genesis: String, budget: CaptureBudget): WalletHistoryManifest {
        val token = request.targetMint?.let { TokenTarget(it, tokenProgram(it, budget)) }
        return WalletHistoryManifest(
            schemaVersion = WalletCaptureFormat.version(request.scope), wallet = request.wallet.value, scope = request.scope,
            listingAddress = WalletHistoryListing.address(request.scope, request.wallet, token), genesisHash = genesis,
            commitment = ChainCaptureFormat.COMMITMENT, capturedAt = clock.instant().toString(), limit = request.limit,
            scanPageLimit = request.scanPages, selection = emptyList(), records = emptyList(), complete = false,
            discoveryComplete = false, discoveryStop = null, scannedSignatures = 0, skippedFailed = 0, scanCursor = null,
            requestsUsed = 0, bytesUsed = 0, targetMint = token?.mint?.value, tokenProgram = token?.tokenProgram,
        )
    }

    private fun tokenProgram(mint: MintAddress, budget: CaptureBudget): String {
        val owner = ChainCaptureReads.mintOwner(rpc, mint.value, budget)
        if (owner !in SolanaPrograms.TOKEN_PROGRAMS) throw CaptureValidationException("MINT_PROGRAM_UNSUPPORTED")
        log.info("wallet_capture token_program mint={} tokenProgram={}", mint.value, owner)
        return owner
    }

    private fun discover(request: WalletHistoryCaptureRequest, saved: WalletHistoryManifest, budget: CaptureBudget, stop: CaptureStop): WalletHistoryManifest {
        var manifest = saved
        while (scannedPages(manifest) < request.scanPages) {
            val page = try {
                ChainCaptureReads.signaturePage(rpc, manifest.listingAddress, before = manifest.scanCursor?.signature, until = null, budget)
            } catch (exception: CaptureProviderException) {
                log.warn("wallet_capture discovery interrupted category={}", exception.category)
                stop.record(exception.category, exception.retryAfter)
                return manifest
            } catch (exception: CaptureBudgetException) {
                log.warn("wallet_capture discovery interrupted category={}", exception.category)
                stop.record(exception.category)
                return manifest
            }
            val entries = SignatureListing.parse(page)
            if (entries.isEmpty()) return stopped(manifest, WalletHistoryDiscoveryStop.HISTORY_END)
            manifest = scan(manifest, entries)
            if (manifest.selection.size == manifest.limit) return stopped(manifest, WalletHistoryDiscoveryStop.LIMIT_REACHED)
            if (entries.size < ChainCaptureFormat.SIGNATURE_PAGE_SIZE) return stopped(manifest, WalletHistoryDiscoveryStop.HISTORY_END)
            checkpoint(request, usage(manifest, budget), budget)
        }
        return stopped(manifest, WalletHistoryDiscoveryStop.SCAN_BOUND)
    }

    // Discovery only checkpoints after full pages, so an incomplete scan always covers whole pages.
    private fun scannedPages(manifest: WalletHistoryManifest): Int = manifest.scannedSignatures / ChainCaptureFormat.SIGNATURE_PAGE_SIZE

    private fun scan(manifest: WalletHistoryManifest, entries: List<SignatureListingEntry>): WalletHistoryManifest {
        val cursor = manifest.scanCursor
        if (!SignatureListing.isNewestFirst(entries, cursor?.slot)) throw CaptureValidationException("SIGNATURE_ORDER")
        val seen = manifest.selection.associate { it.signature to SignatureListingEntry(it.signature, it.slot, failed = false) }.toMutableMap()
        val selection = manifest.selection.toMutableList()
        var scanned = manifest.scannedSignatures
        var skippedFailed = manifest.skippedFailed
        var lastScanned = cursor
        for (entry in entries) {
            if (selection.size == manifest.limit) break
            val previous = seen.putIfAbsent(entry.signature, entry)
            if (previous != null && previous != entry) throw CaptureValidationException("CONFLICTING_SIGNATURE")
            if (entry.signature == cursor?.signature) throw CaptureValidationException("NON_PROGRESSING_PAGE")
            scanned++
            lastScanned = WalletHistoryScanCursor(entry.signature, entry.slot)
            if (entry.failed) skippedFailed++ else if (previous == null) selection += WalletHistorySelection(entry.signature, entry.slot)
        }
        val added = selection.drop(manifest.selection.size).map { WalletHistoryRecord(it.signature, it.slot, WalletHistoryOutcome.PENDING) }
        return manifest.copy(selection = selection.toList(), records = manifest.records + added,
            scannedSignatures = scanned, skippedFailed = skippedFailed, scanCursor = lastScanned)
    }

    private fun stopped(manifest: WalletHistoryManifest, stop: WalletHistoryDiscoveryStop): WalletHistoryManifest =
        manifest.copy(discoveryComplete = true, discoveryStop = stop)

    private fun finish(request: WalletHistoryCaptureRequest, saved: WalletHistoryManifest, budget: CaptureBudget, stop: CaptureStop): WalletHistoryCaptureResult {
        val manifest = usage(saved, budget)
        val complete = manifest.complete
        checkpoint(request, manifest, budget)
        log.info("wallet_capture finished scope={} selected={} scanned={} skippedFailed={} discoveryStop={} complete={} requests={} bytes={} stopReason={}",
            manifest.scope, manifest.selection.size, manifest.scannedSignatures, manifest.skippedFailed, manifest.discoveryStop, complete, budget.requestsUsed, budget.bytesUsed,
            stop.reason)
        if (WalletHistoryCompletion.historyNotExhausted(manifest)) {
            log.warn("wallet_capture history_not_exhausted scope={} discoveryStop={} selected={} limit={} scanned={} scanPageLimit={}",
                manifest.scope, manifest.discoveryStop, manifest.selection.size, manifest.limit, manifest.scannedSignatures, manifest.scanPageLimit)
        }
        return WalletHistoryCaptureResult(request.directory.toAbsolutePath().normalize(), manifest, stop.reason, stop.retryAfter)
    }

    private fun usage(manifest: WalletHistoryManifest, budget: CaptureBudget): WalletHistoryManifest =
        manifest.copy(requestsUsed = budget.cumulativeRequests(), bytesUsed = budget.cumulativeBytes()).let { it.copy(complete = WalletCaptureFormat.isComplete(it)) }

    private fun checkpoint(request: WalletHistoryCaptureRequest, manifest: WalletHistoryManifest, budget: CaptureBudget) {
        store.save(request.directory, manifest)
        observer.checkpoint(com.trenching.bot.chaincapture.CaptureCheckpoint(
            phase = phase(manifest),
            at = clock.instant(), operationRequests = budget.requestsUsed, operationBytes = budget.bytesUsed,
            cumulativeRequests = budget.cumulativeRequests(), cumulativeBytes = budget.cumulativeBytes(),
            processed = manifest.records.count { it.outcome !in WalletCaptureFormat.UNRESOLVED_OUTCOMES }, selected = manifest.selection.size))
    }

    private fun phase(manifest: WalletHistoryManifest): String = when {
        manifest.complete -> "WALLET_COMPLETE"
        !manifest.discoveryComplete -> "WALLET_DISCOVERY"
        !WalletCaptureFormat.recordsResolved(manifest) -> "WALLET_DETAILS"
        else -> "WALLET_VERIFICATION"
    }

    private fun validate(request: WalletHistoryCaptureRequest) {
        if (request.limit !in 1..WalletCaptureFormat.MAX_SELECTION || request.scanPages !in 1..WalletCaptureFormat.MAX_SCAN_PAGES ||
            request.requestBudget !in 1..WalletCaptureFormat.MAX_REQUESTS || request.byteBudget !in 1..WalletCaptureFormat.MAX_CAPTURE_BYTES ||
            request.operationTimeout?.let { SolanaRpcSettings.isInvocationTimeoutValid(it) } == false ||
            (request.targetMint != null) != (request.scope == WalletHistoryScope.WALLET_TOKEN_ACCOUNT)) {
            throw CaptureValidationException("REQUEST_BOUNDS")
        }
    }
}
