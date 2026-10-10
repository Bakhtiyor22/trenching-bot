package com.trenching.bot.console.report

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.domain.StoredReportFacts
import com.trenching.bot.console.report.dto.StoredRunReport
import com.trenching.bot.strategy.domain.SimulationConfig
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Binds the report straight to typed fields instead of a tree: a tree would hold the notional as a
 * double, while the `BigDecimal` binding keeps its exact digits and scale.
 */
@Component
class RunReportReader(private val json: ObjectMapper) {
    fun read(report: String): StoredReportFacts {
        val stored = json.readValue(report, StoredRunReport::class.java)
        val configuration = stored.configuration
        val config = SimulationConfig.of(configuration.notional, configuration.maxAgeSlots, configuration.dipBps,
            configuration.takeProfitBps, configuration.stopLossBps, configuration.minMarketCapUsd, configuration.horizonSlots,
            configuration.latencySlots, configuration.transactionCostSol, configuration.strategyName,
            configuration.strategyVersion, MintAddress.of(configuration.quoteMint.value))
        return StoredReportFacts(config)
    }
}
