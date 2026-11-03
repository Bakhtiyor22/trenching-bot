package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.portfolio.domain.PositionState
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
 * One sweep entry replayed on one captured buy (plan Rules 21–23 and 27): its run, or the refusal code in [outcome] when the
 * entry got no run. [id] is the SHA-256 of `item|plan|label`. P&L is in SOL at scale 12.
 *
 * The id is assigned, so the row is [Persistable]: a new row is always inserted, never merged into a stored one, and a second
 * row for the same entry fails on the key instead of replacing the first.
 */
@Entity
@Table(
    name = "automation_sweep_runs",
    uniqueConstraints = [UniqueConstraint(name = "uq_automation_sweep_runs_entry", columnNames = ["item_id", "plan_id", "label"])],
    indexes = [
        Index(name = "idx_automation_sweep_runs_run", columnList = "run_id"),
        Index(name = "idx_automation_sweep_runs_session", columnList = "session_id, plan_id, label"),
    ],
)
class AutomationSweepRunEntity(
    @Id @Column(name = "id", nullable = false, length = 64) private var id: String,
    @Column(name = "item_id", nullable = false, length = 64) var itemId: String,
    @Column(name = "session_id", nullable = false, length = 64) var sessionId: String,
    @Column(name = "plan_id", nullable = false, length = 32) var planId: String,
    @Column(name = "label", nullable = false, length = 16) var label: String,
    @Column(name = "run_id", length = 64) var runId: String?,
    @Column(name = "configuration_hash", length = 64) var configurationHash: String?,
    @Column(name = "engine_version", length = 64) var engineVersion: String?,
    @Column(name = "outcome", nullable = false, length = 40) var outcome: String,
    @Enumerated(EnumType.STRING) @Column(name = "position_state", length = 8) var positionState: PositionState?,
    @Column(name = "is_signal", nullable = false) var isSignal: Boolean,
    @Column(name = "realized_pnl", nullable = false, precision = 38, scale = 12) var realizedPnl: BigDecimal,
    @Column(name = "worst_case_pnl", nullable = false, precision = 38, scale = 12) var worstCasePnl: BigDecimal,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
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
