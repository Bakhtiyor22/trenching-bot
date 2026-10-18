package com.trenching.bot.console.application

import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.strategy.domain.TradeIntent
import java.time.Instant

/** A scenario's stage instants, read from the stored records: nothing is recomputed. */
object ScenarioTimelines {
    /** [entry] is the observation the entry fill used; [firstBuyAt] the block time of the tracked wallet's buy that started the cycle. */
    fun of(
        intent: TradeIntent,
        entry: MarketObservation?,
        position: Position?,
        firstBuyAt: Instant?,
        observationsById: Map<String, MarketObservation>,
    ): ScenarioTimeline = ScenarioTimeline(
        firstBuyAt = firstBuyAt,
        dipAt = intent.trigger?.let { observationsById[it.observationId] }?.observedTime,
        entryAt = entry?.observedTime,
        exitTriggerAt = position?.exitTrigger?.let { observationsById[it.observationId] }?.observedTime,
        exitAt = position?.close?.let { observationsById[it.observationId] }?.observedTime,
    )
}
