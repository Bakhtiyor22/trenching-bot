package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.ReferenceRateCaptures
import java.time.Clock
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val markets = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
    val service = RecordedReplayExportService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        marketHistory = markets,
        referenceRates = ReferenceRateCaptures.recorded(markets, mapper, Clock.systemUTC()),
        mapper = mapper,
    )
    val status = RecordedReplayCommands(service::export, System.out, System.err).export(args)
    if (status != 0) exitProcess(status)
}
