package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal

/**
 * A variation with what makes it readable: the configuration properties in which it differs from the agreed configuration and
 * from the variation it replaced (none when it replaced nothing), each in the order of the configuration response.
 */
data class VariationView(val variation: Variation, val differsFromAgreed: List<String>, val changedFromReplaced: List<String>) {
    companion object {
        /** [replaced] is the variation [variation] replaced, null when it replaced none or it is not known. */
        fun of(variation: Variation, replaced: Variation?): VariationView = VariationView(
            variation,
            VariationDifferences.between(variation.config, VariationDifferences.AGREED),
            replaced?.let { VariationDifferences.between(variation.config, it.config) }.orEmpty(),
        )
    }
}

/** The plan's variations as listed, by sequence number. */
data class VariationList(val planId: String, val items: List<VariationView>)

/** Which configuration properties two configurations do not share. */
object VariationDifferences {
    val AGREED: SimulationConfig = SimulationConfig.agreed()

    private val PROPERTIES: List<Pair<String, (SimulationConfig) -> Any?>> = listOf(
        "strategyName" to SimulationConfig::strategyName,
        "strategyVersion" to SimulationConfig::strategyVersion,
        "notional" to SimulationConfig::notional,
        "maxAgeSlots" to SimulationConfig::maxAgeSlots,
        "dipBps" to SimulationConfig::dipBps,
        "takeProfitBps" to SimulationConfig::takeProfitBps,
        "stopLossBps" to SimulationConfig::stopLossBps,
        "minMarketCapUsd" to SimulationConfig::minMarketCapUsd,
        "horizonSlots" to SimulationConfig::horizonSlots,
        "latencySlots" to SimulationConfig::latencySlots,
        "transactionCostSol" to SimulationConfig::transactionCostSol,
        "quoteMint" to SimulationConfig::quoteMint,
        "dipWindowMs" to SimulationConfig::dipWindowMs,
        "maxHoldMs" to SimulationConfig::maxHoldMs,
        "hardExitStartMs" to SimulationConfig::hardExitStartMs,
        "priceMeasure" to SimulationConfig::priceMeasure,
        "followWalletSells" to SimulationConfig::followWalletSells,
        "minSignalSol" to SimulationConfig::minSignalSol,
    )

    /** The property names in which [config] differs from [baseline]; decimals are compared by value, not by scale. */
    fun between(config: SimulationConfig, baseline: SimulationConfig): List<String> =
        PROPERTIES.filterNot { (_, read) -> same(read(config), read(baseline)) }.map { it.first }

    private fun same(a: Any?, b: Any?): Boolean = if (a is BigDecimal && b is BigDecimal) a.compareTo(b) == 0 else a == b
}
