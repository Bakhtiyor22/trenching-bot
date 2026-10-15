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
import com.trenching.bot.chaincapture.ParallelFetchResult
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.AccumulatorScan
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.VerificationOutcome
import com.trenching.bot.walletactivity.VerificationRecord
import com.trenching.bot.walletactivity.VerifiedFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.WalletTokenVerification
import com.trenching.bot.walletactivity.domain.CreationOutcome
import com.trenching.bot.walletactivity.infrastructure.capture.TokenTarget
import com.trenching.bot.walletactivity.infrastructure.capture.VerificationSection
import com.trenching.bot.walletactivity.infrastructure.capture.VerificationStart
import com.trenching.bot.walletactivity.infrastructure.capture.WalletCaptureFormat
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import java.nio.file.Path
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * Step 2 of a token-account capture, once step 1 resolved: takes B as the creation when it creates the mint, or lists the mint
 * before B and fetches its oldest successful entries until one creates it; then scans both of the wallet's accumulators from
 * the head of their listings down to below the creation, and only then fetches the in-range entries once each, oldest first,
 * until the verdict is reached. Every page and fetch is checkpointed and never repeated. A budget, deadline or provider stop
 * interrupts the run; a verification cap is a verdict.
 */
internal class WalletTokenVerificationCapture(
    private val rpc: SolanaReadRpc,
    private val store: WalletHistoryArtifactStore,
    private val reader: CapturedArtifactReader,
    private val concurrency: Int = 1,
    private val observer: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE,
) {
    private val log = LoggerFactory.getLogger(WalletTokenVerificationCapture::class.java)
    private val transactions = WalletTransactionEvidence()
    private val parallelFetch = BoundedParallelFetch()

    /** [checkpoint] saves a manifest and returns it as saved. The manifest returned is the last one checkpointed. */
    fun verify(
        directory: Path,
        saved: WalletHistoryManifest,
        budget: CaptureBudget,
        stop: CaptureStop,
        checkpoint: (WalletHistoryManifest) -> WalletHistoryManifest,
    ): WalletHistoryManifest {
        val wallet = WalletAddress.of(saved.wallet)
        val artifacts = reader.read(directory, saved, wallet)
        val assessment = checkNotNull(artifacts.assessment) { "Verification outside a token-account capture wallet=${saved.wallet}" }
        val target = checkNotNull(TokenTarget.of(saved)) { "Verification without a target mint wallet=${saved.wallet}" }
        var manifest = saved
        val verification = manifest.verification ?: VerificationStart.of(wallet,
            ActualFirstBuyVerification.target(assessment.tokenAccountFirstBuy(manifest, artifacts.inspected))).also {
            manifest = checkpoint(manifest.copy(verification = it))
            log.info("wallet_capture verification_started wallet={} mint={} firstBuy={}", wallet.value, target.mint.value, it.firstBuy?.signature)
        }
        val firstBuy = verification.firstBuy ?: return manifest
        if (verification.result != null) return manifest
        return Run(directory, manifest, target, firstBuy, assessment, artifacts.kinds, budget, stop, checkpoint).execute()
    }

    private inner class Run(
        private val directory: Path,
        private var manifest: WalletHistoryManifest,
        private val target: TokenTarget,
        private val firstBuy: VerifiedFirstBuy,
        private val assessment: TargetTokenAssessment,
        private var kinds: VerificationKinds,
        private val budget: CaptureBudget,
        private val stop: CaptureStop,
        private val checkpoint: (WalletHistoryManifest) -> WalletHistoryManifest,
    ) {
        private val attempted = mutableSetOf<Pair<VerificationListing, String>>()
        private val measurements = DetailMeasurements()
        private var halted: String? = null

        fun execute(): WalletHistoryManifest = try { loop() } finally {
            if (measurements.batches > 0) {
                log.info("wallet_capture verification_details wallet={} mint={} requestsSent={} cacheHits={} rateLimitedResponses={} retries={} elapsedMs={} concurrency={}",
                    manifest.wallet, target.mint.value, measurements.total.requestsSent, measurements.total.cacheHits, measurements.total.rateLimitedResponses,
                    measurements.total.retries, measurements.total.elapsed.toMillis(), concurrency)
            }
        }

        private fun loop(): WalletHistoryManifest {
            while (true) {
                val verification = current()
                val verdict = ActualFirstBuyVerification.verdict(verification, firstBuy, kinds)
                if (verdict != ActualFirstBuy.NotVerified) {
                    save(verification.copy(result = ActualFirstBuyVerification.stored(verdict)))
                    log.info("wallet_capture verification_finished wallet={} mint={} fetches={} result={}",
                        manifest.wallet, target.mint.value, verification.fetches, verdict)
                    return manifest
                }
                val next = try { step(verification) } catch (exception: CaptureProviderException) {
                    return interrupted(exception.category)
                } catch (exception: CaptureBudgetException) {
                    return interrupted(exception.category)
                } ?: return manifest
                save(next)
                halted?.let { return interrupted(it) }
            }
        }

        private fun step(verification: WalletTokenVerification): WalletTokenVerification? {
            return when (val creation = ActualFirstBuyVerification.creation(verification, firstBuy, kinds)) {
                CreationOutcome.Pending -> if (verification.creation.historyEnd) nextCandidate(verification) else mintPage(verification)
                is CreationOutcome.Identified -> when {
                    !verification.pumpAccumulator.complete -> accumulatorPage(verification, VerificationListing.PUMP_ACCUMULATOR, creation.slot.value)
                    !verification.pumpSwapAccumulator.complete -> accumulatorPage(verification, VerificationListing.PUMPSWAP_ACCUMULATOR, creation.slot.value)
                    else -> nextEntries(verification)
                }
                is CreationOutcome.Decided -> error("Verification has a verdict but was not finished wallet=${manifest.wallet}")
            }
        }

        private fun mintPage(verification: WalletTokenVerification): WalletTokenVerification {
            val creation = verification.creation
            val start = creation.cursor ?: WalletHistoryScanCursor(firstBuy.signature, firstBuy.slot)
            val entries = page(target.mint.value, start)
            var candidates = creation.candidates
            entries.filterNot { it.failed }.forEach { entry ->
                if (candidates.any { it.signature == entry.signature }) throw CaptureValidationException("CONFLICTING_SIGNATURE")
                candidates = (candidates + WalletHistorySelection(entry.signature, entry.slot)).takeLast(WalletHistoryCaptureLimits.CREATION_FETCHES)
            }
            val cursor = entries.lastOrNull()?.let { WalletHistoryScanCursor(it.signature, it.slot) } ?: creation.cursor
            return verification.copy(creation = creation.copy(
                cursor = cursor, pages = creation.pages + 1, entries = creation.entries + entries.size, oldestSlot = cursor?.slot,
                historyEnd = entries.size < ChainCaptureFormat.SIGNATURE_PAGE_SIZE, candidates = candidates,
            ))
        }

        private fun accumulatorPage(verification: WalletTokenVerification, listing: VerificationListing, creationSlot: Long): WalletTokenVerification {
            val scan = scan(verification, listing)
            val records = verification.records.toMutableList()
            var consumed = 0
            var skippedFailed = scan.skippedFailed
            var cursor = scan.cursor
            var listedFirstBuy = scan.listedFirstBuy
            for (entry in page(scan.address, scan.cursor)) {
                consumed++
                cursor = WalletHistoryScanCursor(entry.signature, entry.slot)
                if (entry.slot < creationSlot) break
                if (entry.signature == firstBuy.signature) {
                    if (entry.slot != firstBuy.slot || entry.failed) throw CaptureValidationException("CONFLICTING_SIGNATURE")
                    listedFirstBuy = true
                    continue
                }
                if (entry.failed) { skippedFailed++; continue }
                if (entry.slot > firstBuy.slot) continue
                val existing = records.firstOrNull { it.listing == listing && it.signature == entry.signature }
                if (existing == null) records += VerificationRecord(entry.signature, entry.slot, listing, VerificationOutcome.PENDING)
                else if (existing.slot != entry.slot) throw CaptureValidationException("CONFLICTING_SIGNATURE")
            }
            val scanned = scan.copy(cursor = cursor, pages = scan.pages + 1, entries = scan.entries + consumed, skippedFailed = skippedFailed,
                oldestSlot = cursor?.slot, listedFirstBuy = listedFirstBuy)
            val updated = verification.copy(records = records.toList())
            return when (listing) {
                VerificationListing.PUMP_ACCUMULATOR -> updated.copy(pumpAccumulator = scanned)
                VerificationListing.PUMPSWAP_ACCUMULATOR -> updated.copy(pumpSwapAccumulator = scanned)
                VerificationListing.MINT -> error("The mint listing is not an accumulator scan")
            }
        }

        /** The oldest candidate not yet resolved; a candidate already tried in this run waits for the next one. */
        private fun nextCandidate(verification: WalletTokenVerification): WalletTokenVerification? {
            val fetched = verification.records.filter { it.listing == VerificationListing.MINT }
            val index = fetched.indexOfFirst { it.outcome in WalletCaptureFormat.UNRESOLVED_VERIFICATION_OUTCOMES }.takeIf { it >= 0 } ?: fetched.size
            val candidate = verification.creation.candidates.reversed().getOrNull(index) ?: return null
            return resolve(verification, fetched.getOrNull(index) ?: VerificationRecord(candidate.signature, candidate.slot,
                VerificationListing.MINT, VerificationOutcome.PENDING))
        }

        /**
         * The oldest unresolved entries by slot then signature, each tried once per run, fetched together and applied oldest first,
         * so the earliest decoded wallet buy settles the verdict before any later entry is recorded. Entries that arrive after
         * the verdict, or after a failed or unstarted one, are discarded and fetched again by a later run if still needed.
         */
        private fun nextEntries(verification: WalletTokenVerification): WalletTokenVerification? {
            val planned = plan(verification)
            if (planned.isEmpty()) return null
            val toFetch = planned.filter { it.artifact == null }
            val fetched = parallelFetch.fetch(toFetch, concurrency, budget, observer::stopRequested) { fetchOne(it.record, VerificationSection.listingAddress(verification, target, it.record.listing)) }
            measurements.add(fetched.measurements)
            val results = fetched.completed.associateBy { it.index }
            var current = verification
            var fetchPosition = 0
            for (entry in planned) {
                attempted += entry.record.listing to entry.record.signature
                val updated = if (entry.artifact != null) reuse(current, entry.record, entry.artifact) else {
                    val done = results[fetchPosition++] ?: return unfinished(current, entry.record, fetched)
                    apply(current, entry.record, done.result)
                }
                current = ActualFirstBuyVerification.settled(updated, firstBuy, kinds)
                if (ActualFirstBuyVerification.verdict(current, firstBuy, kinds) != ActualFirstBuy.NotVerified) break
            }
            return current
        }

        /** The oldest entries to resolve next: as many fetches as the concurrency and the fetch cap allow, with the artifact-reusing ones between them. */
        private fun plan(verification: WalletTokenVerification): List<PlannedEntry> {
            val allowance = (WalletHistoryCaptureLimits.VERIFICATION_FETCHES - verification.fetches).coerceIn(1, concurrency)
            val oldestFirst = verification.records
                .filter {
                    it.listing != VerificationListing.MINT && it.outcome in WalletCaptureFormat.UNRESOLVED_VERIFICATION_OUTCOMES &&
                        (it.listing to it.signature) !in attempted
                }
                .sortedWith(compareBy({ it.slot }, { it.signature }, { it.listing }))
            val planned = mutableListOf<PlannedEntry>()
            val signatures = HashSet<String>()
            for (record in oldestFirst) {
                // A second listing of one signature reuses the first one's artifact, so it waits for the next round.
                if (!signatures.add(record.signature)) break
                val artifact = reusable(verification, record.signature)
                if (artifact == null && planned.count { it.artifact == null } == allowance) break
                planned += PlannedEntry(record, artifact)
            }
            return planned
        }

        /** Saves what was applied before the first entry that has no result, then ends the run for the reason that entry has none. */
        private fun unfinished(
            applied: WalletTokenVerification,
            record: VerificationRecord,
            fetched: ParallelFetchResult<PlannedEntry, DetailFetch<VerificationOutcome>>,
        ): WalletTokenVerification {
            val failure = fetched.failure
            if (failure == null) {
                halted = STOP_REQUESTED
                return applied
            }
            save(if (failure is CaptureProviderException) replace(applied, record.copy(outcome = VerificationOutcome.PROVIDER_UNAVAILABLE)) else applied)
            throw failure
        }

        private fun resolve(verification: WalletTokenVerification, record: VerificationRecord): WalletTokenVerification? {
            if (!attempted.add(record.listing to record.signature)) return null
            val artifact = reusable(verification, record.signature)
            if (artifact != null) return reuse(verification, record, artifact)
            val fetched = try {
                fetchOne(record, VerificationSection.listingAddress(verification, target, record.listing))
            } catch (exception: CaptureProviderException) {
                save(replace(verification, record.copy(outcome = VerificationOutcome.PROVIDER_UNAVAILABLE)))
                throw exception
            }
            return apply(verification, record, fetched)
        }

        private fun fetchOne(record: VerificationRecord, listingAddress: String): DetailFetch<VerificationOutcome> = try {
            val raw = ChainCaptureReads.transaction(rpc, record.signature, budget)
            DetailFetch.Fetched(raw, transactions.classifyVerification(raw, record.signature, record.slot, listingAddress))
        } catch (exception: CaptureProviderException) {
            if (exception.category == UNSUPPORTED_VERSION) DetailFetch.UnsupportedVersion else throw exception
        }

        private fun reuse(verification: WalletTokenVerification, record: VerificationRecord, artifact: StoredArtifact): WalletTokenVerification {
            val listingAddress = VerificationSection.listingAddress(verification, target, record.listing)
            val node = store.readArtifact(directory, artifact.path, artifact.bytes, artifact.sha256)
            val outcome = transactions.classifyVerification(node, record.signature, record.slot, listingAddress)
            return replace(verification, if (outcome == VerificationOutcome.CAPTURED) {
                classify(record.listing, record.signature, node)
                record.copy(outcome = outcome, artifact = artifact.path, sha256 = artifact.sha256, bytes = artifact.bytes)
            } else record.copy(outcome = outcome, artifact = null, sha256 = null, bytes = 0))
        }

        private fun apply(verification: WalletTokenVerification, record: VerificationRecord, fetched: DetailFetch<VerificationOutcome>): WalletTokenVerification {
            val counted = verification.copy(fetches = verification.fetches + 1)
            return when (fetched) {
                DetailFetch.UnsupportedVersion -> replace(counted, record.copy(outcome = VerificationOutcome.UNSUPPORTED_VERSION))
                is DetailFetch.Fetched -> {
                    if (fetched.outcome != VerificationOutcome.CAPTURED) return replace(counted, record.copy(outcome = fetched.outcome))
                    val written = store.writeVerificationTransaction(directory, record.signature, fetched.raw)
                    classify(record.listing, record.signature, fetched.raw)
                    replace(counted, record.copy(outcome = fetched.outcome, artifact = written.path, sha256 = written.sha256, bytes = written.bytes))
                }
            }
        }

        private fun classify(listing: VerificationListing, signature: String, node: JsonNode) {
            kinds = when (listing) {
                VerificationListing.MINT -> kinds.copy(candidates = kinds.candidates + (signature to assessment.candidateKind(node)))
                VerificationListing.PUMP_ACCUMULATOR, VerificationListing.PUMPSWAP_ACCUMULATOR ->
                    kinds.copy(entries = kinds.entries + (signature to assessment.entryKind(node)))
            }
        }

        /** An artifact this capture already holds for [signature]: a token-account record's, or another verification record's. */
        private fun reusable(verification: WalletTokenVerification, signature: String): StoredArtifact? =
            manifest.records.firstNotNullOfOrNull { record -> record.takeIf { it.signature == signature }?.let { StoredArtifact.of(it.artifact, it.sha256, it.bytes) } }
                ?: verification.records.firstNotNullOfOrNull { record ->
                    record.takeIf { it.signature == signature }?.let { StoredArtifact.of(it.artifact, it.sha256, it.bytes) }
                }

        private fun replace(verification: WalletTokenVerification, record: VerificationRecord): WalletTokenVerification {
            val index = verification.records.indexOfFirst { it.listing == record.listing && it.signature == record.signature }
            val records = if (index < 0) verification.records + record else verification.records.toMutableList().also { it[index] = record }.toList()
            return verification.copy(records = records)
        }

        private fun page(address: String, start: WalletHistoryScanCursor?): List<SignatureListingEntry> {
            val entries = SignatureListing.parse(ChainCaptureReads.signaturePage(rpc, address, before = start?.signature, until = null, budget))
            if (!SignatureListing.isNewestFirst(entries, start?.slot)) throw CaptureValidationException("SIGNATURE_ORDER")
            if (entries.any { it.signature == start?.signature }) throw CaptureValidationException("NON_PROGRESSING_PAGE")
            return entries
        }

        private fun scan(verification: WalletTokenVerification, listing: VerificationListing): AccumulatorScan = when (listing) {
            VerificationListing.PUMP_ACCUMULATOR -> verification.pumpAccumulator
            VerificationListing.PUMPSWAP_ACCUMULATOR -> verification.pumpSwapAccumulator
            VerificationListing.MINT -> error("The mint listing is not an accumulator scan")
        }

        private fun current(): WalletTokenVerification =
            checkNotNull(manifest.verification) { "Verification run without a verification section wallet=${manifest.wallet}" }

        private fun save(verification: WalletTokenVerification) {
            manifest = checkpoint(manifest.copy(verification = ActualFirstBuyVerification.settled(verification, firstBuy, kinds)))
        }

        private fun interrupted(category: String): WalletHistoryManifest {
            log.warn("wallet_capture verification interrupted category={} wallet={}", category, manifest.wallet)
            stop.record(category)
            return manifest
        }
    }

    /** An entry to resolve, with the artifact it can reuse instead of a fetch, if any. */
    private data class PlannedEntry(val record: VerificationRecord, val artifact: StoredArtifact?)

    private companion object {
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
        const val STOP_REQUESTED = "STOP_REQUESTED"
    }
}
