package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowRecord

internal object MarketWindowRecords {
    fun pending(window: MarketCaptureWindow): MarketWindowRecord = MarketWindowRecord(
        baseMint = window.baseMint.value, quoteMint = window.pair.quote.value, fromSlot = window.fromSlot.value,
        toSlot = window.toSlot.value, untilSignature = window.untilSignature.value, coverage = MarketWindowCoverage.PENDING,
        pagesScanned = 0, scanned = 0, skippedFailed = 0, skippedNewer = 0, selected = 0, selection = emptyList(), scanCursor = null,
    )

    /** Throws [IllegalArgumentException] when the record does not hold a valid wrapped-SOL window. */
    fun window(record: MarketWindowRecord): MarketCaptureWindow {
        require(record.quoteMint == MintAddress.WRAPPED_SOL.value) { "Window quote must be wrapped SOL" }
        return MarketCaptureWindow.of(MintAddress.of(record.baseMint), Slot.of(record.fromSlot), Slot.of(record.toSlot),
            TransactionSignature.of(record.untilSignature))
    }
}
