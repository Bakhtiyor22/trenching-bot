package com.trenching.bot.marketdata

/** Checks on a recorded market window that the modules judging a capture share. */
object MarketWindowChecks {
    /**
     * Whether the window's listing may have started before the chain reached its end, which misses every trade after that
     * tip. A recorded [MarketWindowRecord.listedAtTip] rules it out. Without one, a listing that started after the end walks
     * past the window's newer trades first and counts them as skipped, so a listing that skipped none probably started
     * inside it, unless the token simply stopped trading at the window's end.
     */
    fun mayBeListedBeforeEnd(window: MarketWindowRecord): Boolean =
        window.pagesScanned > 0 && window.listedAtTip == null && window.skippedNewer == 0
}
