package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.marketdata.MarketListedSignature
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowRecord
import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import java.time.Duration

/** Applies newest-first listing pages of a window's base mint to its discovery state. */
internal object MarketWindowScan {
    /**
     * Counts failed and newer entries, selects successful in-window ones and completes the window at the first entry
     * older than it or at a short page. Holding more signatures than [capacity] that are not in [admitted] makes the
     * window [MarketWindowCoverage.OVER_CAP], so no partial selection is ever fetched. That includes a selection kept from
     * before an extension, which is checked again when the window completes.
     */
    fun apply(window: MarketWindowRecord, entries: List<SignatureListingEntry>, admitted: Set<String>, capacity: Int): MarketWindowRecord {
        val cursor = window.scanCursor
        if (!SignatureListing.isNewestFirst(entries, cursor?.slot)) throw CaptureValidationException("SIGNATURE_ORDER")
        val seen = window.selection.associate { it.signature to SignatureListingEntry(it.signature, it.slot, failed = false) }.toMutableMap()
        val selection = window.selection.toMutableList()
        var unadmitted = selection.count { it.signature !in admitted }
        var scanned = window.scanned
        var skippedFailed = window.skippedFailed
        var skippedNewer = window.skippedNewer
        var lastScanned = cursor
        var reachedOlder = false
        fun overCap() = window.copy(coverage = MarketWindowCoverage.OVER_CAP, pagesScanned = window.pagesScanned + 1,
            scanned = scanned, skippedFailed = skippedFailed, skippedNewer = skippedNewer,
            selected = selection.size, selection = emptyList(), scanCursor = lastScanned)
        for (entry in entries) {
            if (entry.signature == cursor?.signature) throw CaptureValidationException("NON_PROGRESSING_PAGE")
            if (entry.slot < window.fromSlot) {
                reachedOlder = true
                break
            }
            val previous = seen.putIfAbsent(entry.signature, entry)
            if (previous != null && previous != entry) throw CaptureValidationException("CONFLICTING_SIGNATURE")
            scanned++
            lastScanned = MarketListedSignature(entry.signature, entry.slot)
            when {
                previous != null -> Unit
                entry.failed -> skippedFailed++
                entry.slot > window.toSlot -> skippedNewer++
                else -> {
                    selection += MarketListedSignature(entry.signature, entry.slot)
                    if (entry.signature !in admitted) unadmitted++
                    if (unadmitted > capacity) return overCap()
                }
            }
        }
        val complete = reachedOlder || entries.size < ChainCaptureFormat.SIGNATURE_PAGE_SIZE
        if (complete && unadmitted > capacity) return overCap()
        return window.copy(coverage = if (complete) MarketWindowCoverage.COMPLETE else MarketWindowCoverage.PENDING,
            pagesScanned = window.pagesScanned + 1, scanned = scanned, skippedFailed = skippedFailed, skippedNewer = skippedNewer,
            selected = selection.size, selection = selection.toList(), scanCursor = lastScanned)
    }

    /** Whether the window's listing has not started yet, so it must first know that the confirmed tip has passed its end. */
    fun needsTip(window: MarketWindowRecord): Boolean =
        window.pagesScanned == 0 && window.coverage in MarketCaptureFormat.OPEN_COVERAGES

    /**
     * Opens an unstarted window only once [tip], the confirmed tip read before its first page, has reached its end: a
     * newest-first listing from an earlier tip ends at the window's start exactly like a finished one, yet misses the rest of
     * it. Until then the window is [MarketWindowCoverage.HORIZON_NOT_ELAPSED] and nothing of it is listed.
     */
    fun atTip(window: MarketWindowRecord, tip: Long): MarketWindowRecord = when {
        !needsTip(window) -> window
        tip >= window.toSlot -> window.copy(coverage = MarketWindowCoverage.PENDING)
        else -> window.copy(coverage = MarketWindowCoverage.HORIZON_NOT_ELAPSED)
    }

    /** An estimate of how long the confirmed tip needs to reach the window's end. */
    fun untilEnd(window: MarketWindowRecord, tip: Long): Duration =
        MarketCaptureFormat.ESTIMATED_SLOT_DURATION.multipliedBy((window.toSlot - tip).coerceAtLeast(0))

    /** Keeps the window's cursor, counts and in-window selection, so a resume with more pages can extend it losslessly. */
    fun boundReached(window: MarketWindowRecord): MarketWindowRecord =
        window.copy(coverage = MarketWindowCoverage.COVERAGE_INCOMPLETE)

    /** Reopens a window that stopped at the old page bound; it continues before its saved cursor. */
    fun extended(window: MarketWindowRecord): MarketWindowRecord =
        if (window.coverage == MarketWindowCoverage.COVERAGE_INCOMPLETE) window.copy(coverage = MarketWindowCoverage.PENDING) else window
}
