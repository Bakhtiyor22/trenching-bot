package com.trenching.bot.console.capture

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.domain.PercentConversion
import com.trenching.bot.console.domain.Recommendation
import com.trenching.bot.console.domain.RecommendationSetting
import com.trenching.bot.console.domain.RecommendationTarget
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Duration

/**
 * Capture-setup recommendations as a pure function of the form's wallet, scope and mint and the capture config: the same
 * inputs always give the same list in the same order.
 */
object CaptureRecommendations {
    const val SCOPE_WALLET_TOKEN = "SCOPE_WALLET_TOKEN"
    const val CUTOFF_DECIDED = "CUTOFF_DECIDED"
    const val ENABLE_RECOVERY = "ENABLE_RECOVERY"
    const val DECU_PUMPFUN_SCOPE = "DECU_PUMPFUN_SCOPE"
    const val CUTOFF_USES_AGREED_STRATEGY = "CUTOFF_USES_AGREED_STRATEGY"

    private const val SCOPE_FIELD = "scope"
    private const val RECOVERY_FIELD = "recovery"
    private const val RECOVERY_ENABLED = "ENABLED"

    fun of(wallet: WalletAddress?, scope: CaptureWalletScope?, mint: MintAddress?, ratesConfigured: Boolean, suggested: CaptureSuggestedWallets): List<Recommendation> =
        listOfNotNull(walletToken(mint), cutoffDecided().takeIf { ratesConfigured }, recovery(scope), suggestedWalletScope(wallet, scope, suggested))

    private fun walletToken(mint: MintAddress?): Recommendation {
        val defaults = CaptureDefaults.walletToken
        val facts = buildMap<String, Any> {
            put("limit", defaults.limit)
            put("scanPages", defaults.scanPages)
            put("requestBudget", CaptureDefaults.walletTokenBudget.requestBudget)
            mint?.let { put("mint", it.value) }
        }
        return capture(SCOPE_WALLET_TOKEN, RecommendationSetting(SCOPE_FIELD, CaptureWalletScope.WALLET_TOKEN.value), facts)
    }

    /**
     * `DECIDED` windows are cut by the agreed strategy's outcome, whose levels and clocks the facts name, and keep at least
     * the minimum recording after the buy. A run with other settings may need more of the window than such a capture holds.
     */
    private fun cutoffDecided(): Recommendation {
        val agreed = SimulationConfig.agreed()
        val facts = buildMap<String, Any> {
            put("dipPercent", PercentConversion.toPercent(agreed.dipBps.toLong()))
            put("takeProfitPercent", PercentConversion.toPercent(agreed.takeProfitBps.toLong()))
            put("stopLossPercent", PercentConversion.toPercent(agreed.stopLossBps.toLong()))
            agreed.dipWindowMs?.let { put("dipWindowSeconds", Duration.ofMillis(it).toSeconds()) }
            agreed.maxHoldMs?.let { put("maxHoldSeconds", Duration.ofMillis(it).toSeconds()) }
            agreed.hardExitStartMs?.let { put("hardExitStartSeconds", Duration.ofMillis(it).toSeconds()) }
            put("minimumRecordingSeconds", ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING.toSeconds())
        }
        return capture(CUTOFF_DECIDED, RecommendationSetting(CaptureCutoffModes.FIELD, MarketCutoffMode.DECIDED.name), facts,
            CUTOFF_USES_AGREED_STRATEGY)
    }

    /** The default recovery gives each of its attempts one full wallet-operation allowance of the chosen scope. */
    private fun recovery(scope: CaptureWalletScope?): Recommendation {
        val budget = CaptureDefaults.walletBudget(scope)
        val attempts = CaptureRecoveryConfig.DEFAULT_MAX_ATTEMPTS
        return capture(ENABLE_RECOVERY, RecommendationSetting(RECOVERY_FIELD, RECOVERY_ENABLED), mapOf(
            "requests" to Math.multiplyExact(budget.requestBudget, attempts),
            "bytes" to Math.multiplyExact(budget.byteBudget, attempts.toLong()),
            "attempts" to attempts,
        ))
    }

    private fun suggestedWalletScope(wallet: WalletAddress?, scope: CaptureWalletScope?, suggested: CaptureSuggestedWallets): Recommendation? {
        val label = wallet?.let { suggested.labelOf(it.value) } ?: return null
        if (scope != CaptureWalletScope.WALLET_REFERENCES) return null
        return capture(DECU_PUMPFUN_SCOPE, RecommendationSetting(SCOPE_FIELD, CaptureWalletScope.PUMPFUN_USER_BUYS.value), mapOf("label" to label))
    }

    private fun capture(id: String, setting: RecommendationSetting?, facts: Map<String, Any>, caveatCode: String? = null) =
        Recommendation(id, RecommendationTarget.CAPTURE, setting, facts, caveatCode)
}
