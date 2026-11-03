package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationStep
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.math.BigInteger
import java.time.Instant

/**
 * One discovered wallet buy and its progress through capture and sweep (plan Rules 7, 8, 11, 13 and 27): the first buy of
 * each mint per wallet, identified by its signature, instruction and inner index (-1 for a top-level event).
 */
@Entity
@Table(
    name = "automation_items",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_automation_items_buy", columnNames = ["wallet", "signature", "instruction_index", "inner_index"]),
        UniqueConstraint(name = "uq_automation_items_mint", columnNames = ["wallet", "mint"]),
    ],
    indexes = [
        Index(name = "idx_automation_items_due", columnList = "state, next_action_at, block_time"),
        Index(name = "idx_automation_items_session", columnList = "session_id, block_time"),
        Index(name = "idx_automation_items_backfill", columnList = "backfill_id, state"),
    ],
)
class AutomationItemEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "session_id", nullable = false, length = 64) var sessionId: String,
    @Column(name = "wallet", nullable = false, length = 44) var wallet: String,
    @Column(name = "mint", nullable = false, length = 44) var mint: String,
    @Column(name = "signature", nullable = false, length = 88) var signature: String,
    @Column(name = "instruction_index", nullable = false) var instructionIndex: Int,
    @Column(name = "inner_index", nullable = false) var innerIndex: Int,
    @Column(name = "slot", nullable = false) var slot: Long,
    @Column(name = "block_time") var blockTime: Instant?,
    @Column(name = "curve_payment_lamports", nullable = false, precision = 20, scale = 0) var curvePaymentLamports: BigInteger,
    @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 24) var state: AutomationItemState,
    @Enumerated(EnumType.STRING) @Column(name = "step", length = 16) var step: AutomationStep?,
    @Column(name = "step_attempt", nullable = false) var stepAttempt: Int,
    @Column(name = "capture_session_id", length = 80) var captureSessionId: String?,
    @Column(name = "operation_id", length = 80) var operationId: String?,
    @Column(name = "dataset_id", length = 64) var datasetId: String?,
    @Column(name = "classification", length = 32) var classification: String?,
    @Column(name = "failure_code", length = 64) var failureCode: String?,
    @Column(name = "failure_message", length = 512) var failureMessage: String?,
    @Column(name = "next_action_at") var nextActionAt: Instant?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    /** The provider requests of the item's capture session so far, refreshed whenever a capture step ends; null before any. */
    @Column(name = "requests_used", nullable = true)
    var requestsUsed: Int? = null

    /** The backfill that found the buy (plan Rule 38), null for a buy live discovery found. */
    @Column(name = "backfill_id", nullable = true, length = 64)
    var backfillId: String? = null

    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
