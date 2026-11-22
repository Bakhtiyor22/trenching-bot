package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant

/**
 * Variation rows written straight to the table, to stand in for an edit, which is what the edit transaction does: the archived row
 * and its replacement, as the edit leaves them (plan Rule 2).
 */
class AutomationVariationRows(
    private val rows: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
    private val planId: String = SweepPlans.DECU_SWEEP_1.id,
) {
    /** Archives [label] at [at] and stores its replacement, active from [at] under the next sequence number; returns the replacement. */
    fun edit(label: String, at: Instant, requestId: String, takeProfitBps: Int = 8000, stopLossBps: Int = 5000): Variation {
        val replaced = checkNotNull(rows.findOneByPlanIdAndLabel(planId, label)) { "No variation $label" }
        val seq = checkNotNull(rows.findMaxSeq(planId)) { "No variation in the plan" } + 1
        val newLabel = "V" + seq.toString().padStart(2, '0')
        val config = withLevels(mapper.toConfig(replaced), takeProfitBps, stopLossBps)
        rows.saveAndFlush(replaced.apply {
            state = VariationState.ARCHIVED
            archivedAt = at
            replacedByLabel = newLabel
        })
        val created = Variation(planId, newLabel, seq, VariationState.ACTIVE, config, at, null, label, null, at)
        rows.saveAndFlush(mapper.toEntity(created, requestId))
        return created
    }

    /** Archives every active variation at [at], which no edit can do: a plan always keeps one. */
    fun archiveAll(at: Instant) {
        rows.saveAll(rows.findAll().onEach { it.state = VariationState.ARCHIVED; it.archivedAt = at })
        rows.flush()
    }

    companion object {
        fun withLevels(config: SimulationConfig, takeProfitBps: Int, stopLossBps: Int): SimulationConfig = SimulationConfig.of(
            config.notional, config.maxAgeSlots, config.dipBps, takeProfitBps, stopLossBps, config.minMarketCapUsd, config.horizonSlots,
            config.latencySlots, config.transactionCostSol, config.strategyName, config.strategyVersion, config.quoteMint, config.dipWindowMs,
            config.maxHoldMs, config.hardExitStartMs, config.priceMeasure, config.followWalletSells, config.minSignalSol,
        )

        /** A copy of [base] under another id and, as given, other keys, to meet each unique key on its own. */
        fun entityLike(
            base: AutomationVariationEntity,
            label: String = base.label,
            seq: Int = base.seq,
            requestId: String? = base.requestId,
        ) = AutomationVariationEntity(
            id = AutomationIds.variation(base.planId, "copy-of-${base.label}-$label-$seq"), planId = base.planId, label = label, seq = seq,
            state = base.state, requestId = requestId, replacesLabel = base.replacesLabel, replacedByLabel = base.replacedByLabel,
            activeFrom = base.activeFrom, archivedAt = base.archivedAt, createdAt = base.createdAt, strategyName = base.strategyName,
            strategyVersion = base.strategyVersion, notional = base.notional, minMarketCapUsd = base.minMarketCapUsd,
            transactionCostSol = base.transactionCostSol, minSignalSol = base.minSignalSol, maxAgeSlots = base.maxAgeSlots,
            horizonSlots = base.horizonSlots, latencySlots = base.latencySlots, dipBps = base.dipBps, takeProfitBps = base.takeProfitBps,
            stopLossBps = base.stopLossBps, quoteMint = base.quoteMint, dipWindowMs = base.dipWindowMs, maxHoldMs = base.maxHoldMs,
            hardExitStartMs = base.hardExitStartMs, priceMeasure = base.priceMeasure, followWalletSells = base.followWalletSells,
        )
    }
}
