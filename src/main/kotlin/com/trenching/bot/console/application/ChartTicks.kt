package com.trenching.bot.console.application

import com.trenching.bot.console.domain.TickView
import com.trenching.bot.console.domain.TicksView
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.TotalSupplyValuation
import com.trenching.bot.protocol.pumpfun.BondingCurve

/** A window's observations as single trades, for a chart zoomed in further than its one-second candles show. */
object ChartTicks {
    /** All of [points] when there are at most [limit] of them; [limit] 0 means none were asked for. */
    fun of(points: List<OrderedPoint>, trackedWallet: String?, clock: SlotClock, rates: ReferenceRates?, limit: Int): TicksView {
        val included = limit > 0 && points.size <= limit
        return TicksView(included, points.size, if (included) points.map { tick(it, trackedWallet, clock, rates) } else emptyList())
    }

    private fun tick(ordered: OrderedPoint, trackedWallet: String?, clock: SlotClock, rates: ReferenceRates?): TickView {
        val point = ordered.point
        val observation = point.observation
        val trade = observation.trade
        // Only a Pump.fun trade the valuation supports has reserves that are a price at the protocol's decimals.
        val curvePrice = if (point.valuation is PointValuation.Unsupported) null
            else BondingCurve.price(observation.curve.reserves.virtualSol, observation.curve.reserves.virtualToken)
        val curveMcap = curvePrice?.let { price -> rates?.rateAt(observation.observedTime)?.let { TotalSupplyValuation.usdt(price, it) } }
        return TickView(
            observationId = observation.id,
            slot = observation.slot.value,
            indexInSlot = ordered.indexInSlot,
            slotOrderProven = ordered.proven,
            time = observation.observedTime,
            estimatedTime = clock.estimate(observation.slot.value, observation.observedTime),
            price = observation.price,
            mcap = point.mcap,
            curvePrice = curvePrice,
            curveMcap = curveMcap,
            side = trade?.side,
            trader = trade?.trader,
            tracked = trade != null && trade.trader == trackedWallet,
            solLamports = trade?.solLamports,
            tokenRaw = trade?.tokenRaw,
        )
    }
}
