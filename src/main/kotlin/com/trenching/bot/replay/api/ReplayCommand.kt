package com.trenching.bot.replay.api

import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import java.nio.file.Files
import java.nio.file.Path
import tools.jackson.databind.json.JsonMapper

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: replay <dataset.json> <report.json>" }
    val mapper = JsonMapper.builder().build()
    val dataset = Files.newInputStream(Path.of(args[0])).use { ReplayDatasetLoader(mapper).load(it) }
    val report = ReplayRunner().run(dataset, SimulationConfig.mvp())
    val output = Path.of(args[1]).toAbsolutePath().normalize()
    Files.createDirectories(output.parent)
    Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    println("runId=" + report.runId + " resultChecksum=" + report.resultChecksum)
    report.scenarios.forEach { scenario ->
        println("offset=" + scenario.intent.entryOffset + " targetSlot=" + scenario.intent.targetSlot.value +
            " state=" + scenario.position?.state + " pnl=" + scenario.position?.close?.realizedPnl)
    }
    println("report=" + output)
}
