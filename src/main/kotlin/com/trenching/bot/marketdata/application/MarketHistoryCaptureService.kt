package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.BoundedParallelFetch
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureMeasurements
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureStop
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.DetailBatches
import com.trenching.bot.chaincapture.DetailFetch
import com.trenching.bot.chaincapture.FetchedItem
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.domain.MarketCutoffRules
import com.trenching.bot.marketdata.domain.MarketWindowRules
import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.infrastructure.capture.MarketTransactionEvidence
import com.trenching.bot.marketdata.infrastructure.capture.MarketWindowRecords
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

internal class MarketHistoryCaptureService(
    private val rpc: SolanaReadRpc,
    private val store: MarketHistoryArtifactStore,
    private val clock: Clock,
    private val concurrency: Int = 1,
    private val observer: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE,
) : MarketHistoryCapture {
    private val log = LoggerFactory.getLogger(MarketHistoryCaptureService::class.java)
    private val evidence = MarketTransactionEvidence()
    private val extractor = PumpFunTradeObservationExtractor()
    private val detailBatches = DetailBatches(BoundedParallelFetch(), observer, concurrency, MarketCaptureFormat.DETAIL_CHECKPOINT_INTERVAL, "market_capture")

    override fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult {
        val windows = validate(request)
        return store.lock(request.directory).use { acquire(request, windows) }
    }

    private fun acquire(request: MarketHistoryCaptureRequest, windows: List<MarketCaptureWindow>): MarketHistoryCaptureResult {
        val saved = store.loadIfPresent(request.directory)
        if (saved != null && !sameIdentity(saved, request, windows)) throw CaptureValidationException("RESUME_IDENTITY")
        val budget = CaptureBudget(request.requestBudget, request.byteBudget, saved?.requestsUsed ?: 0, saved?.bytesUsed ?: 0, request.operationTimeout, request.usage)
        val genesis = ChainCaptureReads.requireMainnet(rpc, budget)
        var manifest = saved ?: MarketHistoryManifest(
            schemaVersion = MarketCaptureFormat.VERSION, genesisHash = genesis, commitment = ChainCaptureFormat.COMMITMENT,
            capturedAt = clock.instant().toString(), windowParameters = request.windowParameters, scanPageLimit = request.scanPages,
            maxTransactions = request.maxTransactions, windows = windows.map(MarketWindowRecords::pending), records = emptyList(),
            acquisitionComplete = false, complete = false, requestsUsed = 0, bytesUsed = 0,
        )
        if (request.scanPages > manifest.scanPageLimit) manifest = extend(manifest, request.scanPages)
        manifest = reopenUnaccepted(manifest, request)
        checkpoint(request, summarized(manifest, budget), budget)
        val stop = CaptureStop()
        manifest = discover(request, manifest, budget, stop)
        return finish(request, manifest, budget, stop)
    }

    /**
     * Lists and fetches each window in order. The confirmed tip is read once, before the first window that has not started
     * its listing; a window it has not passed is held, and the invocation then stops with the longest estimated wait, at the
     * recent slot pace read once when the first window is held.
     */
    private fun discover(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget, stop: CaptureStop): MarketHistoryManifest {
        var manifest = saved
        var tip: Long? = null
        var pace: SlotPace? = null
        var heldFor: Duration? = null
        for (index in manifest.windows.indices) {
            var window = manifest.windows[index]
            if (MarketWindowScan.needsTip(window)) {
                val confirmedTip = tip ?: discoveryRead(window, stop) { ChainCaptureReads.confirmedTip(rpc, budget) }?.also { tip = it }
                    ?: return manifest
                window = MarketWindowScan.atTip(window, confirmedTip)
                if (window.coverage == MarketWindowCoverage.HORIZON_NOT_ELAPSED) {
                    val slotPace = pace ?: discoveryRead(window, stop) { SlotPace(ChainCaptureReads.recentSlotDuration(rpc, budget)) }
                        ?.also { pace = it } ?: return manifest
                    val wait = MarketWindowScan.untilEnd(window, confirmedTip, slotPace.slotDuration)
                    heldFor = maxOf(heldFor ?: wait, wait)
                    log.info("market_capture window held mint={} fromSlot={} toSlot={} tipSlot={} slotMs={} estimatedWaitMs={}",
                        window.baseMint, window.fromSlot, window.toSlot, confirmedTip, slotPace.slotDuration?.toMillis(), wait.toMillis())
                }
                if (window != manifest.windows[index]) {
                    manifest = withWindow(manifest, index, window)
                    checkpoint(request, summarized(manifest, budget), budget)
                }
            }
            while (window.coverage == MarketWindowCoverage.PENDING) {
                val page = discoveryRead(window, stop) {
                    ChainCaptureReads.signaturePage(rpc, window.baseMint, window.scanCursor?.signature, window.untilSignature, budget)
                } ?: return manifest
                val admitted = manifest.records.mapTo(HashSet()) { it.signature }
                val scanned = MarketWindowScan.apply(window, SignatureListing.parse(page), admitted, request.maxTransactions - manifest.records.size)
                // Marked in the same save, so an interruption never leaves a pending window at its page bound.
                window = if (scanned.coverage == MarketWindowCoverage.PENDING && scanned.pagesScanned >= request.scanPages) {
                    MarketWindowScan.boundReached(scanned)
                } else scanned
                manifest = withWindow(manifest, index, window)
                checkpoint(request, summarized(manifest, budget), budget)
            }
            if (window.coverage == MarketWindowCoverage.HORIZON_NOT_ELAPSED) continue
            log.info("market_capture window mint={} fromSlot={} toSlot={} coverage={} scanned={} skippedFailed={} skippedNewer={} selected={}",
                window.baseMint, window.fromSlot, window.toSlot, window.coverage, window.scanned, window.skippedFailed, window.skippedNewer, window.selected)
            if (window.coverage == MarketWindowCoverage.COMPLETE) {
                manifest = fetchDetails(request, manifest, budget, stop, index)
                checkpoint(request, summarized(manifest, budget), budget)
                if (stop.reason != null) return manifest
            }
        }
        heldFor?.let { stop.record(MarketCaptureReasons.HORIZON_NOT_ELAPSED, it) }
        return manifest
    }

    /** The recent time per slot, or null when the endpoint had no sample to measure it from. */
    private data class SlotPace(val slotDuration: Duration?)

    /** Runs one discovery request; a provider or budget stop is recorded and yields null, so the caller keeps what it saved. */
    private fun <T : Any> discoveryRead(window: MarketWindowRecord, stop: CaptureStop, read: () -> T): T? = try {
        read()
    } catch (exception: CaptureProviderException) {
        log.warn("market_capture discovery interrupted category={} mint={}", exception.category, window.baseMint)
        stop.record(exception.category, exception.retryAfter)
        null
    } catch (exception: CaptureBudgetException) {
        log.warn("market_capture discovery interrupted category={} mint={}", exception.category, window.baseMint)
        stop.record(exception.category)
        null
    }

    private fun extend(manifest: MarketHistoryManifest, scanPages: Int): MarketHistoryManifest {
        log.info("market_capture extending scanPages={} previousScanPages={}", scanPages, manifest.scanPageLimit)
        return manifest.copy(scanPageLimit = scanPages, windows = manifest.windows.map(MarketWindowScan::extended))
    }

    /**
     * Reopens each decided window this request does not accept, a full capture or a cutoff of another identity: its
     * `NOT_NEEDED` records become pending again and are fetched from the saved listing.
     */
    private fun reopenUnaccepted(manifest: MarketHistoryManifest, request: MarketHistoryCaptureRequest): MarketHistoryManifest =
        manifest.windows.indices.fold(manifest) { current, index ->
            val cutoff = current.windows[index].cutoff
            if (cutoff == null || accepts(request, cutoff)) current else {
                log.info("market_capture cutoff reopened mint={} throughSlot={} outcome={} configurationHash={} mode={}",
                    current.windows[index].baseMint, cutoff.throughSlot, cutoff.outcome, cutoff.configurationHash, request.cutoffMode)
                MarketCutoffRules.reopened(current, index)
            }
        }

    private fun accepts(request: MarketHistoryCaptureRequest, cutoff: MarketWindowCutoff): Boolean = when (request.cutoffMode) {
        MarketCutoffMode.FULL -> false
        MarketCutoffMode.DECIDED -> request.cutoffIdentity == CutoffIdentity(cutoff.engineVersion, cutoff.configurationHash)
    }

    /** Admits a completed window's selection, keeping records in window order and each existing record's state unless it is needed again. */
    private fun withWindow(manifest: MarketHistoryManifest, index: Int, window: MarketWindowRecord): MarketHistoryManifest {
        val windows = manifest.windows.toMutableList().apply { set(index, window) }
        if (window.coverage != MarketWindowCoverage.COMPLETE) return manifest.copy(windows = windows)
        val existing = manifest.records.associateBy { it.signature }
        val records = MarketWindowRecords.admitted(windows)
            .map { existing[it.signature] ?: MarketTransactionRecord(it.signature, it.slot, MarketTransactionOutcome.PENDING) }
        return MarketCutoffRules.reconciled(manifest.copy(windows = windows, records = records))
    }

    /**
     * Fetches a complete, undecided window's unresolved records oldest first. In decided mode the cutoff is asked before
     * the first fetch and with each batch whose prefix advanced; a decision is saved with that batch and ends the phase.
     */
    private fun fetchDetails(
        request: MarketHistoryCaptureRequest,
        saved: MarketHistoryManifest,
        budget: CaptureBudget,
        stop: CaptureStop,
        index: Int,
    ): MarketHistoryManifest {
        val window = saved.windows[index]
        val unresolved = unresolved(saved, window)
        if (window.cutoff != null || unresolved.isEmpty()) return saved
        val evaluator = evaluator(request, index)
        val initial = evaluator?.evaluate(saved) ?: saved
        if (evaluator?.decided(initial) == true) return initial
        return detailBatches.run(
            items = unresolved, initial = initial, budget = budget, stop = stop,
            signatureOf = { it.value.signature },
            fetchOne = { fetchDetail(it.value, budget) },
            apply = { manifest, batch ->
                val applied = withDetails(request, manifest, batch.completed, batch.unavailable)
                evaluator?.evaluate(applied) ?: applied
            },
            checkpoint = { checkpoint(request, summarized(it, budget), budget) },
            report = { logPhase(window, it) },
            until = { evaluator?.decided(it) == true },
        )
    }

    /** The window's records still to fetch, in ascending (slot, signature) order; their indexes keep the manifest's order. */
    private fun unresolved(manifest: MarketHistoryManifest, window: MarketWindowRecord): List<IndexedValue<MarketTransactionRecord>> {
        val selected = window.selection.mapTo(HashSet()) { it.signature }
        return manifest.records.withIndex()
            .filter { (_, record) -> record.signature in selected && record.outcome in MarketCaptureFormat.UNRESOLVED_OUTCOMES }
            .sortedWith(compareBy({ it.value.slot }, { it.value.signature }))
    }

    private fun evaluator(request: MarketHistoryCaptureRequest, index: Int): WindowCutoffEvaluator? = when (request.cutoffMode) {
        MarketCutoffMode.FULL -> null
        MarketCutoffMode.DECIDED -> WindowCutoffEvaluator(request.directory, index,
            requireNotNull(request.cutoff) { "A decided capture was validated with a cutoff" },
            requireNotNull(request.cutoffIdentity) { "A decided capture was validated with a cutoff identity" }, store, extractor)
    }

    private fun fetchDetail(record: MarketTransactionRecord, budget: CaptureBudget): DetailFetch<MarketTransactionOutcome> =
        DetailFetch.request(rpc, record.signature, budget) { evidence.classify(it, record.signature, record.slot) }

    /** Applies the completed details to their records in listing order; the record that failed on the provider stays retryable. */
    private fun withDetails(
        request: MarketHistoryCaptureRequest,
        saved: MarketHistoryManifest,
        completed: List<FetchedItem<IndexedValue<MarketTransactionRecord>, DetailFetch<MarketTransactionOutcome>>>,
        unavailable: IndexedValue<MarketTransactionRecord>?,
    ): MarketHistoryManifest {
        val records = saved.records.toMutableList()
        for (done in completed) {
            val (index, record) = done.item
            records[index] = detail(request, record, done.result)
        }
        unavailable?.let { (index, record) -> records[index] = MarketTransactionRecord(record.signature, record.slot, MarketTransactionOutcome.PROVIDER_UNAVAILABLE) }
        return saved.copy(records = records.toList())
    }

    private fun detail(request: MarketHistoryCaptureRequest, record: MarketTransactionRecord, fetched: DetailFetch<MarketTransactionOutcome>): MarketTransactionRecord = when (fetched) {
        DetailFetch.UnsupportedVersion -> MarketTransactionRecord(record.signature, record.slot, MarketTransactionOutcome.UNSUPPORTED_VERSION)
        is DetailFetch.Fetched ->
            if (fetched.outcome == MarketTransactionOutcome.CAPTURED) {
                val artifact = store.writeTransaction(request.directory, record.signature, fetched.raw)
                MarketTransactionRecord(record.signature, record.slot, fetched.outcome, artifact.path, artifact.sha256, artifact.bytes)
            } else MarketTransactionRecord(record.signature, record.slot, fetched.outcome)
    }

    private fun logPhase(window: MarketWindowRecord, measurements: CaptureMeasurements) {
        log.info("market_capture details mint={} fromSlot={} toSlot={} requestsSent={} cacheHits={} rateLimitedResponses={} retries={} elapsedMs={} concurrency={}",
            window.baseMint, window.fromSlot, window.toSlot, measurements.requestsSent, measurements.cacheHits, measurements.rateLimitedResponses,
            measurements.retries, measurements.elapsed.toMillis(), concurrency)
    }
    private fun finish(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget, stop: CaptureStop): MarketHistoryCaptureResult {
        val manifest = summarized(saved, budget)
        checkpoint(request, manifest, budget)
        log.info("market_capture finished windows={} transactions={} acquisitionComplete={} complete={} requests={} bytes={} stopReason={}",
            manifest.windows.size, manifest.records.size, manifest.acquisitionComplete, manifest.complete, budget.requestsUsed, budget.bytesUsed,
            stop.reason)
        return MarketHistoryCaptureResult(request.directory.toAbsolutePath().normalize(), manifest, stop.reason, stop.retryAfter)
    }

    /** Sets the cumulative usage, the completion flags and the schema version the manifest's content needs. */
    private fun summarized(manifest: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryManifest {
        val acquired = MarketCaptureFormat.isAcquired(manifest.windows, manifest.records)
        return manifest.copy(schemaVersion = MarketCaptureFormat.versionFor(manifest.windows),
            requestsUsed = budget.cumulativeRequests(), bytesUsed = budget.cumulativeBytes(), acquisitionComplete = acquired,
            complete = acquired && manifest.windows.all { it.coverage == MarketWindowCoverage.COMPLETE })
    }

    private fun checkpoint(request: MarketHistoryCaptureRequest, manifest: MarketHistoryManifest, budget: CaptureBudget) {
        store.save(request.directory, manifest)
        observer.checkpoint(com.trenching.bot.chaincapture.CaptureCheckpoint(
            phase = if (manifest.acquisitionComplete) "MARKET_COMPLETE" else if (manifest.windows.any { it.coverage in MarketCaptureFormat.OPEN_COVERAGES }) "MARKET_DISCOVERY" else "MARKET_DETAILS",
            at = clock.instant(), operationRequests = budget.requestsUsed, operationBytes = budget.bytesUsed,
            cumulativeRequests = budget.cumulativeRequests(), cumulativeBytes = budget.cumulativeBytes(),
            processed = manifest.records.count { it.outcome !in MarketCaptureFormat.UNRESOLVED_OUTCOMES }, selected = manifest.records.size))
    }

    private fun sameIdentity(saved: MarketHistoryManifest, request: MarketHistoryCaptureRequest, windows: List<MarketCaptureWindow>): Boolean =
        saved.windows.map(MarketWindowRecords::window) == windows && saved.windowParameters == request.windowParameters &&
            saved.scanPageLimit <= request.scanPages && saved.maxTransactions == request.maxTransactions

    /** Returns the request's windows in canonical order after checking every bound before any provider request. */
    private fun validate(request: MarketHistoryCaptureRequest): List<MarketCaptureWindow> {
        val windows = MarketWindowRules.canonical(request.windows)
        if (request.scanPages !in 1..MarketCaptureFormat.MAX_SCAN_PAGES || request.maxTransactions !in 1..MarketCaptureFormat.MAX_TRANSACTIONS ||
            request.requestBudget !in 1..MarketCaptureFormat.MAX_REQUESTS || request.byteBudget !in 1..MarketCaptureFormat.MAX_CAPTURE_BYTES ||
            windows.size !in 1..MarketCaptureFormat.MAX_WINDOWS || !MarketWindowRules.isDisjoint(windows) ||
            !MarketWindowRules.isValid(request.windowParameters) ||
            request.operationTimeout?.let { SolanaRpcSettings.isInvocationTimeoutValid(it) } == false || !isCutoffValid(request)) {
            throw CaptureValidationException("REQUEST_BOUNDS")
        }
        return windows
    }

    private fun isCutoffValid(request: MarketHistoryCaptureRequest): Boolean = when (request.cutoffMode) {
        MarketCutoffMode.FULL -> true
        MarketCutoffMode.DECIDED -> request.cutoff != null && request.cutoffIdentity?.let { identity ->
            MarketCaptureFormat.isCutoffText(identity.engineVersion) && MarketCaptureFormat.isCutoffText(identity.configurationHash)
        } == true
    }
}
