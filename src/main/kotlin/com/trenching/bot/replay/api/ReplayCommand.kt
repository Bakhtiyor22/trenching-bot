package com.trenching.bot.replay.api

import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.MarketCoverageEndedException
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import tools.jackson.databind.json.JsonMapper

fun main(args: Array<String>) = ReplayCommand.execute(args, System.out)

/** Replays a dataset file in memory; a scenario decided beyond the dataset's coverage refuses the run before any report is written. */
internal object ReplayCommand {
    fun execute(args: Array<String>, output: PrintStream) {
        require(args.size == 2) { "Usage: replay <dataset.json> <report.json>" }
        val mapper = JsonMapper.builder().build()
        val dataset = Files.newInputStream(Path.of(args[0])).use { ReplayDatasetLoader(mapper).load(it) }
        val report = ReplayRunner().run(dataset, SimulationConfig.mvp())
        DatasetCoverage.undecided(report, dataset).firstOrNull()?.let { throw MarketCoverageEndedException(it) }
        val reportFile = Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(reportFile.parent)
        Files.writeString(reportFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        output.println("runId=" + report.runId + " resultChecksum=" + report.resultChecksum)
        report.scenarios.forEach { scenario ->
            output.println("offset=" + scenario.intent.entryOffset + " targetSlot=" + scenario.intent.targetSlot.value +
                " state=" + scenario.position?.state + " pnl=" + scenario.position?.close?.realizedPnl)
        }
        output.println("report=" + reportFile)
    }
}
