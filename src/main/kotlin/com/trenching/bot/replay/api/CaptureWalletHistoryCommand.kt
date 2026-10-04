package com.trenching.bot.replay.api

import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val configuration = WalletHistoryCaptureConfiguration()
    val recorded = configuration.recordedWalletHistory(mapper)
    val commands = WalletHistoryCommands(
        captureFactory = {
            configuration.walletHistoryCapture(
                WalletHistoryCaptureProperties(endpoint = System.getenv("SOLANA_RPC_URL")), mapper, Clock.systemUTC(),
            )
        },
        inspect = recorded::inspect,
        decode = recorded::decode,
        output = System.out,
        errors = System.err,
    )
    val status = commands.capture(args)
    if (status != 0) exitProcess(status)
}
