package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val service = RecordedReplayExportService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        marketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper),
        mapper = mapper,
    )
    val status = RecordedReplayCommands(service::export, System.out, System.err).export(args)
    if (status != 0) exitProcess(status)
}
