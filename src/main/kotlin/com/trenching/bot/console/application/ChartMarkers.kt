package com.trenching.bot.console.application

import com.trenching.bot.console.domain.MarkerClusterView
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.MarkerMode
import com.trenching.bot.console.domain.MarkersUnavailableReason
import com.trenching.bot.console.domain.MarkersView
import com.trenching.bot.console.domain.ObservedMarkerView
import com.trenching.bot.marketdata.domain.ObservationSignature
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide

/** Chooses which observed trades the response lists and which it only counts. */
object ChartMarkers {
    /** Chart order of items: block time, then slot, then id. */
    val ITEM_ORDER: Comparator<ObservedMarkerView> = compareBy({ it.time }, { it.slot }, { it.observationId })

    fun notRecorded(): MarkersView = MarkersView(false, MarkersUnavailableReason.NOT_RECORDED_BEFORE_SCHEMA_V4, MarkerMode.ITEMS, 0,
        emptyList(), null, emptyList())

    /**
     * [points] are ordered; those without a trade are skipped. Trades of [wallets] and of the [trackedWallet] match, or every
     * trade when [wallets] is empty. Up to [limit] matching trades are items; more are clustered per bucket, leaving the
     * tracked wallet's trades as items, paged from [after]. Clusters come with the first page only.
     */
    fun select(
        points: List<ChartPoint>,
        trackedWallet: String?,
        wallets: Set<String>,
        limit: Int,
        after: ChartPosition?,
        bucketSeconds: Int,
        clock: SlotClock,
    ): MarkersView {
        val matching = points.mapNotNull { point -> point.observation.trade?.let { Traded(point, it) } }
            .filter { it.trade.trader == trackedWallet || wallets.isEmpty() || it.trade.trader in wallets }
        val clustered = matching.size > limit
        val (tracked, others) = matching.partition { it.trade.trader == trackedWallet }
        val listed = if (clustered) tracked else matching
        val remaining = if (after == null) listed else listed.filter { ChartPosition.of(it.point.observation) > after }
        val page = remaining.take(limit)
        return MarkersView(
            available = true,
            unavailableReason = null,
            mode = if (clustered) MarkerMode.CLUSTERED else MarkerMode.ITEMS,
            total = matching.size,
            items = page.map { it.toItem(trackedWallet, clock) },
            nextCursor = if (remaining.size > page.size) page.lastOrNull()?.let { ChartPosition.of(it.point.observation).encode() } else null,
            clusters = if (clustered && after == null) clusters(others, bucketSeconds) else emptyList(),
        )
    }

    /** [markers] with [unobserved] tracked trades added to the items, in chart order, and to the total; for a first page only. */
    fun including(markers: MarkersView, unobserved: List<ObservedMarkerView>): MarkersView {
        if (unobserved.isEmpty()) return markers
        return markers.copy(total = markers.total + unobserved.size, items = (markers.items + unobserved).sortedWith(ITEM_ORDER))
    }

    private fun clusters(trades: List<Traded>, bucketSeconds: Int): List<MarkerClusterView> =
        trades.groupBy { ChartBuckets.bucketStart(it.point.observation.observedTime, bucketSeconds) }.map { (start, bucket) ->
            val first = bucket.first().point.observation
            val last = bucket.last().point.observation
            MarkerClusterView(
                bucketStart = start,
                count = bucket.size,
                buyCount = bucket.count { it.trade.side == TradeSide.BUY },
                sellCount = bucket.count { it.trade.side == TradeSide.SELL },
                firstSlot = first.slot.value,
                lastSlot = last.slot.value,
                firstTime = first.observedTime,
                lastTime = last.observedTime,
            )
        }

    private data class Traded(val point: ChartPoint, val trade: ObservedTrade) {
        fun toItem(trackedWallet: String?, clock: SlotClock): ObservedMarkerView {
            val observation = point.observation
            return ObservedMarkerView(MarkerKind.OBSERVED, observation.id, trade.trader, trade.trader == trackedWallet, trade.side,
                trade.solLamports, trade.tokenRaw, observation.price, point.mcap, observation.slot.value, observation.observedTime,
                clock.estimate(observation.slot.value, observation.observedTime), ObservationSignature.of(observation), observation.source)
        }
    }
}
