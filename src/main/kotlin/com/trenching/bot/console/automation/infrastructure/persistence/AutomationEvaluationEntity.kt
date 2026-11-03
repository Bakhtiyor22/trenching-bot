package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.EvaluationStatus
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
import java.math.BigDecimal
import java.time.Instant
import org.springframework.data.domain.Persistable

/**
 * One sweep entry's score over a closing session's window (plan Rules 24–27). [id] is the SHA-256 of `session|plan|label`;
 * [windowSessionIds] is the window's session ids as an ordered JSON array. P&L is in SOL at scale 12.
 *
 * The id is assigned, so the row is [Persistable]: a new row is always inserted, never merged into a stored one, and a second
 * evaluation of the same entry fails on the key instead of replacing the first (Rule 26).
 */
@Entity
@Table(
    name = "automation_evaluations",
    uniqueConstraints = [UniqueConstraint(name = "uq_automation_evaluations_entry", columnNames = ["session_id", "plan_id", "label"])],
    indexes = [
        Index(name = "idx_automation_evaluations_rank", columnList = "status, worst_case_pnl, evaluated_at"),
        Index(name = "idx_automation_evaluations_config", columnList = "plan_id, label, evaluated_at"),
    ],
)
class AutomationEvaluationEntity(
    @Id @Column(name = "id", nullable = false, length = 64) private var id: String,
    @Column(name = "session_id", nullable = false, length = 64) var sessionId: String,
    @Column(name = "plan_id", nullable = false, length = 32) var planId: String,
    @Column(name = "label", nullable = false, length = 16) var label: String,
    @Column(name = "window_session_ids", nullable = false, columnDefinition = "text") var windowSessionIds: String,
    @Column(name = "window_sessions", nullable = false) var windowSessions: Int,
    @Column(name = "signals", nullable = false) var signals: Int,
    @Column(name = "scored", nullable = false) var scored: Int,
    @Column(name = "unscored", nullable = false) var unscored: Int,
    @Column(name = "realized_pnl", nullable = false, precision = 38, scale = 12) var realizedPnl: BigDecimal,
    @Column(name = "worst_case_pnl", nullable = false, precision = 38, scale = 12) var worstCasePnl: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 12) var status: EvaluationStatus,
    @Column(name = "evaluated_at", nullable = false) var evaluatedAt: Instant,
) : Persistable<String> {
    @Transient
    private var stored: Boolean = false

    override fun getId(): String = id

    override fun isNew(): Boolean = !stored

    @PostPersist
    @PostLoad
    fun markStored() {
        stored = true
    }
}
