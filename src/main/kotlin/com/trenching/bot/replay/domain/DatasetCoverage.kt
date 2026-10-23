package com.trenching.bot.replay.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.portfolio.domain.RecordedThrough
import com.trenching.bot.strategy.domain.TradeIntent

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
        val recorded = recordedThrough(dataset)
        return report.scenarios.mapNotNull { scenario ->
            val intent = scenario.intent
            val decidedAt = ScenarioDecision.decidedAt(scenario, source, report.configuration, recorded(intent))
            val throughSlot = usable(coverageByPair, intent)?.throughSlot
            if (decidedAt > (throughSlot ?: intent.creationSlot)) CoverageShortfall(intent.pair, intent.id, decidedAt, throughSlot) else null
        }
    }

    /**
     * How far [dataset] proves each cycle's market recorded, for the exits sold against the standing curve. v3 and v4 datasets
     * count as recorded through the horizon end, as [undecided] treats them, but with no time. Coverage beyond the horizon only
     * proves the horizon end, whose time it does not give; a pair without usable coverage proves nothing after its buy.
     */
    fun recordedThrough(dataset: ReplayDataset): (TradeIntent) -> RecordedThrough {
        if (!ReplayDataset.recordsCoverage(dataset.schemaVersion)) return { RecordedThrough(it.horizonEndSlot, null) }
        val coverageByPair = dataset.coverage.associateBy { it.pair }
        return { intent ->
            val coverage = usable(coverageByPair, intent)
            when {
                coverage == null -> RecordedThrough(intent.creationSlot, null)
                coverage.throughSlot > intent.horizonEndSlot -> RecordedThrough(intent.horizonEndSlot, null)
                else -> RecordedThrough(coverage.throughSlot, coverage.throughTime)
            }
        }
    }

    private fun usable(coverageByPair: Map<MarketPair, MarketCoverage>, intent: TradeIntent): MarketCoverage? =
        coverageByPair[intent.pair]?.takeIf { it.fromSlot.value - 1 <= intent.creationSlot.value }
}
