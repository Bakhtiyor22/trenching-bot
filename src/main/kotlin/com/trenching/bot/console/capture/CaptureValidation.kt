package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

object CaptureValidation {
    const val WALLET_REQUESTS_MAX = 1000L
    val bounds = mapOf("walletLimitMax" to WalletHistoryCaptureLimits.MAX_TRANSACTIONS.toLong(), "walletPagesMax" to 50L, "walletRequestsMax" to WALLET_REQUESTS_MAX, "walletBytesMax" to 524288000L, "marketPagesMax" to 500L, "marketTransactionsMax" to 50000L, "marketRequestsMax" to 60000L, "marketBytesMax" to 4294967296L, "operationTimeoutMinutesMax" to SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES, "recoveryRequestsMax" to 60000L, "recoveryBytesMax" to 4294967296L, "recoveryAttemptsMax" to 8L)
    private const val NAME_FIELD = "name"
    fun wallet(value: CaptureWalletSettings) {
        walletAddress(value.wallet)
        when (scope(value.scope)) {
            CaptureWalletScope.WALLET_TOKEN -> mintAddress(value.mint ?: invalid("mint", "required for the wallet-token scope"))
            CaptureWalletScope.PUMPFUN_USER_BUYS, CaptureWalletScope.WALLET_REFERENCES -> if (value.mint != null) invalid("mint", "only the wallet-token scope takes a mint")
        }
        range("limit", value.limit.toLong(), WalletHistoryCaptureLimits.MAX_TRANSACTIONS.toLong())
        range("scanPages", value.scanPages.toLong(), 50)
    }
    fun walletAddress(value: String): WalletAddress = try { WalletAddress.of(value) } catch (_: IllegalArgumentException) { invalid("wallet", "must be a base58 public wallet address") }
    fun mintAddress(value: String): MintAddress = try { MintAddress.of(value) } catch (_: IllegalArgumentException) { invalid("mint", "must be a base58 32-byte mint address") }
    fun scope(value: String): CaptureWalletScope = CaptureWalletScope.of(value) ?: invalid("scope", "unsupported discovery scope")
    /** The name as stored: trimmed, whitespace collapsed, 1 to [CaptureNamePolicy.MAX_LENGTH] characters. */
    fun name(value: String): String = CaptureNamePolicy.normalize(value).takeIf { it.length in 1..CaptureNamePolicy.MAX_LENGTH }
        ?: invalid(NAME_FIELD, "must contain 1 to ${CaptureNamePolicy.MAX_LENGTH} characters")
    fun timeZone(value: String?): ZoneId = value?.let {
        try { ZoneId.of(it) } catch (_: DateTimeException) { invalid("timeZone", "must be an IANA time zone such as Europe/Berlin") }
    } ?: ZoneOffset.UTC
    fun nameTaken(suggestion: String): Nothing = throw CaptureRequestException(409, "NAME_TAKEN", "Capture name is already taken", mapOf(NAME_FIELD to "is already taken"), suggestion)
    fun market(value: CaptureMarketSettings) { range("scanPages", value.scanPages.toLong(), 500); range("maxTransactions", value.maxTransactions.toLong(), 50000) }
    fun budget(step: String, value: CaptureBudget) {
        val wallet = step == CaptureNames.WALLET
        range("requestBudget", value.requestBudget.toLong(), if (wallet) WALLET_REQUESTS_MAX else 60000)
        range("byteBudget", value.byteBudget, if (wallet) 524288000 else 4294967296)
        range("operationTimeoutMinutes", value.operationTimeoutMinutes.toLong(), SolanaRpcSettings.MAX_OPERATION_TIMEOUT_MINUTES)
    }
    fun recovery(value: CaptureRecoveryConfig, step: String) {
        range("totalRequestBudget", value.totalRequestBudget.toLong(), 60000)
        range("totalByteBudget", value.totalByteBudget, 4294967296)
        if (value.maxAttempts !in 1..8) invalid("maxAttempts", "must be between 1 and 8")
        if (step !in setOf(CaptureNames.WALLET, CaptureNames.MARKET)) invalid("recovery", "automatic recovery applies only to wallet or market acquisition")
    }
    fun range(field: String, value: Long, max: Long) { if (value < 1 || value > max) invalid(field, "must be between 1 and $max") }
    fun invalid(field: String, reason: String): Nothing = throw CaptureRequestException(400, "INVALID_REQUEST", "Invalid capture settings", mapOf(field to reason))
    fun conflict(code: String, message: String): Nothing = throw CaptureRequestException(409, code, message)
}
