package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan

/**
 * A decoded market capture is usable with a wallet capture only when it holds exactly the planned windows, each complete
 * and free of detail gaps and in-window exclusions, so a missing trade can never read as an absence of trades.
 */
object RecordedMarketVerification {
    /** Events no replay window needs. Every other exclusion is a trade a window lost, which could move a fill. */
    private val NON_BLOCKING_EXCLUSIONS = setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)

    fun requireMatching(plan: ReplayMarketWindowPlan, market: MarketHistoryDecodeResult) {
        if (market.windowParameters != plan.parameters || market.windows.map { it.window }.toSet() != plan.windows.toSet()) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        if (!market.complete || market.windows.any { !it.yieldsObservations }) throw CaptureValidationException("MARKET_CAPTURE_INCOMPLETE")
        if (market.exclusions.any { (reason, count) -> reason !in NON_BLOCKING_EXCLUSIONS && count > 0 }) {
            throw CaptureValidationException("MARKET_CAPTURE_EXCLUSIONS")
        }
    }
}
