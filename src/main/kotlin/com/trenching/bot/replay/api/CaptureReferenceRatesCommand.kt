package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.ReferenceRateCaptures
import java.time.Clock
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val markets = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
    val commands = ReferenceRateCommands(
        captureFactory = { ReferenceRateCaptures.capture(markets, mapper, Clock.systemUTC()) },
        inspect = ReferenceRateCaptures.recorded(markets, mapper, Clock.systemUTC())::inspect,
        output = System.out,
        errors = System.err,
    )
    val status = commands.capture(args)
    if (status != 0) exitProcess(status)
}
