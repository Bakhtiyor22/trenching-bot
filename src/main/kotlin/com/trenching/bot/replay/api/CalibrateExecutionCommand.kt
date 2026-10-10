package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.replay.application.ExecutionCalibrationService
import com.trenching.bot.replay.infrastructure.fixture.CalibrationReportWriter
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val service = ExecutionCalibrationService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        marketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper),
    )
    val commands = ExecutionCalibrationCommands(
        calibrate = { request -> service.calibrate(request.walletCaptureDirectory, request.marketCaptureDirectory) },
        save = CalibrationReportWriter(mapper)::save,
        output = System.out,
        errors = System.err,
    )
    val status = commands.calibrate(args)
    if (status != 0) exitProcess(status)
}
