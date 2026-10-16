package com.trenching.bot.console.application

import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.strategy.domain.TradeIntent
import java.time.Instant

/** Dates a scenario's milestones by the block times of the records they point at; a missing record leaves its milestone null. */
object ScenarioTimelines {
    fun of(intent: TradeIntent, outcome: FillOutcome, position: Position?, buyTimes: Map<String, Instant?>, observations: Map<String, MarketObservation>): ScenarioTimeline {
        fun at(observationId: String?): Instant? = observationId?.let { observations[it]?.observedTime }
        return ScenarioTimeline(
            firstBuyAt = buyTimes[intent.sourceEventId],
            dipAt = at(intent.trigger?.observationId),
            entryAt = at((outcome as? FillOutcome.Filled)?.fill?.observationId),
            exitTriggerAt = at(position?.exitTrigger?.observationId),
            exitAt = at(position?.close?.observationId),
        )
    }
}
