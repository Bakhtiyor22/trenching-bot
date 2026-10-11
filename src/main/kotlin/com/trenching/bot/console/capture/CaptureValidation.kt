package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress

object CaptureValidation {
    const val WALLET_REQUESTS_MAX = 1000L
    val bounds = mapOf("walletLimitMax" to 100L, "walletPagesMax" to 50L, "walletRequestsMax" to WALLET_REQUESTS_MAX, "walletBytesMax" to 524288000L, "marketPagesMax" to 500L, "marketTransactionsMax" to 50000L, "marketRequestsMax" to 60000L, "marketBytesMax" to 4294967296L, "operationTimeoutMinutesMax" to SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES)
    fun wallet(value: CaptureWalletSettings) {
        try { WalletAddress.of(value.wallet) } catch (_: IllegalArgumentException) { invalid("wallet", "must be a base58 public wallet address") }
        when (CaptureWalletScope.of(value.scope) ?: invalid("scope", "unsupported discovery scope")) {
            CaptureWalletScope.WALLET_TOKEN -> mint(value.mint)
            CaptureWalletScope.PUMPFUN_USER_BUYS, CaptureWalletScope.WALLET_REFERENCES -> if (value.mint != null) invalid("mint", "only the wallet-token scope takes a mint")
        }
        range("limit", value.limit.toLong(), 100)
        range("scanPages", value.scanPages.toLong(), 50)
    }
    private fun mint(value: String?) {
        val mint = value ?: invalid("mint", "required for the wallet-token scope")
        try { MintAddress.of(mint) } catch (_: IllegalArgumentException) { invalid("mint", "must be a base58 32-byte mint address") }
    }
    fun market(value: CaptureMarketSettings) { range("scanPages", value.scanPages.toLong(), 500); range("maxTransactions", value.maxTransactions.toLong(), 50000) }
    fun budget(step: String, value: CaptureBudget) {
        val wallet = step == CaptureNames.WALLET
        range("requestBudget", value.requestBudget.toLong(), if (wallet) WALLET_REQUESTS_MAX else 60000)
        range("byteBudget", value.byteBudget, if (wallet) 524288000 else 4294967296)
        range("operationTimeoutMinutes", value.operationTimeoutMinutes.toLong(), SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES)
    }
    fun range(field: String, value: Long, max: Long) { if (value < 1 || value > max) invalid(field, "must be between 1 and $max") }
    fun invalid(field: String, reason: String): Nothing = throw CaptureRequestException(400, "INVALID_REQUEST", "Invalid capture settings", mapOf(field to reason))
    fun conflict(code: String, message: String): Nothing = throw CaptureRequestException(409, code, message)
}
