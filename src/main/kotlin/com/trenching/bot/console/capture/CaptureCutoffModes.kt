package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureMarketSettings
import com.trenching.bot.marketdata.MarketCutoffMode

/**
 * How a market attempt or operation bounds each window. `DECIDED` stops a window once its outcome under
 * `SimulationConfig.mvp()` is decided, which prices triggers with the reference rate source, so it is refused up front
 * when that source is disabled.
 */
object CaptureCutoffModes {
    const val FIELD = "cutoffMode"
    private const val RATES_NOT_CONFIGURED = "RATES_NOT_CONFIGURED"

    /** Without an explicit mode only a wallet-token attempt decides early, and only while reference rates are enabled. */
    fun forAttempt(requested: String?, scope: CaptureWalletScope?, ratesConfigured: Boolean): MarketCutoffMode {
        val mode = requested?.let(::parse)
            ?: if (scope == CaptureWalletScope.WALLET_TOKEN && ratesConfigured) MarketCutoffMode.DECIDED else MarketCutoffMode.FULL
        return mode.also { requireRates(it, ratesConfigured) }
    }

    /** The request's mode overrides the attempt's; `FULL` on a decided window captures the rest of that window. */
    fun forOperation(requested: String?, attempt: CaptureMarketSettings, ratesConfigured: Boolean): MarketCutoffMode =
        (requested?.let(::parse) ?: saved(attempt.cutoffMode)).also { requireRates(it, ratesConfigured) }

    /** Attempts and operations saved before cutoff modes existed captured in full. */
    fun saved(value: String?): MarketCutoffMode = value?.let(::parse) ?: MarketCutoffMode.FULL

    private fun parse(value: String): MarketCutoffMode =
        MarketCutoffMode.entries.firstOrNull { it.name == value } ?: CaptureValidation.invalid(FIELD, "must be FULL or DECIDED")

    private fun requireRates(mode: MarketCutoffMode, ratesConfigured: Boolean) {
        when (mode) {
            MarketCutoffMode.FULL -> Unit
            MarketCutoffMode.DECIDED -> if (!ratesConfigured) {
                CaptureValidation.conflict(RATES_NOT_CONFIGURED, "Deciding market windows early needs reference rate capture; enable it or capture in FULL mode")
            }
        }
    }
}
