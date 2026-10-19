package com.trenching.bot.marketdata

/** Market capture codes other modules act on: a capture stop reason and the stage and case blocker it leads to. */
object MarketCaptureReasons {
    /** Stop reason of an invocation that held a window whose end the confirmed tip had not reached; it comes with the estimated wait. */
    const val HORIZON_NOT_ELAPSED = "HORIZON_NOT_ELAPSED"

    /** Market stage reason and case blocker while a window is [MarketWindowCoverage.HORIZON_NOT_ELAPSED]; a later resume finishes it. */
    const val MARKET_HORIZON_NOT_ELAPSED = "MARKET_HORIZON_NOT_ELAPSED"
}
