package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SolanaReadRpc
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
        val budget = CaptureBudget(request.requestBudget, request.byteBudget, saved?.requestsUsed ?: 0, saved?.bytesUsed ?: 0)
        val genesis = ChainCaptureReads.requireMainnet(rpc, budget)
        var manifest = saved ?: MarketHistoryManifest(
            schemaVersion = MarketCaptureFormat.VERSION, genesisHash = genesis, commitment = ChainCaptureFormat.COMMITMENT,
            capturedAt = clock.instant().toString(), windowParameters = request.windowParameters, scanPageLimit = request.scanPages,
            maxTransactions = request.maxTransactions, windows = windows.map(MarketWindowRecords::pending), records = emptyList(),
            acquisitionComplete = false, complete = false, requestsUsed = 0, bytesUsed = 0,
        )
        store.save(request.directory, usage(manifest, budget))
        manifest = discover(request, manifest, budget)
        if (manifest.windows.none { it.coverage == MarketWindowCoverage.PENDING }) manifest = fetchDetails(request, manifest, budget)
        return finish(request, manifest, budget)
    }

    private fun discover(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryManifest {
        var manifest = saved
        for (index in manifest.windows.indices) {
            var window = manifest.windows[index]
            while (window.coverage == MarketWindowCoverage.PENDING) {
                window = if (window.pagesScanned >= request.scanPages) MarketWindowScan.boundReached(window) else {
                    val page = try {
                        ChainCaptureReads.signaturePage(rpc, window.baseMint, window.scanCursor?.signature, window.untilSignature, budget)
                    } catch (exception: CaptureProviderException) {
                        log.warn("market_capture discovery interrupted category={} mint={}", exception.category, window.baseMint)
                        return manifest
                    } catch (_: CaptureBudgetException) {
                        log.warn("market_capture discovery interrupted category=BUDGET mint={}", window.baseMint)
                        return manifest
                    }
                    val admitted = manifest.records.mapTo(HashSet()) { it.signature }
                    MarketWindowScan.apply(window, SignatureListing.parse(page), admitted, request.maxTransactions - manifest.records.size)
                }
                manifest = withWindow(manifest, index, window)
                store.save(request.directory, usage(manifest, budget))
            }
            log.info("market_capture window mint={} fromSlot={} toSlot={} coverage={} scanned={} skippedFailed={} skippedNewer={} selected={}",
                window.baseMint, window.fromSlot, window.toSlot, window.coverage, window.scanned, window.skippedFailed, window.skippedNewer, window.selected)
        }
        return manifest
    }

    private fun withWindow(manifest: MarketHistoryManifest, index: Int, window: MarketWindowRecord): MarketHistoryManifest {
        val windows = manifest.windows.toMutableList().apply { set(index, window) }
        if (window.coverage != MarketWindowCoverage.COMPLETE) return manifest.copy(windows = windows)
        val admitted = manifest.records.mapTo(HashSet()) { it.signature }
        val added = window.selection.filter { it.signature !in admitted }
            .map { MarketTransactionRecord(it.signature, it.slot, MarketTransactionOutcome.PENDING) }
        return manifest.copy(windows = windows, records = manifest.records + added)
    }

    private fun fetchDetails(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryManifest {
        var manifest = saved
        val records = manifest.records.toMutableList()
        for ((index, record) in manifest.records.withIndex()) {
            if (record.outcome !in MarketCaptureFormat.UNRESOLVED_OUTCOMES) continue
            val raw = try {
                ChainCaptureReads.transaction(rpc, record.signature, budget)
            } catch (exception: CaptureProviderException) {
                val outcome = if (exception.category == UNSUPPORTED_VERSION) MarketTransactionOutcome.UNSUPPORTED_VERSION
                else MarketTransactionOutcome.PROVIDER_UNAVAILABLE
                records[index] = MarketTransactionRecord(record.signature, record.slot, outcome)
                manifest = manifest.copy(records = records.toList())
                log.warn("market_capture interrupted category={} signature={}", exception.category, record.signature)
                store.save(request.directory, usage(manifest, budget))
                if (outcome == MarketTransactionOutcome.PROVIDER_UNAVAILABLE) break else continue
            } catch (_: CaptureBudgetException) {
                log.warn("market_capture interrupted category=BUDGET signature={}", record.signature)
                break
            }
            val outcome = evidence.classify(raw, record.signature, record.slot)
            records[index] = if (outcome == MarketTransactionOutcome.CAPTURED) {
                val artifact = store.writeTransaction(request.directory, record.signature, raw)
                MarketTransactionRecord(record.signature, record.slot, outcome, artifact.path, artifact.sha256, artifact.bytes)
            } else MarketTransactionRecord(record.signature, record.slot, outcome)
            manifest = manifest.copy(records = records.toList())
            store.save(request.directory, usage(manifest, budget))
        }
        return manifest
    }

    private fun finish(request: MarketHistoryCaptureRequest, saved: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryCaptureResult {
        val manifest = usage(saved, budget)
        store.save(request.directory, manifest)
        log.info("market_capture finished windows={} transactions={} acquisitionComplete={} complete={} requests={} bytes={}",
            manifest.windows.size, manifest.records.size, manifest.acquisitionComplete, manifest.complete, budget.requestsUsed, budget.bytesUsed)
        return MarketHistoryCaptureResult(request.directory.toAbsolutePath().normalize(), manifest)
    }

    private fun usage(manifest: MarketHistoryManifest, budget: CaptureBudget): MarketHistoryManifest {
        val acquired = manifest.windows.none { it.coverage == MarketWindowCoverage.PENDING } &&
            manifest.records.none { it.outcome in MarketCaptureFormat.UNRESOLVED_OUTCOMES }
        return manifest.copy(requestsUsed = budget.cumulativeRequests(), bytesUsed = budget.cumulativeBytes(), acquisitionComplete = acquired,
            complete = acquired && manifest.windows.all { it.coverage == MarketWindowCoverage.COMPLETE })
    }

    private fun sameIdentity(saved: MarketHistoryManifest, request: MarketHistoryCaptureRequest, windows: List<MarketCaptureWindow>): Boolean =
        saved.windows.map(MarketWindowRecords::window) == windows && saved.windowParameters == request.windowParameters &&
            saved.scanPageLimit == request.scanPages && saved.maxTransactions == request.maxTransactions

    /** Returns the request's windows in canonical order after checking every bound before any provider request. */
    private fun validate(request: MarketHistoryCaptureRequest): List<MarketCaptureWindow> {
        val windows = MarketWindowRules.canonical(request.windows)
        if (request.scanPages !in 1..MarketCaptureFormat.MAX_SCAN_PAGES || request.maxTransactions !in 1..MarketCaptureFormat.MAX_TRANSACTIONS ||
            request.requestBudget !in 1..MarketCaptureFormat.MAX_REQUESTS || request.byteBudget !in 1..MarketCaptureFormat.MAX_CAPTURE_BYTES ||
            windows.size !in 1..MarketCaptureFormat.MAX_WINDOWS || !MarketWindowRules.isDisjoint(windows) ||
            !MarketWindowRules.isValid(request.windowParameters)) {
            throw CaptureValidationException("REQUEST_BOUNDS")
        }
        return windows
    }

    private companion object {
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    }
}
