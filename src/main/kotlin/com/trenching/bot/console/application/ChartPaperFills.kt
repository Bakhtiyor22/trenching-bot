package com.trenching.bot.console.application

import com.trenching.bot.common.MarketPair
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.PaperFillView
import com.trenching.bot.console.domain.PaperPhase
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.papertrading.domain.FillOutcome
import java.math.BigDecimal

/** A run's stored fills and closes as chart markers. Nothing is recomputed: prices and quantities are the stored ones. */
object ChartPaperFills {
    private val ORDER: Comparator<PaperFillView> = compareBy({ it.time }, { it.slot }, { it.intentId }, { it.phase })

    fun of(
        scenarios: List<RunScenario>,
        observations: List<MarketObservation>,
        pair: MarketPair?,
        rates: ReferenceRates?,
        clock: SlotClock,
    ): List<PaperFillView> {
        val byId = observations.associateBy { it.id }
        return scenarios.filter { it.intent.pair == pair }.flatMap { scenario ->
            val fill = (scenario.outcome as? FillOutcome.Filled)?.fill
            if (fill == null) return@flatMap emptyList()
            val intentId = scenario.intent.id
            val entry = checkNotNull(scenario.selectedObservation) { "Missing fill observation intentId=$intentId" }
            val close = scenario.position?.close
            listOfNotNull(
                view(PaperPhase.ENTRY, TradeSide.BUY, intentId, entry, fill.actualSlot.value, fill.executionPrice, fill.quantity, null, rates,
                    clock),
                close?.let {
                    val exit = checkNotNull(byId[it.observationId]) { "Missing close observation intentId=$intentId id=${it.observationId}" }
                    view(PaperPhase.EXIT, TradeSide.SELL, intentId, exit, it.slot.value, it.executionPrice, fill.quantity, it.reason.name, rates,
                        clock)
                },
            )
        }.sortedWith(ORDER)
    }

    private fun view(
        phase: PaperPhase,
        side: TradeSide,
        intentId: String,
        observation: MarketObservation,
        slot: Long,
        price: BigDecimal,
        quantity: BigDecimal,
        reason: String?,
        rates: ReferenceRates?,
        clock: SlotClock,
    ) = PaperFillView(MarkerKind.PAPER, phase, side, intentId, slot, observation.observedTime,
        clock.estimate(slot, observation.observedTime), price, ChartValuation.of(observation, rates).mcap, quantity, reason)
}
