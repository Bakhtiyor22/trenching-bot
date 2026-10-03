package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.replay.mapper.SimulationRunMapper
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SimulationRunsService(
    private val catalog: ReplayDatasetCatalog,
    private val ledger: SimulationRunLedger,
    private val runs: SimulationRunTransaction,
    private val mapper: SimulationRunMapper,
) : SimulationRuns {
    private val log = LoggerFactory.getLogger(SimulationRunsService::class.java)

    override fun start(datasetId: String, config: SimulationConfig): RunStart {
        val dataset = catalog.load(datasetId)
        val report = ReplayRunner().run(dataset, config)
        val persisted = ledger.persistWithOutcome(dataset, report)
        val created = persisted.created
        val run = mapper.toSummary(persisted.record)
        log.info("simulation run started runId={} datasetId={} created={}", run.id, datasetId, created)
        return RunStart(run, created)
    }

    override fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> =
        runs.page(datasetId, ReplayPaging.request(page, size))

    override fun find(runId: String): RunSummary? = runs.summary(runId)

    override fun report(runId: String): String = runs.report(runId) ?: throw SimulationRunNotFoundException(runId)

    override fun totals(createdSince: Instant): RunAggregates = runs.aggregates(createdSince)
}
