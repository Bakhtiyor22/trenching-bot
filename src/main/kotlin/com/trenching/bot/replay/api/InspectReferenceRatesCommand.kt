package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.ReferenceRateCaptures
import java.time.Clock
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val recorded = ReferenceRateCaptures.recorded(MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper), mapper, Clock.systemUTC())
    val commands = ReferenceRateCommands(
        captureFactory = { error("inspectReferenceRates never captures") },
        inspect = recorded::inspect,
        output = System.out,
        errors = System.err,
    )
    val status = commands.inspect(args)
    if (status != 0) exitProcess(status)
}
