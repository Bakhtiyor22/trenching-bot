package com.trenching.bot.replay.api

import com.trenching.bot.TrenchingBotApplication
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.strategy.domain.SimulationConfig
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.ObjectMapper

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: persistedReplay <dataset.json> <report.json>" }
    SpringApplicationBuilder(TrenchingBotApplication::class.java).web(WebApplicationType.NONE).run().use { context ->
        val json = context.getBean(ObjectMapper::class.java)
        val input = Path.of(args[0])
        val record = Files.newInputStream(input).use {
            context.getBean(PersistedReplayService::class.java).execute(input.fileName.toString(), it, SimulationConfig.mvp())
        }
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(record.reportSnapshot)))
        println("runId=" + record.id + " resultChecksum=" + record.resultChecksum + " createdAt=" + record.createdAt)
        println("report=" + output)
    }
}
