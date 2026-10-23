package com.trenching.bot.replay.domain

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.marketdata.MarketCutoffMode
import java.nio.file.Path
import java.time.Duration

data class ReplayMarketCaptureRequest(
    val walletCaptureDirectory: Path,
    val directory: Path,
    val scanPages: Int,
    val maxTransactions: Int,
    val requestBudget: Int,
    val byteBudget: Long,
    /** This invocation's deadline, or null for the transport default. */
    val operationTimeout: Duration? = null,
    /**
     * [MarketCutoffMode.DECIDED] stops each window once its dip-entry outcome under `SimulationConfig.agreed()` is decided and
     * the recording reaches [MINIMUM_DECIDED_RECORDING] after the buy.
     */
    val cutoffMode: MarketCutoffMode = MarketCutoffMode.FULL,
    /** What the market and cutoff-rate requests are counted under in API usage. */
    val usage: ApiUsageContext = ApiUsageContext.UNATTRIBUTED,

) {
    companion object {
        /** Spec Section 11 `recordingWindowMs`: the market is recorded for four minutes from t0, even when the signal is skipped. */
        val MINIMUM_DECIDED_RECORDING: Duration = Duration.ofMillis(240_000)
    }
}
