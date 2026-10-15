package com.trenching.bot.replay.api

import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureProperties
import com.trenching.bot.replay.application.ReplayMarketCaptureService
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.time.Clock
import kotlin.system.exitProcess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

fun main(args: Array<String>) {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val cache = TransactionCacheOption.split(args)
    val commandArgs = (cache as? CommandArguments.Valid)?.value?.remaining ?: args
    val cacheDirectory = (cache as? CommandArguments.Valid)?.value?.directory
    val markets = MarketHistoryCaptureConfiguration()
    val recorded = markets.recordedMarketHistory(mapper)
    val service = ReplayMarketCaptureService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        marketCapture = {
            markets.cachedCapture(MarketHistoryCaptureProperties(endpoint = System.getenv("SOLANA_RPC_URL")), mapper, Clock.systemUTC(), cacheDirectory?.let { SharedTransactionCache(it, mapper) })
        },
    )
    val commands = ReplayMarketCommands(
        capture = service::capture,
        inspect = recorded::inspect,
        decode = recorded::decode,
        output = System.out,
        errors = System.err,
    )
    val status = commands.capture(commandArgs)
    if (status != 0) exitProcess(status)
}
