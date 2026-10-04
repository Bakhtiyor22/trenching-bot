package com.trenching.bot.marketdata

import java.nio.file.Path

interface RecordedMarketHistory {
    fun inspect(directory: Path): MarketHistoryCaptureResult

    fun decode(directory: Path): MarketHistoryDecodeResult
}
