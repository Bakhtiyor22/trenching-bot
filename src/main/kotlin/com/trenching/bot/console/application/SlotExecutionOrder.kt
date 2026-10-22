package com.trenching.bot.console.application

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import java.math.BigInteger

/** A chart point with its place among its slot's trades, and whether that place is proven by the curve or only the id order. */
data class OrderedPoint(val point: ChartPoint, val indexInSlot: Int, val proven: Boolean)

/**
 * The order the trades of one slot executed in. Block time and slot cannot tell them apart and an id order is arbitrary, but a
 * bonding-curve trade's reserves before it are exactly the previous trade's reserves after it. A slot whose trades form one such
 * chain is put in that order; any other slot keeps the order it was given.
 */
object SlotExecutionOrder {
    /** [points] in [ChartPosition.ORDER], so a slot's points are adjacent and slots stay in order. */
    fun order(points: List<ChartPoint>): List<OrderedPoint> =
        points.groupBy { it.observation.slot.value }.values.flatMap { slot ->
            val chained = chain(slot)
            (chained ?: slot).mapIndexed { index, point -> OrderedPoint(point, index, chained != null) }
        }

    private fun chain(points: List<ChartPoint>): List<ChartPoint>? {
        if (points.size == 1) return points
        val links = points.map { point -> link(point) ?: return null }
        val byBefore = links.associateBy { it.before }
        if (byBefore.size != links.size) return null
        val afters = links.mapTo(HashSet()) { it.after }
        val first = links.singleOrNull { it.before !in afters } ?: return null
        // A cycle never ends, so one link more than there are shows it.
        val ordered = generateSequence(first) { byBefore[it.after] }.take(links.size + 1).toList()
        return if (ordered.size == links.size) ordered.map { it.point } else null
    }

    private fun link(point: ChartPoint): Link? {
        val observation = point.observation
        val trade = observation.trade ?: return null
        val after = reserves(observation)
        val sign = if (trade.side == TradeSide.BUY) BigInteger.ONE else BigInteger.ONE.negate()
        val before = Reserves(after.sol - sign * trade.solLamports, after.token + sign * trade.tokenRaw)
        return Link(point, before, after)
    }

    private fun reserves(observation: MarketObservation) =
        Reserves(observation.curve.reserves.virtualSol, observation.curve.reserves.virtualToken)

    private data class Reserves(val sol: BigInteger, val token: BigInteger)

    private data class Link(val point: ChartPoint, val before: Reserves, val after: Reserves)
}
