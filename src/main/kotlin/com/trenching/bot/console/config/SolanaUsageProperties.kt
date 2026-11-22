package com.trenching.bot.console.config

import com.trenching.bot.chaincapture.RpcEndpointRules
import java.time.Duration

/**
 * How the RPC accounts' credits are read (`trenching.solana.usage.*`). The project ids are private, so this is not a data class and
 * [toString] shows no value; a failed check names the property and never quotes a value. The API key travels to [baseUrl] in a header, so
 * it must be https, or http to this machine only; it is stored trimmed.
 */
class SolanaUsageProperties(
    val primaryProjectId: String? = null,
    val backupProjectId: String? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    val refreshInterval: Duration = Duration.ofMinutes(15),
    val lowBalancePercent: Int = 10,
    val resumeCredits: Long = 1_000,
) {
    val baseUrl: String = baseUrl.trim()

    init {
        require(refreshInterval.isPositive) { "trenching.solana.usage.refresh-interval must be positive" }
        require(lowBalancePercent in 0..100) { "trenching.solana.usage.low-balance-percent must be between 0 and 100" }
        require(resumeCredits >= 1) { "trenching.solana.usage.resume-credits must be at least 1" }
        require(RpcEndpointRules.violation(this.baseUrl) == null) { "trenching.solana.usage.base-url must be an https address, or an http address on localhost" }
    }

    override fun toString(): String = "SolanaUsageProperties(refreshInterval=$refreshInterval, lowBalancePercent=$lowBalancePercent, resumeCredits=$resumeCredits)"

    companion object {
        const val DEFAULT_BASE_URL = "https://admin-api.helius.xyz"
    }
}
