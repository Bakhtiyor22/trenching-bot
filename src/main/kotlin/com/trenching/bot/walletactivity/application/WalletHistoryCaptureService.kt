package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureStop
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.walletactivity.*
import com.trenching.bot.walletactivity.infrastructure.capture.WalletCaptureFormat
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import java.time.Clock
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

internal class WalletHistoryCaptureService(
    private val rpc: SolanaReadRpc,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val observer: com.trenching.bot.chaincapture.CaptureCheckpointObserver = com.trenching.bot.chaincapture.CaptureCheckpointObserver.NONE,
) : WalletHistoryCapture {
    private val log = LoggerFactory.getLogger(WalletHistoryCaptureService::class.java)
    private val store = WalletHistoryArtifactStore(objectMapper)
    private val evidence = WalletTransactionEvidence()

    override fun capture(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult {
        validate(request)
        val listingAddress = WalletHistoryListing.address(request.scope, request.wallet)
        return store.lock(request.directory).use { acquire(request, listingAddress) }
    }

    private fun acquire(request: WalletHistoryCaptureRequest, listingAddress: String): WalletHistoryCaptureResult {
        val saved = store.loadIfPresent(request.directory)?.copy(schemaVersion = WalletCaptureFormat.VERSION)
        if (saved != null && (saved.wallet != request.wallet.value || saved.scope != request.scope ||
                saved.limit != request.limit || saved.scanPageLimit != request.scanPages)) {
            throw CaptureValidationException("RESUME_IDENTITY")
        }
        val budget = CaptureBudget(request.requestBudget, request.byteBudget, saved?.requestsUsed ?: 0, saved?.bytesUsed ?: 0, request.operationTimeout)
        val genesis = ChainCaptureReads.requireMainnet(rpc, budget)
        var manifest = saved ?: WalletHistoryManifest(
            schemaVersion = WalletCaptureFormat.VERSION, wallet = request.wallet.value, scope = request.scope,
            listingAddress = listingAddress, genesisHash = genesis,
            commitment = ChainCaptureFormat.COMMITMENT, capturedAt = clock.instant().toString(), limit = request.limit,
            scanPageLimit = request.scanPages, selection = emptyList(), records = emptyList(), complete = false,
            discoveryComplete = false, discoveryStop = null, scannedSignatures = 0, skippedFailed = 0, scanCursor = null,
            requestsUsed = 0, bytesUsed = 0,
        )
        checkpoint(request, manifest, budget)
        val stop = CaptureStop()
        if (!manifest.discoveryComplete) {
            manifest = discover(request, manifest, budget, stop)
            checkpoint(request, usage(manifest, budget), budget)
        }
        if (!manifest.discoveryComplete) return finish(request, manifest, budget, stop)
        val records = manifest.records.toMutableList()
        for ((index, selection) in manifest.selection.withIndex()) {
            val old = records[index]
            if (old.outcome !in WalletCaptureFormat.UNRESOLVED_OUTCOMES) continue
            val raw = try {
                ChainCaptureReads.transaction(rpc, selection.signature, budget)
            } catch (exception: CaptureProviderException) {
                val outcome = if (exception.category == "UNSUPPORTED_VERSION") WalletHistoryOutcome.UNSUPPORTED_VERSION else WalletHistoryOutcome.PROVIDER_UNAVAILABLE
                records[index] = WalletHistoryRecord(selection.signature, selection.slot, outcome)
                manifest = manifest.copy(records = records.toList())
                log.warn("wallet_capture interrupted category={} signature={}", exception.category, selection.signature)
                checkpoint(request, usage(manifest, budget), budget)
                if (outcome == WalletHistoryOutcome.PROVIDER_UNAVAILABLE) { stop.record(exception.category); break } else continue
            } catch (exception: CaptureBudgetException) {
                log.warn("wallet_capture interrupted category={} signature={}", exception.category, selection.signature)
                stop.record(exception.category)
                break
            }
            val outcome = evidence.classify(raw, selection, manifest.wallet, manifest.listingAddress)
            records[index] = if (outcome in WalletCaptureFormat.ARTIFACT_OUTCOMES) {
                val artifact = store.writeTransaction(request.directory, selection.signature, raw)
                WalletHistoryRecord(selection.signature, selection.slot, outcome, artifact.path, artifact.sha256, artifact.bytes)
            } else WalletHistoryRecord(selection.signature, selection.slot, outcome)
            manifest = manifest.copy(records = records.toList())
            checkpoint(request, usage(manifest, budget), budget)
        }
        return finish(request, manifest.copy(records = records.toList()), budget, stop)
    }

    private fun discover(request: WalletHistoryCaptureRequest, saved: WalletHistoryManifest, budget: CaptureBudget, stop: CaptureStop): WalletHistoryManifest {
        var manifest = saved
        while (scannedPages(manifest) < request.scanPages) {
            val page = try {
                ChainCaptureReads.signaturePage(rpc, manifest.listingAddress, before = manifest.scanCursor?.signature, until = null, budget)
            } catch (exception: CaptureProviderException) {
                log.warn("wallet_capture discovery interrupted category={}", exception.category)
                stop.record(exception.category)
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
        val complete = saved.discoveryComplete && saved.records.none { it.outcome in WalletCaptureFormat.UNRESOLVED_OUTCOMES }
        val manifest = usage(saved.copy(complete = complete), budget)
        checkpoint(request, manifest, budget)
        log.info("wallet_capture finished scope={} selected={} scanned={} skippedFailed={} discoveryStop={} complete={} requests={} bytes={} stopReason={}",
            manifest.scope, manifest.selection.size, manifest.scannedSignatures, manifest.skippedFailed, manifest.discoveryStop, complete, budget.requestsUsed, budget.bytesUsed,
            stop.reason)
        return WalletHistoryCaptureResult(request.directory.toAbsolutePath().normalize(), manifest, stop.reason)
    }

    private fun usage(manifest: WalletHistoryManifest, budget: CaptureBudget): WalletHistoryManifest =
        manifest.copy(requestsUsed = budget.cumulativeRequests(), bytesUsed = budget.cumulativeBytes(),
            complete = manifest.discoveryComplete && manifest.records.none { it.outcome in WalletCaptureFormat.UNRESOLVED_OUTCOMES })

    private fun checkpoint(request: WalletHistoryCaptureRequest, manifest: WalletHistoryManifest, budget: CaptureBudget) {
        store.save(request.directory, manifest)
        observer.checkpoint(com.trenching.bot.chaincapture.CaptureCheckpoint(
            phase = if (manifest.complete) "WALLET_COMPLETE" else if (manifest.discoveryComplete) "WALLET_DETAILS" else "WALLET_DISCOVERY",
            at = clock.instant(), operationRequests = budget.requestsUsed, operationBytes = budget.bytesUsed,
            cumulativeRequests = budget.cumulativeRequests(), cumulativeBytes = budget.cumulativeBytes(),
            processed = manifest.records.count { it.outcome !in WalletCaptureFormat.UNRESOLVED_OUTCOMES }, selected = manifest.selection.size))
    }

    private fun validate(request: WalletHistoryCaptureRequest) {
        if (request.limit !in 1..WalletCaptureFormat.MAX_SELECTION || request.scanPages !in 1..WalletCaptureFormat.MAX_SCAN_PAGES ||
            request.requestBudget !in 1..WalletCaptureFormat.MAX_REQUESTS || request.byteBudget !in 1..WalletCaptureFormat.MAX_CAPTURE_BYTES ||
            request.operationTimeout?.let { SolanaRpcSettings.isInvocationTimeoutValid(it) } == false) {
            throw CaptureValidationException("REQUEST_BOUNDS")
        }
    }
}
