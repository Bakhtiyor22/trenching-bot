package com.trenching.bot.marketdata

/** Market capture codes other modules act on: capture stop reasons, the stage and case blockers they lead to, and planning refusals. */
object MarketCaptureReasons {
    /** Stop reason of an invocation that held a window whose end the confirmed tip had not reached; it comes with the estimated wait. */
    const val HORIZON_NOT_ELAPSED = "HORIZON_NOT_ELAPSED"

    /** Market stage reason and case blocker while a window is [MarketWindowCoverage.HORIZON_NOT_ELAPSED]; a later resume finishes it. */
    const val MARKET_HORIZON_NOT_ELAPSED = "MARKET_HORIZON_NOT_ELAPSED"

    /**
     * Market stage reason and case blocker for a window [MarketWindowChecks.mayBeListedBeforeEnd] flags: a listing that began
     * before the window ended misses its later trades for good, so only a new market attempt can capture them.
     */
    const val MARKET_LISTED_BEFORE_END = "MARKET_LISTED_BEFORE_END"

    /** Planning refusal for a first buy too early for its lead: its window would start before slot 0. */
    const val MARKET_WINDOW_INVALID = "MARKET_WINDOW_INVALID"
}
