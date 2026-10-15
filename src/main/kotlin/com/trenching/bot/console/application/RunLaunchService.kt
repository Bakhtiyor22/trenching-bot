package com.trenching.bot.console.application

import com.trenching.bot.console.api.dto.RunStartRequest
import com.trenching.bot.console.domain.ConfigDefaults
import com.trenching.bot.console.domain.ConfigInvalidException
import com.trenching.bot.console.domain.ConfigValidation
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.MarketCoverageEndedException
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.strategy.domain.SimulationConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Starts persisted runs from the console; a run is synchronous and idempotent by run id. */
@Service
class RunLaunchService(
    private val runs: SimulationRuns,
    private val catalog: ReplayDatasetCatalog,
    private val validator: ConsoleConfigValidator,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(RunLaunchService::class.java)

    fun defaults(): ConfigDefaults = ConfigDefaults(SimulationConfig.mvp(), SimulationConfigLimits.CURRENT)

    fun start(request: RunStartRequest): RunStart {
        val datasetId = request.datasetId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidConsoleRequestException(DATASET_ID, "is required")
        val config = when (val validation = validator.validate(request.config)) {
            is ConfigValidation.Valid -> validation.config
            is ConfigValidation.Invalid -> {
                log.warn("console run rejected datasetId={} invalidFields={}", datasetId, validation.fields.keys)
                throw ConfigInvalidException(validation.fields)
            }
        }
        catalog.find(datasetId) ?: throw DatasetNotFoundException(datasetId)
        val started = timed { run(datasetId, config) }
        meterRegistry.counter(ConsoleMetrics.RUNS, ConsoleMetrics.OUTCOME,
            if (started.created) ConsoleMetrics.CREATED else ConsoleMetrics.EXISTING).increment()
        return started
    }

    private fun run(datasetId: String, config: SimulationConfig): RunStart = try {
        runs.start(datasetId, config)
    } catch (exception: MarketCoverageEndedException) {
        meterRegistry.counter(ConsoleMetrics.RUNS, ConsoleMetrics.OUTCOME, ConsoleMetrics.COVERAGE_ENDED).increment()
        val shortfall = exception.shortfall
        log.warn("console run refused datasetId={} mint={} decidedAt={} throughSlot={} reason=MARKET_COVERAGE_ENDED",
            datasetId, shortfall.pair.base.value, shortfall.decidedAt.value, shortfall.throughSlot?.value)
        throw exception
    } catch (exception: RuntimeException) {
        meterRegistry.counter(ConsoleMetrics.RUNS, ConsoleMetrics.OUTCOME, ConsoleMetrics.FAILED).increment()
        log.error("console run failed datasetId={} strategyVersion={}", datasetId, config.strategyVersion, exception)
        throw exception
    }

    private fun <T> timed(call: () -> T): T {
        val sample = Timer.start(meterRegistry)
        try {
            return call()
        } finally {
            sample.stop(meterRegistry.timer(ConsoleMetrics.RUN_DURATION))
        }
    }

    private companion object {
        const val DATASET_ID = "datasetId"
    }
}
