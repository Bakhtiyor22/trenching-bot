package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.strategy.domain.PriceMeasure
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
import java.math.BigDecimal
import java.time.Instant
import org.springframework.data.domain.Persistable

/**
 * One variation of a sweep plan (plan Data Model): a sweep entry kept as a row. [id] is the SHA-256 of `plan|label`. The
 * configuration columns are written once, at insert (plan Rule 1); only [state], [archivedAt] and [replacedByLabel] change, and
 * only from active to archived. Numbers are SOL or USD at scale 12.
 *
 * The id is assigned, so the row is [Persistable], as [AutomationSweepRunEntity] is: a new row is always inserted, and a second
 * row for the same label, sequence number or request id fails on its key.
 */
@Entity
@Table(
    name = "automation_variations",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_automation_variations_label", columnNames = ["plan_id", "label"]),
        UniqueConstraint(name = "uq_automation_variations_seq", columnNames = ["plan_id", "seq"]),
        UniqueConstraint(name = "uq_automation_variations_request", columnNames = ["plan_id", "request_id"]),
    ],
    indexes = [Index(name = "idx_automation_variations_state", columnList = "plan_id, state, seq")],
)
class AutomationVariationEntity(
    @Id @Column(name = "id", nullable = false, length = 64) private val id: String,
    @Column(name = "plan_id", nullable = false, updatable = false, length = 32) val planId: String,
    @Column(name = "label", nullable = false, updatable = false, length = 16) val label: String,
    @Column(name = "seq", nullable = false, updatable = false) val seq: Int,
    @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 8) var state: VariationState,
    @Column(name = "request_id", updatable = false, length = 64) val requestId: String?,
    @Column(name = "replaces_label", updatable = false, length = 16) val replacesLabel: String?,
    @Column(name = "replaced_by_label", length = 16) var replacedByLabel: String?,
    @Column(name = "active_from", updatable = false) val activeFrom: Instant?,
    @Column(name = "archived_at") var archivedAt: Instant?,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
    @Column(name = "strategy_name", nullable = false, updatable = false, length = 64) val strategyName: String,
    @Column(name = "strategy_version", nullable = false, updatable = false, length = 32) val strategyVersion: String,
    @Column(name = "notional", nullable = false, updatable = false, precision = 38, scale = 12) val notional: BigDecimal,
    @Column(name = "min_market_cap_usd", nullable = false, updatable = false, precision = 38, scale = 12) val minMarketCapUsd: BigDecimal,
    @Column(name = "transaction_cost_sol", nullable = false, updatable = false, precision = 38, scale = 12) val transactionCostSol: BigDecimal,
    @Column(name = "min_signal_sol", nullable = false, updatable = false, precision = 38, scale = 12) val minSignalSol: BigDecimal,
    @Column(name = "max_age_slots", nullable = false, updatable = false) val maxAgeSlots: Long,
    @Column(name = "horizon_slots", nullable = false, updatable = false) val horizonSlots: Long,
    @Column(name = "latency_slots", nullable = false, updatable = false) val latencySlots: Long,
    @Column(name = "dip_bps", nullable = false, updatable = false) val dipBps: Int,
    @Column(name = "take_profit_bps", nullable = false, updatable = false) val takeProfitBps: Int,
    @Column(name = "stop_loss_bps", nullable = false, updatable = false) val stopLossBps: Int,
    @Column(name = "quote_mint", nullable = false, updatable = false, length = 44) val quoteMint: String,
    @Column(name = "dip_window_ms", updatable = false) val dipWindowMs: Long?,
    @Column(name = "max_hold_ms", updatable = false) val maxHoldMs: Long?,
    @Column(name = "hard_exit_start_ms", updatable = false) val hardExitStartMs: Long?,
    @Enumerated(EnumType.STRING) @Column(name = "price_measure", nullable = false, updatable = false, length = 16) val priceMeasure: PriceMeasure,
    @Column(name = "follow_wallet_sells", nullable = false, updatable = false) val followWalletSells: Boolean,
) : Persistable<String> {
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

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
