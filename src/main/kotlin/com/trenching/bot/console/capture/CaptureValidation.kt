package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.common.WalletAddress

object CaptureValidation {
    val bounds = mapOf("walletLimitMax" to 100L, "walletPagesMax" to 50L, "walletRequestsMax" to 1000L, "walletBytesMax" to 524288000L, "marketPagesMax" to 500L, "marketTransactionsMax" to 50000L, "marketRequestsMax" to 60000L, "marketBytesMax" to 4294967296L, "operationTimeoutMinutesMax" to SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES)
    fun wallet(value: CaptureWalletSettings) {
        try { WalletAddress.of(value.wallet) } catch (_: IllegalArgumentException) { invalid("wallet", "must be a base58 public wallet address") }
        if (value.scope !in setOf("pumpfun-user-buys", "wallet-references")) invalid("scope", "unsupported discovery scope")
        range("limit", value.limit.toLong(), 100)
        range("scanPages", value.scanPages.toLong(), 50)
    }
    fun market(value: CaptureMarketSettings) { range("scanPages", value.scanPages.toLong(), 500); range("maxTransactions", value.maxTransactions.toLong(), 50000) }
    fun budget(step: String, value: CaptureBudget) {
        val wallet = step == CaptureNames.WALLET
        range("requestBudget", value.requestBudget.toLong(), if (wallet) 1000 else 60000)
        range("byteBudget", value.byteBudget, if (wallet) 524288000 else 4294967296)
        range("operationTimeoutMinutes", value.operationTimeoutMinutes.toLong(), SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES)
    }
    fun range(field: String, value: Long, max: Long) { if (value < 1 || value > max) invalid(field, "must be between 1 and $max") }
    fun invalid(field: String, reason: String): Nothing = throw CaptureRequestException(400, "INVALID_REQUEST", "Invalid capture settings", mapOf(field to reason))
    fun conflict(code: String, message: String): Nothing = throw CaptureRequestException(409, code, message)
}
