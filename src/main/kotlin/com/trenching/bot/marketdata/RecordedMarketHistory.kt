package com.trenching.bot.marketdata

import java.nio.file.Path

interface RecordedMarketHistory {
    fun inspect(directory: Path): MarketHistoryCaptureResult

    fun decode(directory: Path): MarketHistoryDecodeResult

    /**
     * The window parameters the saved manifest records, its lead included ([MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS]
     * for a capture from before the lead), or null when [directory] holds no manifest. Only the manifest is read and
     * verified, no artifact; a manifest that is present but invalid fails as [inspect] would.
     */
    fun windowParameters(directory: Path): MarketCaptureWindowParameters? =
        throw UnsupportedOperationException("Reading window parameters is not available in this market history source")

    /** Lists manifest progress without waiting for, or decoding, sibling windows. */
    fun inspectCases(directory: Path): MarketCaseInventory =
        throw UnsupportedOperationException("Case inspection is not available in this market history source")

    /** Verifies and decodes only this case's selected transaction artifacts. */
    fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult =
        throw UnsupportedOperationException("Case decoding is not available in this market history source")
}
