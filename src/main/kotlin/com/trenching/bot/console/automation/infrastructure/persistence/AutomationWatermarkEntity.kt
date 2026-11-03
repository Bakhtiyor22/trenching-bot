package com.trenching.bot.console.automation.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * The newest wallet action discovery has processed, one row per wallet (plan Rule 9). While a backlog larger than one scan is
 * worked through, [resumeBefore] is where the next scan continues, [pending] the catch-up's newest action, which becomes the
 * watermark once a scan reaches it, and [catchUpStartedAt] the start of the scan from the newest transaction that began the
 * catch-up, which becomes [updatedAt] once it completes; [resumeSince] is the initial lookback of a first scan's catch-up,
 * which has no watermark to reach. All four are null otherwise. [updatedAt] is the start of the last complete scan. [coveredFrom]
 * is how far back live discovery covers the wallet without gaps (plan Rule 32): its first scan's lookback start, moved back
 * only by a backfill; null means no coverage is known.
 */
@Entity
@Table(name = "automation_watermarks")
class AutomationWatermarkEntity(
    @Id @Column(name = "wallet", nullable = false, length = 44) var wallet: String,
    @Column(name = "slot", nullable = false) var slot: Long,
    @Column(name = "signature", nullable = false, length = 88) var signature: String,
    @Column(name = "instruction_index", nullable = false) var instructionIndex: Int,
    @Column(name = "inner_index", nullable = false) var innerIndex: Int,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    @Column(name = "resume_before", nullable = true, length = 88)
    var resumeBefore: String? = null

    @Column(name = "resume_since", nullable = true)
    var resumeSince: Instant? = null

    @Column(name = "catch_up_started_at", nullable = true)
    var catchUpStartedAt: Instant? = null

    @Column(name = "covered_from", nullable = true)
    var coveredFrom: Instant? = null

    @Embedded
    var pending: DiscoveryCursorColumns? = null

    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
