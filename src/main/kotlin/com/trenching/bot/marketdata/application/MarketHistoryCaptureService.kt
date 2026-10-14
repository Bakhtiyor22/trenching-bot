package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureStop
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.domain.MarketWindowRules
import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.infrastructure.capture.MarketTransactionEvidence
import com.trenching.bot.marketdata.infrastructure.capture.MarketWindowRecords
import java.time.Clock
import org.slf4j.LoggerFactory

internal class MarketHistoryCaptureService(
    private val rpc: SolanaReadRpc,
    private val store: MarketHistoryArtifactStore,
    private val clock: Clock,
    private val observer: com.trenching.bot.chaincapture.CaptureCheckpointObserver = com.trenching.bot.chaincapture.CaptureCheckpointObserver.NONE,
) : MarketHistoryCapture {
    private val log = LoggerFactory.getLogger(MarketHistoryCaptureService::class.java)
    private val evidence = MarketTransactionEvidence()

    override fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult {
        val windows = validate(request)
        return store.lock(request.directory).use { acquire(request, windows) }
    }

    private fun acquire(request: MarketHistoryCaptureRequest, windows: List<MarketCaptureWindow>): MarketHistoryCaptureResult {
        val saved = store.loadIfPresent(request.directory)
        if (saved != null && !sameIdentity(saved, request, windows)) throw CaptureValidationException("RESUME_IDENTITY")
        val budget = CaptureBudget(request.requestBudget, request.byteBudget, saved?.requestsUsed ?: 0, saved?.bytesUsed ?: 0, request.operationTimeout)
        val genesis = ChainCaptureReads.requireMainnet(rpc, budget)
        var manifest = saved ?: MarketHistoryManifest(
            schemaVersion = MarketCaptureFormat.VERSION, genesisHash = genesis, commitment = ChainCaptureFormat.COMMITMENT,
            capturedAt = clock.instant().toString(), windowParameters = request.windowParameters, scanPageLimit = request.scanPages,
            maxTransactions = request.maxTransactions, windows = windows.map(MarketWindowRecords::pending), records = emptyList(),
            acquisitionComplete = false, complete = false, requestsUsed = 0, bytesUsed = 0,
        )
        if (request.scanPages > manifest.scanPageLimit) manifest = extend(manifest, request.scanPages)
        checkpoint(request, usage(manifest, budget), budget)
        val stop = CaptureStop()
        manifest = discover(request, manifest, budget, stop)
        return finish(request, manifest, budget, stop)
    }

    private fun discover(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget, stop: CaptureStop): MarketHistoryManifest {
        var manifest = saved
        for (index in manifest.windows.indices) {
            var window = manifest.windows[index]
            while (window.coverage == MarketWindowCoverage.PENDING) {
                val page = try {
                    ChainCaptureReads.signaturePage(rpc, window.baseMint, window.scanCursor?.signature, window.untilSignature, budget)
                } catch (exception: CaptureProviderException) {
                    log.warn("market_capture discovery interrupted category={} mint={}", exception.category, window.baseMint)
                    stop.record(exception.category, exception.retryAfter)
                    return manifest
                } catch (exception: CaptureBudgetException) {
                    log.warn("market_capture discovery interrupted category={} mint={}", exception.category, window.baseMint)
                    stop.record(exception.category)
                    return manifest
                }
                val admitted = manifest.records.mapTo(HashSet()) { it.signature }
                val scanned = MarketWindowScan.apply(window, SignatureListing.parse(page), admitted, request.maxTransactions - manifest.records.size)
                // Marked in the same save, so an interruption never leaves a pending window at its page bound.
                window = if (scanned.coverage == MarketWindowCoverage.PENDING && scanned.pagesScanned >= request.scanPages) {
                    MarketWindowScan.boundReached(scanned)
                } else scanned
                manifest = withWindow(manifest, index, window)
                checkpoint(request, usage(manifest, budget), budget)
            }
            log.info("market_capture window mint={} fromSlot={} toSlot={} coverage={} scanned={} skippedFailed={} skippedNewer={} selected={}",
                window.baseMint, window.fromSlot, window.toSlot, window.coverage, window.scanned, window.skippedFailed, window.skippedNewer, window.selected)
            if (window.coverage == MarketWindowCoverage.COMPLETE) {
                manifest = fetchDetails(request, manifest, budget, stop, window.selection.mapTo(HashSet()) { it.signature })
                checkpoint(request, usage(manifest, budget), budget)
                if (stop.reason != null) return manifest
            }
        }
        return manifest
    }

    private fun extend(manifest: MarketHistoryManifest, scanPages: Int): MarketHistoryManifest {
        log.info("market_capture extending scanPages={} previousScanPages={}", scanPages, manifest.scanPageLimit)
        return manifest.copy(scanPageLimit = scanPages, windows = manifest.windows.map(MarketWindowScan::extended))
    }

    /** Admits a completed window's selection, keeping records in window order and each existing record's state. */
    private fun withWindow(manifest: MarketHistoryManifest, index: Int, window: MarketWindowRecord): MarketHistoryManifest {
        val windows = manifest.windows.toMutableList().apply { set(index, window) }
        if (window.coverage != MarketWindowCoverage.COMPLETE) return manifest.copy(windows = windows)
        val existing = manifest.records.associateBy { it.signature }
        val records = MarketWindowRecords.admitted(windows)
            .map { existing[it.signature] ?: MarketTransactionRecord(it.signature, it.slot, MarketTransactionOutcome.PENDING) }
        return manifest.copy(windows = windows, records = records)
    }

    private fun fetchDetails(
        request: MarketHistoryCaptureRequest,
        saved: MarketHistoryManifest,
        budget: CaptureBudget,
        stop: CaptureStop,
        selectedSignatures: Set<String>,
    ): MarketHistoryManifest {
        var manifest = saved
        val records = manifest.records.toMutableList()
        var fetched = 0
        for ((index, record) in manifest.records.withIndex()) {
            if (record.signature !in selectedSignatures) continue
            if (record.outcome !in MarketCaptureFormat.UNRESOLVED_OUTCOMES) continue
            val raw = try {
                ChainCaptureReads.transaction(rpc, record.signature, budget)
            } catch (exception: CaptureProviderException) {
                val outcome = if (exception.category == UNSUPPORTED_VERSION) MarketTransactionOutcome.UNSUPPORTED_VERSION
                else MarketTransactionOutcome.PROVIDER_UNAVAILABLE
                records[index] = MarketTransactionRecord(record.signature, record.slot, outcome)
                manifest = manifest.copy(records = records.toList())
                log.warn("market_capture interrupted category={} signature={}", exception.category, record.signature)
                checkpoint(request, usage(manifest, budget), budget)
                if (outcome == MarketTransactionOutcome.PROVIDER_UNAVAILABLE) { stop.record(exception.category, exception.retryAfter); break } else continue
            } catch (exception: CaptureBudgetException) {
                log.warn("market_capture interrupted category={} signature={}", exception.category, record.signature)
                stop.record(exception.category)
                break
            }
            val outcome = evidence.classify(raw, record.signature, record.slot)
            records[index] = if (outcome == MarketTransactionOutcome.CAPTURED) {
                val artifact = store.writeTransaction(request.directory, record.signature, raw)
                MarketTransactionRecord(record.signature, record.slot, outcome, artifact.path, artifact.sha256, artifact.bytes)
            } else MarketTransactionRecord(record.signature, record.slot, outcome)
            fetched++
            if (fetched % MarketCaptureFormat.DETAIL_CHECKPOINT_INTERVAL == 0) {
                manifest = manifest.copy(records = records.toList())
                checkpoint(request, usage(manifest, budget), budget)
            }
        }
        return manifest.copy(records = records.toList())
    }

    private fun finish(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget, stop: CaptureStop): MarketHistoryCaptureResult {
        val manifest = usage(saved, budget)
        checkpoint(request, manifest, budget)
        log.info("market_capture finished windows={} transactions={} acquisitionComplete={} complete={} requests={} bytes={} stopReason={}",
            manifest.windows.size, manifest.records.size, manifest.acquisitionComplete, manifest.complete, budget.requestsUsed, budget.bytesUsed,
            stop.reason)
        return MarketHistoryCaptureResult(request.directory.toAbsolutePath().normalize(), manifest, stop.reason, stop.retryAfter)
    }

    private fun usage(manifest: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryManifest {
        val acquired = manifest.windows.none { it.coverage == MarketWindowCoverage.PENDING } &&
            manifest.records.none { it.outcome in MarketCaptureFormat.UNRESOLVED_OUTCOMES }
        return manifest.copy(requestsUsed = budget.cumulativeRequests(), bytesUsed = budget.cumulativeBytes(), acquisitionComplete = acquired,
            complete = acquired && manifest.windows.all { it.coverage == MarketWindowCoverage.COMPLETE })
    }

    private fun checkpoint(request: MarketHistoryCaptureRequest, manifest: MarketHistoryManifest, budget: CaptureBudget) {
        store.save(request.directory, manifest)
        observer.checkpoint(com.trenching.bot.chaincapture.CaptureCheckpoint(
            phase = if (manifest.acquisitionComplete) "MARKET_COMPLETE" else if (manifest.windows.any { it.coverage == MarketWindowCoverage.PENDING }) "MARKET_DISCOVERY" else "MARKET_DETAILS",
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
            request.operationTimeout?.let { SolanaRpcSettings.isInvocationTimeoutValid(it) } == false) {
            throw CaptureValidationException("REQUEST_BOUNDS")
        }
        return windows
    }

    private companion object {
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    }
}
