package com.trenching.bot.replay

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant

/** Persisted simulation runs of stored datasets. Lists are newest first; page size is capped at 100. */
interface SimulationRuns {
    /** Idempotent by run id: the same dataset and configuration return the existing run. */
    fun start(datasetId: String, config: SimulationConfig): RunStart
    fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary>
    fun find(runId: String): RunSummary?
    fun report(runId: String): String
    /** Totals over all stored runs; [createdSince] is inclusive. */
    fun totals(createdSince: Instant): RunAggregates
}
