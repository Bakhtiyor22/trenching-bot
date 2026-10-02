package com.trenching.bot.papertrading

import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.strategy.domain.TradeIntent

interface PaperOrderStore {
    fun record(runId: String, intents: List<TradeIntent>, outcomes: List<FillOutcome>)
    fun outcomes(runId: String): List<FillOutcome>
    fun fills(runId: String): List<PaperFill>
}
