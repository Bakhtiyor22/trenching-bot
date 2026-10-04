package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureLimits
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import java.nio.file.Path

internal object ReplayMarketArgumentParser {
    /** Market artifacts must not share the wallet capture's directory, whose transaction files they would overwrite. */
    fun capture(args: Array<String>): CommandArguments<ReplayMarketCaptureRequest> {
        val options = CaptureCommandArguments.options(args, ReplayMarketCommandOptions.CAPTURE_OPTIONS)
        if (options !is CommandArguments.Valid) return CommandArguments.Invalid
        val values = options.value
        val walletDirectory = CaptureCommandArguments.directory(values[ReplayMarketCommandOptions.WALLET_CAPTURE_DIRECTORY])
        val directory = CaptureCommandArguments.directory(values[ReplayMarketCommandOptions.DIRECTORY])
        if (walletDirectory !is CommandArguments.Valid || directory !is CommandArguments.Valid ||
            walletDirectory.value == directory.value) return CommandArguments.Invalid
        val scanPages = CaptureCommandArguments.integer(values, ReplayMarketCommandOptions.SCAN_PAGES,
            ReplayMarketCommandOptions.DEFAULT_SCAN_PAGES, 1..MarketHistoryCaptureLimits.MAX_SCAN_PAGES) ?: return CommandArguments.Invalid
        val maxTransactions = CaptureCommandArguments.integer(values, ReplayMarketCommandOptions.MAX_TRANSACTIONS,
            ReplayMarketCommandOptions.DEFAULT_MAX_TRANSACTIONS, 1..MarketHistoryCaptureLimits.MAX_TRANSACTIONS) ?: return CommandArguments.Invalid
        val requestBudget = CaptureCommandArguments.integer(values, ReplayMarketCommandOptions.REQUEST_BUDGET,
            ReplayMarketCommandOptions.DEFAULT_REQUEST_BUDGET, 1..MarketHistoryCaptureLimits.MAX_REQUESTS) ?: return CommandArguments.Invalid
        val byteBudget = CaptureCommandArguments.long(values, ReplayMarketCommandOptions.BYTE_BUDGET,
            ReplayMarketCommandOptions.DEFAULT_BYTE_BUDGET, 1..MarketHistoryCaptureLimits.MAX_BYTES) ?: return CommandArguments.Invalid
        return CommandArguments.Valid(ReplayMarketCaptureRequest(walletDirectory.value, directory.value, scanPages, maxTransactions,
            requestBudget, byteBudget))
    }

    fun inspect(args: Array<String>): CommandArguments<Path> =
        when (val options = CaptureCommandArguments.options(args, setOf(ReplayMarketCommandOptions.DIRECTORY))) {
            CommandArguments.Invalid -> CommandArguments.Invalid
            is CommandArguments.Valid -> CaptureCommandArguments.directory(options.value[ReplayMarketCommandOptions.DIRECTORY])
        }
}
