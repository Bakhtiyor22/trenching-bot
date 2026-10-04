package com.trenching.bot.replay.api

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import java.nio.file.Path

internal object WalletHistoryArgumentParser {
    fun capture(args: Array<String>): CommandArguments<WalletHistoryCaptureRequest> {
        val options = CaptureCommandArguments.options(args, WalletHistoryCommandOptions.CAPTURE_OPTIONS)
        if (options !is CommandArguments.Valid) return CommandArguments.Invalid
        val values = options.value
        val walletValue = values[WalletHistoryCommandOptions.WALLET] ?: return CommandArguments.Invalid
        val directory = CaptureCommandArguments.directory(values[WalletHistoryCommandOptions.DIRECTORY])
        if (directory !is CommandArguments.Valid) return CommandArguments.Invalid
        val wallet = try { WalletAddress.of(walletValue) } catch (_: IllegalArgumentException) { return CommandArguments.Invalid }
        val limit = CaptureCommandArguments.integer(values, WalletHistoryCommandOptions.LIMIT,
            WalletHistoryCommandOptions.DEFAULT_LIMIT, 1..WalletHistoryCaptureLimits.MAX_TRANSACTIONS) ?: return CommandArguments.Invalid
        val scanPages = CaptureCommandArguments.integer(values, WalletHistoryCommandOptions.SCAN_PAGES,
            WalletHistoryCommandOptions.DEFAULT_SCAN_PAGES, 1..WalletHistoryCaptureLimits.MAX_SCAN_PAGES) ?: return CommandArguments.Invalid
        val requestBudget = CaptureCommandArguments.integer(values, WalletHistoryCommandOptions.REQUEST_BUDGET,
            WalletHistoryCommandOptions.DEFAULT_REQUEST_BUDGET, 1..WalletHistoryCaptureLimits.MAX_REQUESTS) ?: return CommandArguments.Invalid
        val byteBudget = CaptureCommandArguments.long(values, WalletHistoryCommandOptions.BYTE_BUDGET,
            WalletHistoryCommandOptions.DEFAULT_BYTE_BUDGET, 1..WalletHistoryCaptureLimits.MAX_BYTES) ?: return CommandArguments.Invalid
        return CommandArguments.Valid(WalletHistoryCaptureRequest(wallet, directory.value, limit, scanPages, requestBudget, byteBudget))
    }

    fun inspect(args: Array<String>): CommandArguments<Path> =
        when (val options = CaptureCommandArguments.options(args, setOf(WalletHistoryCommandOptions.DIRECTORY))) {
            CommandArguments.Invalid -> CommandArguments.Invalid
            is CommandArguments.Valid -> CaptureCommandArguments.directory(options.value[WalletHistoryCommandOptions.DIRECTORY])
        }
}
