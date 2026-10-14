package com.trenching.bot.marketdata

import java.nio.file.Path

interface RecordedMarketHistory {
    fun inspect(directory: Path): MarketHistoryCaptureResult

    fun decode(directory: Path): MarketHistoryDecodeResult

    /** Lists manifest progress without waiting for, or decoding, sibling windows. */
    fun inspectCases(directory: Path): MarketCaseInventory =
        throw UnsupportedOperationException("Case inspection is not available in this market history source")

    /** Verifies and decodes only this case's selected transaction artifacts. */
    fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult =
        throw UnsupportedOperationException("Case decoding is not available in this market history source")
}
