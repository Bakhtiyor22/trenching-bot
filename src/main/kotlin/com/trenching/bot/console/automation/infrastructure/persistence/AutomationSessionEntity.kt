package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.SessionCloseReason
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

/**
 * A run of one wallet's discovered buys with no gap longer than the session idle time (plan Rule 10). [id] is the SHA-256 of
 * `wallet|` the item the session opened for and never changes. [firstItemId] is that item while it stands; once an earlier buy
 * of its mint replaced it, the session's earliest remaining item, so it need not be the earliest buy nor hash to [id].
 */
@Entity
@Table(
    name = "automation_sessions",
    uniqueConstraints = [UniqueConstraint(name = "uq_automation_sessions_first_item", columnNames = ["first_item_id"])],
    indexes = [
        Index(name = "idx_automation_sessions_wallet_state", columnList = "wallet, state, last_buy_at"),
        Index(name = "idx_automation_sessions_first_buy", columnList = "wallet, first_buy_at"),
        Index(name = "idx_automation_sessions_backfill", columnList = "backfill_id, state"),
    ],
)
class AutomationSessionEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "wallet", nullable = false, length = 44) var wallet: String,
    @Column(name = "first_item_id", nullable = false, length = 64) var firstItemId: String,
    @Column(name = "first_buy_at", nullable = false) var firstBuyAt: Instant,
    @Column(name = "last_buy_at", nullable = false) var lastBuyAt: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 16) var state: AutomationSessionState,
    @Column(name = "closed_at") var closedAt: Instant?,
    @Enumerated(EnumType.STRING) @Column(name = "close_reason", length = 16) var closeReason: SessionCloseReason?,
    @Column(name = "below_filter_count", nullable = false) var belowFilterCount: Int,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
) {
    /** The backfill whose buys the session groups (plan Rule 38), null for a session of live discovery. */
    @Column(name = "backfill_id", nullable = true, length = 64)
    var backfillId: String? = null

    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
