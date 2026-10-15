package com.trenching.bot.replay.api

import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val cache = TransactionCacheOption.split(args)
    val commandArgs = (cache as? CommandArguments.Valid)?.value?.remaining ?: args
    val cacheDirectory = (cache as? CommandArguments.Valid)?.value?.directory
    val configuration = WalletHistoryCaptureConfiguration()
    val recorded = configuration.recordedWalletHistory(mapper)
    val commands = WalletHistoryCommands(
        captureFactory = {
            configuration.cachedCapture(
                WalletHistoryCaptureProperties(endpoint = System.getenv("SOLANA_RPC_URL")), mapper, Clock.systemUTC(), cacheDirectory?.let { SharedTransactionCache(it, mapper) },
            )
        },
        inspect = recorded::inspect,
        decode = recorded::decode,
        output = System.out,
        errors = System.err,
    )
    val status = commands.capture(commandArgs)
    if (status != 0) exitProcess(status)
}
