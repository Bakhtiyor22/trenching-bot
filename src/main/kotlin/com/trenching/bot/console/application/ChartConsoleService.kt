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
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import org.springframework.stereotype.Service

/**
 * Bounded chart views, built in memory from the observations of one run or dataset (themselves bounded by the replay input
 * limits); only the response is bounded. Rates and the tracked wallet come from the dataset, never from the network. The tracked
 * wallet's trades the observations do not hold, above all each cycle's first buy, are added to the markers from its accepted trades.
 */
@Service
class ChartConsoleService(
    private val consoleService: ConsoleService,
    private val catalog: ReplayDatasetCatalog,
) {
    private val normalizer = WalletNormalizer()

    /** The run's own stored observations, valued with its dataset's rates when the dataset is still in the catalog. */
    fun runChart(runId: String, query: ChartQuery): ChartView {
        val parameters = ChartParameters.of(query)
        val (detail, observations) = consoleService.detailWithObservations(runId)
        val dataset = catalog.find(detail.run.datasetId)?.let { catalog.load(it.id) }
        val pair = parameters.pair ?: onlyPair(observations.map { it.pair }.distinct())
        return assemble(parameters, pair, ChartSource(observations, detail.run.quoteMint, dataset, detail, consoleService.walletTrades(runId)))
    }

    fun datasetChart(datasetId: String, query: ChartQuery): ChartView {
        val parameters = ChartParameters.of(query)
        val pair = parameters.pair ?: throw InvalidConsoleRequestException(ConsolePairs.PAIR, "is required")
        val dataset = catalog.load(datasetId)
        val trades = dataset.transactions.mapNotNull {
            (normalizer.normalize(it, dataset.trackedWallet, pair.quote) as? NormalizationOutcome.Accepted)?.trade
        }
        return assemble(parameters, pair, ChartSource(dataset.observations, pair.quote, dataset, null, trades))
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
        val recordsTrades = source.observations.any { it.trade != null }
        // Later pages continue the observed trades only, so the wallet's own trades come with the first page.
        val walletMarkers = if (recordsTrades && parameters.cursor == null) {
            ChartWalletTrades.unobserved(source.walletTrades, pairObservations, pair, rates).filter { parameters.contains(it.time) }
        } else {
            emptyList()
        }
        val times = points.map { it.observation.observedTime } + walletMarkers.map { it.time }
        val range = ChartRange(parameters.from ?: times.minOrNull(), parameters.to ?: times.maxOrNull())
        val bucketSeconds = ChartBuckets.bucketSeconds(range.from, range.to, parameters.maxBuckets)
        val trackedWallet = dataset?.trackedWallet?.value
        val markers = if (recordsTrades) {
            val observed = ChartMarkers.select(points, trackedWallet, parameters.wallets, parameters.markerLimit, parameters.cursor, bucketSeconds)
            ChartMarkers.including(observed, walletMarkers)
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
