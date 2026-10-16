package com.trenching.bot.replay.domain

import com.trenching.bot.marketdata.domain.OrderedMarketData

/** The schema v5 coverage rule and its refusal codes: a scenario decided after its pair's coverage ends has no outcome yet. */
internal object DatasetCoverage {
    const val MISSING = "COVERAGE_MISSING"
    const val INVALID = "COVERAGE_INVALID"
    const val BEFORE_V5 = "COVERAGE_BEFORE_V5"

    /**
     * The scenarios of [report] decided after the coverage of [dataset], which it was replayed from; always empty for v3 and v4.
     * A pair without coverage starting by its buy's next slot has no usable prefix, so only a scenario decided at its buy is decided.
     */
    fun undecided(report: SimulationReport, dataset: ReplayDataset): List<CoverageShortfall> {
        if (!ReplayDataset.recordsCoverage(dataset.schemaVersion)) return emptyList()
        val source = OrderedMarketData(dataset.observations)
        val coverageByPair = dataset.coverage.associateBy { it.pair }
        return report.scenarios.mapNotNull { scenario ->
            val intent = scenario.intent
            val decidedAt = ScenarioDecision.decidedAt(scenario, source, report.configuration)
            val throughSlot = coverageByPair[intent.pair]
                ?.takeIf { it.fromSlot.value - 1 <= intent.creationSlot.value }?.throughSlot
            if (decidedAt > (throughSlot ?: intent.creationSlot)) CoverageShortfall(intent.pair, intent.id, decidedAt, throughSlot) else null
        }
    }
}
