package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.BackfillState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import org.springframework.data.domain.Persistable

/**
 * A date-range job that discovers the wallet's older buys (plan Key Decisions 6-10, Rules 35-43). [id] is the SHA-256 of
 * `wallet|` [requestId], so a repeated create finds the stored row, and [requestId] is unique too. [activeWallet] is the wallet
 * while the backfill is queued, scanning or capturing and null once it ends; its unique key lets one backfill per wallet hold it
 * (a null never conflicts). [rangeStart] and [rangeEnd] are the range after clipping to live coverage. [resumeBefore] is where the
 * scan continues, null before it starts and after it ends; a budget-stopped scan keeps it, so it tells a stopped scan from a
 * finished one. [scannedTo] is the oldest instant the scan has read down to (the range start once it finished), so `[scannedTo,
 * rangeEnd)` is what an ended backfill covers even when it stopped early; null before a page was read. [summary] holds a dry run's
 * tally while it scans and as its result. The counts cover the range read so far; [skippedNoBlockTime] counts the actions the scan
 * could not place in the range because the listing gave them no block time. [filtered] is set when the backfill narrows what the
 * capture filter alone would take (a token list, or a minimum signal above the capture minimum): it still captures its buys, but its
 * range is never coverage, since buys that would pass the capture filter produced no item.
 *
 * The id is assigned, so the row is [Persistable]: a new row is always inserted, never merged into a stored one.
 */
@Entity
@Table(
    name = "automation_backfills",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_automation_backfills_request", columnNames = ["request_id"]),
        UniqueConstraint(name = "uq_automation_backfills_active", columnNames = ["active_wallet"]),
    ],
    indexes = [Index(name = "idx_automation_backfills_wallet_created", columnList = "wallet, created_at")],
)
class AutomationBackfillEntity(
    @Id @Column(name = "id", nullable = false, length = 64) private var id: String,
    @Column(name = "request_id", nullable = false, length = 80) var requestId: String,
    @Column(name = "wallet", nullable = false, length = 44) var wallet: String,
    @Column(name = "active_wallet", length = 44) var activeWallet: String?,
    @Column(name = "from_day", nullable = false) var fromDay: LocalDate,
    @Column(name = "to_day", nullable = false) var toDay: LocalDate,
    @Column(name = "range_start", nullable = false) var rangeStart: Instant,
    @Column(name = "range_end", nullable = false) var rangeEnd: Instant,
    @Column(name = "dry_run", nullable = false) var dryRun: Boolean,
    @Column(name = "min_signal_lamports", precision = 20, scale = 0) var minSignalLamports: BigInteger?,
    @Column(name = "mints", columnDefinition = "text") var mints: String?,
    @Column(name = "request_budget", nullable = false) var requestBudget: Long,
    @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 16) var state: BackfillState,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) : Persistable<String> {
    @Column(name = "discovery_requests", nullable = false)
    var discoveryRequests: Long = 0

    @Column(name = "resume_before", length = 88)
    var resumeBefore: String? = null

    @Column(name = "actions_scanned", nullable = false)
    var actionsScanned: Int = 0

    @Column(name = "buys_found", nullable = false)
    var buysFound: Int = 0

    @Column(name = "buys_passing", nullable = false)
    var buysPassing: Int = 0

    @Column(name = "items_created", nullable = false)
    var itemsCreated: Int = 0

    @Column(name = "skipped_by_filter", nullable = false)
    var skippedByFilter: Int = 0

    @Column(name = "scanned_to")
    var scannedTo: Instant? = null

    @Column(name = "skipped_no_block_time", nullable = false, columnDefinition = "integer not null default 0")
    var skippedNoBlockTime: Int = 0

    @Column(name = "filtered", nullable = false, columnDefinition = "boolean not null default false")
    var filtered: Boolean = false

    @Column(name = "summary", columnDefinition = "text")
    var summary: String? = null

    @Column(name = "estimated_requests")
    var estimatedRequests: Long? = null

    @Column(name = "finished_at")
    var finishedAt: Instant? = null

    @Version @Column(name = "version", nullable = false)
    var version: Long? = null

    @Transient
    private var stored: Boolean = false

    /** Ends the backfill in [endState] at [at], which frees the wallet's active slot. */
    fun end(endState: BackfillState, at: Instant) {
        state = endState
        activeWallet = null
        finishedAt = at
        updatedAt = at
    }

    override fun getId(): String = id

    override fun isNew(): Boolean = !stored

    @PostPersist
    @PostLoad
    fun markStored() {
        stored = true
    }
}
