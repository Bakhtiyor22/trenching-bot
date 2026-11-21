package com.trenching.bot.replay.domain

import com.trenching.bot.common.LogLine

/** The words and context of a refusal because the market capture ends before a decision, said the same by every place that logs it. */
object CoverageEndedLines {
    /** `<subject> refused: <why><next>.`, with the shortfall's `code`, `mint`, `decidedAt` and, when the capture holds any data, `throughSlot`; the caller adds its own ids. */
    fun refusal(subject: String, shortfall: CoverageShortfall, next: String = ""): LogLine =
        LogLine("{} refused: {}{}.", subject, reason(shortfall), next)
            .field("code", VerdictReasons.MARKET_COVERAGE_ENDED)
            .field("mint", shortfall.pair.base.value)
            .field("decidedAt", shortfall.decidedAt.value)
            .field("throughSlot", shortfall.throughSlot?.value)

    private fun reason(shortfall: CoverageShortfall): String =
        if (shortfall.throughSlot == null) "the market capture holds no data for this token" else "the market capture ends before the strategy's decision"
}
