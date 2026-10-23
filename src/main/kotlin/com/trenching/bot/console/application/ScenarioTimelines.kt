package com.trenching.bot.console.application

import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.ExitTrigger
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
        exitTriggerAt = position?.exitTrigger?.let { triggerTime(it, observationsById) },
        exitAt = position?.let { held -> held.close?.let { observationsById[it.observationId] }?.let { saleTime(held, it, observationsById) } },
    )

    /**
     * When [position]'s sale against [curve] was made: a time exit's at its exit start, since the curve it sold against stood
     * from an earlier trade; a take profit or stop loss sold against the standing curve at its trigger's time, or the standing
     * trade's when that came later, since no trade followed within 60 s; any other at that trade's block time.
     */
    fun saleTime(position: Position, curve: MarketObservation, observationsById: Map<String, MarketObservation>): Instant {
        val trigger = position.exitTrigger ?: return curve.observedTime
        return when (trigger.reason) {
            CloseReason.TIME_EXIT -> trigger.time ?: curve.observedTime
            CloseReason.TAKE_PROFIT, CloseReason.STOP_LOSS -> when (position.close?.priceAssumption) {
                ExitPriceAssumption.STANDING_CURVE -> triggerTime(trigger, observationsById)?.let { maxOf(it, curve.observedTime) } ?: curve.observedTime
                ExitPriceAssumption.NEXT_TRADE, null -> curve.observedTime
            }
        }
    }

    /** A trigger stored before engine dip-paper-v4 has no time and is dated by its trade. */
    private fun triggerTime(trigger: ExitTrigger, observationsById: Map<String, MarketObservation>): Instant? =
        trigger.time ?: observationsById[trigger.observationId]?.observedTime
}
