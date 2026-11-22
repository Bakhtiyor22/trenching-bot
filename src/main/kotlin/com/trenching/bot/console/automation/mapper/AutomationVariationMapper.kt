package com.trenching.bot.console.automation.mapper

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.automation.domain.SweepEntry
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationEntity
import com.trenching.bot.strategy.domain.SimulationConfig
import org.springframework.stereotype.Component

/**
 * Variation rows to the values the services use and back. By hand, not MapStruct: a [SimulationConfig] is built through its
 * validating factory, which a generated mapper cannot call.
 */
@Component
class AutomationVariationMapper {
    fun toVariation(entity: AutomationVariationEntity): Variation = Variation(
        planId = entity.planId, label = entity.label, seq = entity.seq, state = entity.state, config = toConfig(entity),
        activeFrom = entity.activeFrom, archivedAt = entity.archivedAt, replacesLabel = entity.replacesLabel,
        replacedByLabel = entity.replacedByLabel, createdAt = entity.createdAt,
    )

    fun toConfig(entity: AutomationVariationEntity): SimulationConfig = SimulationConfig.of(
        notional = entity.notional, maxAgeSlots = entity.maxAgeSlots, dipBps = entity.dipBps, takeProfitBps = entity.takeProfitBps,
        stopLossBps = entity.stopLossBps, minMarketCapUsd = entity.minMarketCapUsd, horizonSlots = entity.horizonSlots,
        latencySlots = entity.latencySlots, transactionCostSol = entity.transactionCostSol, strategyName = entity.strategyName,
        strategyVersion = entity.strategyVersion, quoteMint = MintAddress.of(entity.quoteMint), dipWindowMs = entity.dipWindowMs,
        maxHoldMs = entity.maxHoldMs, hardExitStartMs = entity.hardExitStartMs, priceMeasure = entity.priceMeasure,
        followWalletSells = entity.followWalletSells, minSignalSol = entity.minSignalSol,
    )

    /** The plan of [variations], in the order given. */
    fun toPlan(planId: String, variations: List<AutomationVariationEntity>): SweepPlan =
        SweepPlan(planId, variations.map { SweepEntry(it.label, toConfig(it)) })

    /** A new row for [variation]; [requestId] is the edit request that created it, null for a seeded one. */
    fun toEntity(variation: Variation, requestId: String? = null): AutomationVariationEntity {
        val config = variation.config
        return AutomationVariationEntity(
            id = AutomationIds.variation(variation.planId, variation.label), planId = variation.planId, label = variation.label,
            seq = variation.seq, state = variation.state, requestId = requestId, replacesLabel = variation.replacesLabel,
            replacedByLabel = variation.replacedByLabel, activeFrom = variation.activeFrom, archivedAt = variation.archivedAt,
            createdAt = variation.createdAt, strategyName = config.strategyName, strategyVersion = config.strategyVersion,
            notional = config.notional, minMarketCapUsd = config.minMarketCapUsd, transactionCostSol = config.transactionCostSol,
            minSignalSol = requireNotNull(config.minSignalSol) { "A variation needs a minimum signal size label=${variation.label}" },
            maxAgeSlots = config.maxAgeSlots, horizonSlots = config.horizonSlots, latencySlots = config.latencySlots,
            dipBps = config.dipBps, takeProfitBps = config.takeProfitBps, stopLossBps = config.stopLossBps,
            quoteMint = config.quoteMint.value, dipWindowMs = config.dipWindowMs, maxHoldMs = config.maxHoldMs,
            hardExitStartMs = config.hardExitStartMs, priceMeasure = config.priceMeasure, followWalletSells = config.followWalletSells,
        )
    }
}
