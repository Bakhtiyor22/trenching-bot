package com.trenching.bot.console.application

import com.trenching.bot.common.MarketPair
import com.trenching.bot.console.domain.ChartExtent
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.domain.ChartRange
import com.trenching.bot.console.domain.ChartSource
import com.trenching.bot.console.domain.ChartView
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.replay.ReplayDatasetCatalog
import org.springframework.stereotype.Service

/**
 * Bounded chart views, built in memory from the observations of one run or dataset (themselves bounded by the replay input
 * limits); only the response is bounded. Rates and the tracked wallet come from the dataset, never from the network.
 */
@Service
class ChartConsoleService(
    private val consoleService: ConsoleService,
    private val catalog: ReplayDatasetCatalog,
) {
    /** The run's own stored observations, valued with its dataset's rates when the dataset is still in the catalog. */
    fun runChart(runId: String, query: ChartQuery): ChartView {
        val parameters = ChartParameters.of(query)
        val (detail, observations) = consoleService.detailWithObservations(runId)
        val dataset = catalog.find(detail.run.datasetId)?.let { catalog.load(it.id) }
        val pair = parameters.pair ?: onlyPair(observations.map { it.pair }.distinct())
        return assemble(parameters, pair, ChartSource(observations, detail.run.quoteMint, dataset, detail))
    }

    fun datasetChart(datasetId: String, query: ChartQuery): ChartView {
        val parameters = ChartParameters.of(query)
        val pair = parameters.pair ?: throw InvalidConsoleRequestException(ConsolePairs.PAIR, "is required")
        val dataset = catalog.load(datasetId)
        return assemble(parameters, pair, ChartSource(dataset.observations, pair.quote, dataset, null))
    }

    private fun onlyPair(pairs: List<MarketPair>): MarketPair? {
        if (pairs.size > 1) throw InvalidConsoleRequestException(ConsolePairs.PAIR, "is required when the run has several pairs")
        return pairs.singleOrNull()
    }

    private fun assemble(parameters: ChartParameters, pair: MarketPair?, source: ChartSource): ChartView {
        val dataset = source.dataset
        val rates = dataset?.referenceRates?.takeIf { it.isNotEmpty() }?.let(::OrderedReferenceRates)
        val pairObservations = source.observations.filter { it.pair == pair }
        val points = pairObservations.filter { parameters.contains(it.observedTime) }
            .sortedWith(ChartPosition.ORDER)
            .map { ChartPoint(it, ChartValuation.of(it, rates)) }
        val range = ChartRange(parameters.from ?: points.firstOrNull()?.observation?.observedTime,
            parameters.to ?: points.lastOrNull()?.observation?.observedTime)
        val bucketSeconds = ChartBuckets.bucketSeconds(range.from, range.to, parameters.maxBuckets)
        val trackedWallet = dataset?.trackedWallet?.value
        val markers = if (source.observations.any { it.trade != null }) {
            ChartMarkers.select(points, trackedWallet, parameters.wallets, parameters.markerLimit, parameters.cursor, bucketSeconds)
        } else {
            ChartMarkers.notRecorded()
        }
        val paperFills = source.run?.let { ChartPaperFills.of(it.scenarios, source.observations, pair, rates) }.orEmpty()
            .filter { parameters.contains(it.time) }
        return ChartView(
            bucketSeconds = bucketSeconds,
            range = range,
            extent = extent(pairObservations),
            trackedWallet = trackedWallet,
            valuationBasis = ChartValuation.basis(source.quote, dataset?.let { ChartValuation.rateSource(it.referenceRates) },
                rates != null, points.map { it.valuation }),
            candles = ChartBuckets.candles(points, bucketSeconds),
            markers = markers,
            paperFills = paperFills,
        )
    }

    private fun extent(observations: List<MarketObservation>): ChartExtent? {
        if (observations.isEmpty()) return null
        return ChartExtent(observations.minOf { it.observedTime }, observations.maxOf { it.observedTime },
            observations.minOf { it.slot.value }, observations.maxOf { it.slot.value })
    }
}
