package com.trenching.bot.console

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.ChartFixtures.OTHER_QUOTE_PAIR
import com.trenching.bot.console.ChartFixtures.PAIR
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.ChartFixtures.tradeObservation
import com.trenching.bot.console.application.ChartConsoleService
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.domain.ChartExtent
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.domain.ChartView
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.MarkerMode
import com.trenching.bot.console.domain.MarkersUnavailableReason
import com.trenching.bot.console.domain.PaperPhase
import com.trenching.bot.console.domain.RunDetail
import com.trenching.bot.console.domain.RunObservations
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.console.domain.ValuationBasisReason
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.DatasetInvalidException
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ChartConsoleServiceTest {
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())
    private val catalog = FakeCatalog()
    private val console: ConsoleService = Mockito.mock(ConsoleService::class.java)
    private val service = ChartConsoleService(console, catalog)
    private val takeProfit = DipSample.pair(DipSample.TAKE_PROFIT)

    @Test
    fun `a dataset chart values the first point of a known pair at its minute's rate and says how`() {
        catalog.add(sample)

        val chart = datasetChart(sample, pair = takeProfit)

        assertEquals("20000.000000000000", assertNotNull(chart.candles.first().mcap).open.toPlainString())
        assertEquals("0.0000001", chart.candles.first().price.open.toPlainString())
        assertTrue(chart.valuationBasis.available)
        assertEquals("test-fixture:SOLUSDT:1m:open", chart.valuationBasis.rateSource)
        assertEquals(sample.trackedWallet.value, chart.trackedWallet)
        assertEquals(sample.observations.first { it.pair == takeProfit }.observedTime, chart.range.from)
        assertTrue(chart.paperFills.isEmpty())
    }

    @Test
    fun `a v3 dataset has no markers and says it was recorded before schema v4`() {
        catalog.add(sample)

        val markers = datasetChart(sample, pair = takeProfit).markers

        assertFalse(markers.available)
        assertEquals(MarkersUnavailableReason.NOT_RECORDED_BEFORE_SCHEMA_V4, markers.unavailableReason)
        assertTrue(markers.items.isEmpty() && markers.clusters.isEmpty())
    }

    @Test
    fun `a dataset chart needs a pair and an unknown dataset is not found`() {
        assertEquals("pair", assertFailsWith<InvalidConsoleRequestException> { service.datasetChart("any", query()) }.field)
        assertFailsWith<DatasetNotFoundException> { service.datasetChart("missing", query(takeProfit)) }
    }

    @Test
    fun `a v4 dataset lists every trade up to the limit and flags only the tracked wallet`() {
        val v4 = v4WithTracked()
        catalog.add(v4)
        val expected = v4.observations.filter { it.pair == takeProfit }

        val markers = datasetChart(v4, pair = takeProfit).markers

        assertTrue(markers.available)
        assertEquals(MarkerMode.ITEMS, markers.mode)
        assertEquals(expected.size, markers.total)
        assertEquals(expected.map { it.id }.sorted(), markers.items.map { it.observationId }.sorted())
        assertEquals(expected.filter { it.trade?.trader == v4.trackedWallet.value }.map { it.id }.sorted(),
            markers.items.filter { it.tracked }.map { it.observationId }.sorted())
        assertTrue(markers.items.all { it.kind == MarkerKind.OBSERVED })
    }

    @Test
    fun `over the marker limit the response is clustered and still accounts for every trade`() {
        val v4 = v4WithTracked()
        catalog.add(v4)
        val trackedIds = v4.observations.filter { it.pair == takeProfit && it.trade?.trader == v4.trackedWallet.value }.map { it.id }

        val markers = datasetChart(v4, pair = takeProfit, markerLimit = trackedIds.size).markers

        assertEquals(MarkerMode.CLUSTERED, markers.mode)
        assertEquals(trackedIds.sorted(), markers.items.map { it.observationId }.sorted())
        assertEquals(markers.total, markers.items.size + markers.clusters.sumOf { it.count })
        assertTrue(markers.clusters.isNotEmpty())
    }

    @Test
    fun `a wallet filter narrows the markers`() {
        val v4 = v4WithTracked()
        catalog.add(v4)
        val other = DipSampleV4.trade(1).trader

        val markers = service.datasetChart(id(v4), query(takeProfit, wallets = listOf(other))).markers

        assertTrue(markers.items.all { it.trader == other || it.trader == v4.trackedWallet.value })
        assertTrue(markers.items.any { it.trader == other })
    }

    @Test
    fun `the rate changing inside a bucket moves the mcap high away from price high times one rate`() {
        val dataset = syntheticDataset(
            listOf(observation("a", at("2026-10-01T00:00:10Z"), "0.00000002"), observation("b", at("2026-10-01T00:01:10Z"), "0.00000002")),
            listOf(rate("2026-10-01T00:00:00Z", "200"), rate("2026-10-01T00:01:00Z", "150")),
        )
        catalog.add(dataset)

        val candle = datasetChart(dataset, pair = PAIR, maxBuckets = 1).candles.single()

        assertEquals(2, candle.observationCount)
        val mcap = assertNotNull(candle.mcap)
        assertEquals(listOf("4000.000000000000", "4000.000000000000", "3000.000000000000", "3000.000000000000"),
            listOf(mcap.open, mcap.high, mcap.low, mcap.close).map { it.toPlainString() })
        assertEquals("0.00000002", candle.price.high.toPlainString())
    }

    @Test
    fun `a minute without a rate gives no mcap and is counted`() {
        val dataset = syntheticDataset(
            listOf(observation("a", at("2026-10-01T00:00:10Z"), "0.00000002"), observation("b", at("2026-10-01T00:05:10Z"), "0.00000002")),
            listOf(rate("2026-10-01T00:00:00Z", "200")),
        )
        catalog.add(dataset)

        val chart = datasetChart(dataset, pair = PAIR, maxBuckets = 2_000)

        assertEquals(2, chart.candles.size)
        assertNotNull(chart.candles[0].mcap)
        assertNull(chart.candles[1].mcap)
        assertEquals(listOf(0, 1), chart.candles.map { it.mcapMissingPoints })
        assertTrue(chart.valuationBasis.available)
    }

    @Test
    fun `a point failing the decimals cross-check is counted as unsupported and not as a missing rate`() {
        val intact = tradeObservation("a", at("2026-10-01T00:00:10Z"), ChartFixtures.TRADER_A)
        val tampered = tradeObservation("b", at("2026-10-01T00:00:20Z"), ChartFixtures.TRADER_B).let { it.copy(price = it.price.add(BigDecimal.ONE)) }
        val dataset = syntheticDataset(listOf(intact, tampered), listOf(rate("2026-10-01T00:00:00Z", "200")))
        catalog.add(dataset)

        val chart = datasetChart(dataset, pair = PAIR, maxBuckets = 1)

        val candle = chart.candles.single()
        assertEquals(1, candle.mcapUnsupportedPoints)
        assertEquals(0, candle.mcapMissingPoints)
        assertNotNull(candle.mcap)
        assertTrue(chart.valuationBasis.available)
    }

    @Test
    fun `a pair not quoted in SOL is not valued`() {
        val dataset = syntheticDataset(listOf(observation("a", at("2026-10-01T00:00:10Z"), "1", pair = OTHER_QUOTE_PAIR)),
            listOf(rate("2026-10-01T00:00:00Z", "200")))
        catalog.add(dataset)

        val chart = datasetChart(dataset, pair = OTHER_QUOTE_PAIR)

        assertEquals(ValuationBasisReason.NOT_SOL_QUOTED, chart.valuationBasis.unavailableReason)
        assertNull(chart.candles.single().mcap)
    }

    @Test
    fun `thousands of observations over days stay within the candle cap at the smallest fitting size`() {
        val start = at("2026-10-01T00:00:00Z")
        val observations = (0 until 5_000).map { observation("o$it", start.plusSeconds(it * 172L), "0.00000002") }
        val dataset = syntheticDataset(observations, listOf(rate("2026-10-01T00:00:00Z", "200")))
        catalog.add(dataset)

        val chart = datasetChart(dataset, pair = PAIR, maxBuckets = 5_000)

        assertEquals(900, chart.bucketSeconds)
        assertTrue(chart.candles.size <= 2_000)
        assertEquals(5_000, chart.candles.sumOf { it.observationCount })
    }

    @Test
    fun `the window keeps both bounds and the range reports the request`() {
        val dataset = syntheticDataset((0 until 10).map { observation("o$it", at("2026-10-01T00:00:00Z").plusSeconds(it * 10L), "1") }, emptyList())
        catalog.add(dataset)

        val chart = service.datasetChart(id(dataset), query(PAIR, from = at("2026-10-01T00:00:20Z"), to = at("2026-10-01T00:00:40Z")))

        assertEquals(3, chart.candles.sumOf { it.observationCount })
        assertEquals(at("2026-10-01T00:00:20Z"), chart.range.from)
        assertEquals(at("2026-10-01T00:00:40Z"), chart.range.to)
        assertEquals(ValuationBasisReason.RATES_UNAVAILABLE, chart.valuationBasis.unavailableReason)
    }

    @Test
    fun `the extent is the pair's whole range whatever window is asked for`() {
        val start = at("2026-10-01T00:00:00Z")
        val pairObservations = (0 until 10).map { observation("o$it", start.plusSeconds(it * 10L), "1", slot = 500L + it) }
        val otherPair = listOf(observation("x0", start.minusSeconds(60), "1", slot = 1L, pair = OTHER_QUOTE_PAIR),
            observation("x1", start.plusSeconds(600), "1", slot = 900L, pair = OTHER_QUOTE_PAIR))
        val dataset = syntheticDataset(pairObservations + otherPair, emptyList())
        catalog.add(dataset)

        val windowed = service.datasetChart(id(dataset), query(PAIR, from = at("2026-10-01T00:00:20Z"), to = at("2026-10-01T00:00:40Z"),
            maxBuckets = 1))
        val whole = service.datasetChart(id(dataset), query(PAIR))

        assertEquals(ChartExtent(start, at("2026-10-01T00:01:30Z"), 500, 509), windowed.extent)
        assertEquals(windowed.extent, whole.extent)
    }

    @Test
    fun `a run chart's extent is its pair's whole range, not the window`() {
        catalog.add(sample)
        val spread = sample.observations.map { it.copy(observedTime = it.observedTime.plusSeconds(it.slot.value)) }
        val pairObservations = spread.filter { it.pair == takeProfit }
        val entryTime = spread.first { it.id == "fixture:TP:1003:0" }.observedTime

        val chart = runChart(detail(spread), spread, pair = takeProfit, to = entryTime)

        assertEquals(ChartExtent(pairObservations.minOf { it.observedTime }, pairObservations.maxOf { it.observedTime },
            pairObservations.minOf { it.slot.value }, pairObservations.maxOf { it.slot.value }), chart.extent)
        assertTrue(assertNotNull(chart.extent).lastTime > entryTime)
    }

    @Test
    fun `a pair without observations has no extent`() {
        catalog.add(sample)

        assertNull(datasetChart(sample, pair = PAIR).extent)
        assertNull(runChart(detail(emptyList()), emptyList()).extent)
    }

    @Test
    fun `a run chart carries its stored fill and close as paper fills and keeps them out of the markers`() {
        catalog.add(sample)
        val detail = detail(sample.observations)

        val chart = runChart(detail, sample.observations, pair = takeProfit)

        val scenario = detail.scenarios.single { it.intent.pair == takeProfit }
        val fill = (scenario.outcome as FillOutcome.Filled).fill
        val close = assertNotNull(scenario.position?.close)
        assertEquals(listOf(PaperPhase.ENTRY, PaperPhase.EXIT), chart.paperFills.map { it.phase })
        assertTrue(chart.paperFills.all { it.kind == MarkerKind.PAPER && it.intentId == scenario.intent.id })
        val (entry, exit) = chart.paperFills
        assertEquals(listOf(TradeSide.BUY, TradeSide.SELL), listOf(entry.side, exit.side))
        assertEquals(listOf(fill.actualSlot.value, close.slot.value), listOf(entry.slot, exit.slot))
        assertEquals(listOf(fill.executionPrice, close.executionPrice), listOf(entry.price, exit.price))
        assertEquals(listOf(fill.quantity, fill.quantity), listOf(entry.quantity, exit.quantity))
        assertEquals(listOf(null, close.reason.name), listOf(entry.reason, exit.reason))
        assertEquals(sample.observations.single { it.id == fill.observationId }.observedTime, entry.time)
        assertEquals(sample.observations.single { it.id == close.observationId }.observedTime, exit.time)
        assertEquals(mcapOf(sample.observations.single { it.id == fill.observationId }), entry.mcap)
        assertFalse(chart.markers.available)
    }

    @Test
    fun `paper fills outside the window are left out`() {
        catalog.add(sample)
        val spread = sample.observations.map { it.copy(observedTime = it.observedTime.plusSeconds(it.slot.value)) }
        val entryTime = spread.first { it.id == "fixture:TP:1003:0" }.observedTime

        val chart = runChart(detail(spread), spread, pair = takeProfit, to = entryTime)

        assertEquals(listOf(PaperPhase.ENTRY), chart.paperFills.map { it.phase })
    }

    @Test
    fun `a run chart without a pair needs one only when the run has several`() {
        catalog.add(sample)
        val detail = detail(sample.observations)

        assertEquals("pair", assertFailsWith<InvalidConsoleRequestException> { runChart(detail, sample.observations) }.field)

        val single = sample.observations.filter { it.pair == takeProfit }
        assertTrue(runChart(detail, single).candles.isNotEmpty())
    }

    @Test
    fun `a run whose dataset left the catalog still draws prices but no mcap or tracked wallet`() {
        val detail = detail(sample.observations)

        val chart = runChart(detail, sample.observations, pair = takeProfit)

        assertTrue(chart.candles.isNotEmpty())
        assertTrue(chart.candles.all { it.mcap == null && it.mcapMissingPoints == 0 })
        assertEquals(ValuationBasisReason.RATES_UNAVAILABLE, chart.valuationBasis.unavailableReason)
        assertNull(chart.valuationBasis.rateSource)
        assertNull(chart.trackedWallet)
        assertTrue(chart.paperFills.isNotEmpty() && chart.paperFills.all { it.mcap == null })
    }

    @Test
    fun `a v4 run flags the tracked wallet from its dataset`() {
        val v4 = v4WithTracked()
        catalog.add(v4)
        val detail = detail(v4.observations, datasetId = id(v4))

        val chart = runChart(detail, v4.observations, pair = takeProfit)

        assertTrue(chart.markers.available)
        assertEquals(v4.trackedWallet.value, chart.trackedWallet)
        assertTrue(chart.markers.items.any { it.tracked })
    }

    @Test
    fun `an unknown run is not found`() {
        Mockito.`when`(console.detailWithObservations("missing")).thenThrow(com.trenching.bot.replay.domain.SimulationRunNotFoundException("missing"))

        assertFailsWith<com.trenching.bot.replay.domain.SimulationRunNotFoundException> { service.runChart("missing", query()) }
    }

    private fun v4WithTracked(): ReplayDataset = DipSampleV4.dataset().let {
        it.copy(trackedWallet = WalletAddress.of(DipSampleV4.trade(0).trader),
            referenceRates = it.referenceRates.map { rate -> rate.copy(source = "v4-test") })
    }

    private fun mcapOf(observation: MarketObservation): BigDecimal {
        val rate = sample.referenceRates.single { it.minute == ReferenceRate.minuteOf(observation.observedTime) }.rate
        return DecimalPolicy.scale(observation.price.multiply(rate).multiply(BigDecimal("1000000000")))
    }

    private fun syntheticDataset(observations: List<MarketObservation>, rates: List<ReferenceRate>) = sample.copy(
        observations = observations, referenceRates = rates, transactions = emptyList())

    private fun rate(minute: String, value: String) = ReferenceRate(Instant.parse(minute), BigDecimal(value), "test-rates")

    private fun id(dataset: ReplayDataset): String = catalog.idOf(dataset)

    private fun query(
        pair: MarketPair? = null,
        from: Instant? = null,
        to: Instant? = null,
        maxBuckets: Int? = null,
        markerLimit: Int? = null,
        wallets: List<String> = emptyList(),
    ) = ChartQuery(pair?.let { "${it.base.value}/${it.quote.value}" }, from, to, maxBuckets, markerLimit, wallets, null)

    private fun datasetChart(dataset: ReplayDataset, pair: MarketPair, maxBuckets: Int? = null, markerLimit: Int? = null): ChartView =
        service.datasetChart(id(dataset), query(pair, maxBuckets = maxBuckets, markerLimit = markerLimit))

    private fun runChart(detail: RunDetail, observations: List<MarketObservation>, pair: MarketPair? = null, to: Instant? = null): ChartView {
        Mockito.`when`(console.detailWithObservations(detail.run.id)).thenReturn(RunObservations(detail, observations))
        return service.runChart(detail.run.id, query(pair, to = to))
    }

    private fun detail(observations: List<MarketObservation>, datasetId: String = report.datasetChecksum): RunDetail {
        val byId = observations.associateBy { it.id }
        val scenarios = report.scenarios.map { result ->
            val selected = (result.outcome as? FillOutcome.Filled)?.let { byId[it.fill.observationId] }
            RunScenario(result.intent, result.outcome, selected, result.position, result.exitUnavailable)
        }
        val run = RunSummary(report.runId, datasetId, report.engineVersion, report.configurationHash, report.resultChecksum,
            MintAddress.WRAPPED_SOL, 10, 5, 5, 3, 2, BigDecimal(DipSample.TOTAL_PNL), Instant.parse("2026-10-08T00:00:00Z"))
        return RunDetail(run, SimulationConfig.mvp(), scenarios)
    }

    /** Keyed by the dataset's position in the registration order; the sample is also reachable under the run's dataset id. */
    private inner class FakeCatalog : ReplayDatasetCatalog {
        private val datasets = LinkedHashMap<String, ReplayDataset>()

        fun add(dataset: ReplayDataset) {
            datasets[if (dataset === sample) report.datasetChecksum else "dataset-${datasets.size}"] = dataset
        }

        fun idOf(dataset: ReplayDataset): String = datasets.entries.first { it.value === dataset }.key

        override fun find(id: String): DatasetSummary? = datasets[id]?.let { dataset ->
            DatasetSummary(id, id, dataset.trackedWallet, dataset.schemaVersion, dataset.transactions.size, dataset.observations.size, null, null,
                emptyList(), Instant.EPOCH)
        }

        override fun load(id: String): ReplayDataset = datasets[id] ?: throw DatasetNotFoundException(id)
        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }
}
