package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.walletactivity.PumpFunBuyFacts

/**
 * A decoded market capture is usable with a wallet capture only when it holds exactly the planned windows, each complete
 * and free of detail gaps and in-window exclusions, so a missing trade can never read as an absence of trades.
 */
object RecordedMarketVerification {
    /** Events no replay window needs. Every other exclusion is a trade a window lost, which could move a fill. */
    private val NON_BLOCKING_EXCLUSIONS = setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)

    /**
     * A latency prediction needs every slot of its mint's window up to the slot before its buy. Calibration is refused only
     * when a window was cut off before reaching that slot; a buy after its window's end, or of a mint without a window, has
     * no observed prior state whatever the cutoff, so it never blocks the other buys.
     */
    fun coversCalibration(buys: List<PumpFunBuyFacts>, market: MarketHistoryDecodeResult): Boolean {
        val windows = market.windows.associateBy { it.window.baseMint }
        return buys.none { buy ->
            val window = windows[buy.mint] ?: return@none false
            buy.slot.value - 1 in window.observedThrough.value + 1..window.window.toSlot.value
        }
    }

    /** The last slot each mint's window observed: a calibrated buy's prior state must come from no later. */
    fun observedThrough(market: MarketHistoryDecodeResult): Map<MintAddress, Slot> =
        market.windows.associate { it.window.baseMint to it.observedThrough }

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
