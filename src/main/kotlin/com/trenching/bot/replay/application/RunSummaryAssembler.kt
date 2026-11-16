package com.trenching.bot.replay.application

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunSummaryRow
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import com.trenching.bot.replay.mapper.SimulationRunMapper
import org.springframework.stereotype.Component

/** Adds each run's dataset name and mints to its stored row with one query for each, whatever the number of rows. */
@Component
class RunSummaryAssembler(
    private val mapper: SimulationRunMapper,
    private val datasets: ReplayDatasetRepository,
    private val tokens: SimulationRunTokenRepository,
) {
    fun assemble(rows: List<SimulationRunSummaryRow>): List<RunSummary> {
        if (rows.isEmpty()) return emptyList()
        val names = datasets.findNamesByIds(rows.map { it.datasetChecksum }.toSet()).associate { it.id to it.name }
        val mintsByRun = tokens.findAllByRunIds(rows.map { it.id }).groupBy({ it.runId }, { it.mint })
        return rows.map { row ->
            mapper.toSummary(row).copy(
                datasetName = names[row.datasetChecksum],
                mints = mintsByRun[row.id].orEmpty().sorted().map(MintAddress::of),
            )
        }
    }
}
