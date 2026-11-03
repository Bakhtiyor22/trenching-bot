package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant

/**
 * A wallet's discovery position (plan Rule 9 and its 2026-11-03 amendment). [cursor] is the watermark. While a backlog larger
 * than one scan is worked through, [resumeBefore] is the oldest `nextBefore` read so far, where the next scan continues, and
 * [pending] the newest action read so far in that catch-up, which becomes the watermark once a scan reaches the bound.
 *
 * Until the wallet's first catch-up completes, [resumeSince] keeps its initial lookback, which is the bound, and [cursor] is
 * provisional ([DiscoveryCursor.ORIGIN] while no action has been found). [updatedAt] is the start of the last complete scan:
 * one from the newest transaction down to its bound, or the scan from the newest transaction that began the catch-up a later
 * scan completed (during the first catch-up, that catch-up's start). [discoveredThrough] is what that scan covers, the instant
 * up to which every buy has been discovered, or null while the first catch-up runs.
 */
data class DiscoveryWatermark(
    val cursor: DiscoveryCursor,
    val resumeBefore: String?,
    val resumeSince: Instant?,
    val pending: DiscoveryCursor?,
    val updatedAt: Instant,
) {
    val discoveredThrough: Instant? get() = updatedAt.takeIf { resumeSince == null }?.let(::coveredBy)

    /** Where the next scan stops paging. */
    fun bound(): DiscoveryBound = resumeSince?.let(DiscoveryBound::Since) ?: DiscoveryBound.After(cursor)

    companion object {
        /** How long the provider's listing may take to show a landed transaction (plan Rule 10, amended 2026-11-03). */
        val INDEXING_MARGIN: Duration = Duration.ofSeconds(60)

        /** The instant up to which a complete scan that started at [scanStart] has discovered every buy. */
        fun coveredBy(scanStart: Instant): Instant = scanStart.minus(INDEXING_MARGIN)
    }
}
