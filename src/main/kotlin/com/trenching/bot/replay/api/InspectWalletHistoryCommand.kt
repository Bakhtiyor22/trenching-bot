package com.trenching.bot.replay.api

import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
    val commands = WalletHistoryCommands(
        captureFactory = { error("Offline inspection cannot create a provider adapter") },
        inspect = recorded::inspect,
        decode = recorded::decode,
        output = System.out,
        errors = System.err,
    )
    val status = commands.inspect(args)
    if (status != 0) exitProcess(status)
}
