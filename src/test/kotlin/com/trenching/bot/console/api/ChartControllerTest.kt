package com.trenching.bot.console.api

import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.application.ChartConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.domain.CandleView
import com.trenching.bot.console.domain.ChartCoverage
import com.trenching.bot.console.domain.ChartExtent
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.domain.ChartRange
import com.trenching.bot.console.domain.ChartView
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.MarkerClusterView
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.MarkerMode
import com.trenching.bot.console.domain.MarkersView
import com.trenching.bot.console.domain.Ohlc
import com.trenching.bot.console.domain.ObservedMarkerView
import com.trenching.bot.console.domain.PaperFillView
import com.trenching.bot.console.domain.PaperPhase
import com.trenching.bot.console.domain.ValuationBasisReason
import com.trenching.bot.console.domain.TickView
import com.trenching.bot.console.domain.TicksView
import com.trenching.bot.console.domain.ValuationBasisView
import com.trenching.bot.console.mapper.ChartResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class ChartControllerTest {
    private val service: ChartConsoleService = Mockito.mock(ChartConsoleService::class.java)
    private val json = JsonMapper.builder().build()
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ChartController(service, ChartResponseMapperImpl()))
        .setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl()))
        .build()

    @Test
    fun `a run chart serializes the contract's fields with decimals as plain strings`() {
        Mockito.`when`(service.runChart("run-1", ChartQuery(null, null, null, null, null, emptyList(), null, null))).thenReturn(view())

        val body = body("/api/runs/run-1/chart", 200)

        assertEquals(60, body["bucketSeconds"].asInt())
        assertEquals("2026-10-01T00:00:00Z", body["range"]["from"].asString())
        val extent = body["extent"]
        assertEquals(listOf("firstTime", "lastTime", "firstSlot", "lastSlot"), extent.propertyNames().toList())
        assertEquals("2026-09-30T23:00:00Z", extent["firstTime"].asString())
        assertEquals("2026-10-01T02:00:00Z", extent["lastTime"].asString())
        assertEquals(100, extent["firstSlot"].asLong())
        assertEquals(9_000, extent["lastSlot"].asLong())
        assertEquals(TRACKED, body["trackedWallet"].asString())
        val basis = body["valuationBasis"]
        assertEquals(listOf("label", "supply", "supplySource", "quoteCurrency", "rateSource", "rateGranularity", "available", "unavailableReason"),
            basis.propertyNames().toList())
        assertEquals("RATES_UNAVAILABLE", basis["unavailableReason"].asString())
        val candle = body["candles"][0]
        assertEquals(listOf("bucketStart", "observationCount", "price", "mcap", "mcapMissingPoints", "mcapUnsupportedPoints"), candle.propertyNames().toList())
        assertEquals("0.00000000", candle["price"]["open"].asString())
        assertTrue(candle["mcap"].isNull)
        assertEquals(3, candle["mcapUnsupportedPoints"].asInt())
        val markers = body["markers"]
        assertEquals(listOf("available", "unavailableReason", "mode", "total", "items", "nextCursor", "clusters"), markers.propertyNames().toList())
        assertEquals("CLUSTERED", markers["mode"].asString())
        val item = markers["items"][0]
        assertEquals(listOf("kind", "observationId", "trader", "isTrackedWallet", "side", "solLamports", "tokenRaw", "price", "mcap", "slot", "time",
            "estimatedTime", "signature", "source"), item.propertyNames().toList())
        assertEquals("2026-10-01T00:00:01.250Z", item["estimatedTime"].asString())
        assertEquals("OBSERVED", item["kind"].asString())
        assertTrue(item["isTrackedWallet"].asBoolean())
        assertEquals("18446744073709551615", item["tokenRaw"].asString())
        assertTrue(item["signature"].isNull)
        assertEquals(listOf("bucketStart", "count", "buyCount", "sellCount", "firstSlot", "lastSlot", "firstTime", "lastTime"),
            markers["clusters"][0].propertyNames().toList())
        val paper = body["paperFills"][0]
        assertEquals(listOf("kind", "phase", "side", "intentId", "slot", "time", "estimatedTime", "price", "mcap", "quantity", "reason"), paper.propertyNames().toList())
        assertEquals("PAPER", paper["kind"].asString())
        assertEquals("EXIT", paper["phase"].asString())
        assertEquals("0.100000000000", paper["quantity"].asString())
        assertEquals(216.2, body["millisPerSlot"].asDouble())
        assertEquals(8, body["coverage"][0]["fromSlot"].asLong())
        val ticks = body["ticks"]
        assertEquals(listOf("included", "total", "items"), ticks.propertyNames().toList())
        val tick = ticks["items"][0]
        assertEquals(listOf("observationId", "slot", "indexInSlot", "slotOrderProven", "time", "estimatedTime", "price", "mcap", "curvePrice",
            "curveMcap", "side", "trader", "isTrackedWallet", "solLamports", "tokenRaw"), tick.propertyNames().toList())
        assertEquals("2.00000000", tick["curvePrice"].asString())
        assertEquals("BUY", tick["side"].asString())
        assertEquals("18446744073709551615", tick["tokenRaw"].asString())
    }

    @Test
    fun `the query parameters reach the service as one query for runs and datasets`() {
        val query = ChartQuery("a/b", at("2026-10-01T00:00:00Z"), at("2026-10-02T00:00:00Z"), 50, 20, listOf("w1", "w2"), "cur", 25)
        Mockito.`when`(service.runChart("r", query)).thenReturn(view())
        Mockito.`when`(service.datasetChart("d", query)).thenReturn(view())
        val parameters = "pair=a/b&from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z&maxBuckets=50&markerLimit=20&wallet=w1&wallet=w2&cursor=cur&tickLimit=25"

        body("/api/runs/r/chart?$parameters", 200)
        body("/api/datasets/d/chart?$parameters", 200)
    }

    @Test
    fun `invalid requests and unknown ids use the console error body`() {
        Mockito.`when`(service.datasetChart("d", ChartQuery(null, null, null, null, null, emptyList(), null, null)))
            .thenThrow(InvalidConsoleRequestException("pair", "is required"))
        Mockito.`when`(service.runChart("gone", ChartQuery(null, null, null, null, null, emptyList(), null, null)))
            .thenThrow(SimulationRunNotFoundException("gone"))
        Mockito.`when`(service.datasetChart("missing", ChartQuery(null, null, null, null, null, emptyList(), null, null)))
            .thenThrow(DatasetNotFoundException("missing"))

        assertEquals("INVALID_REQUEST", body("/api/datasets/d/chart", 400)["code"].asString())
        assertEquals("is required", body("/api/datasets/d/chart", 400)["fields"]["pair"].asString())
        assertEquals("RUN_NOT_FOUND", body("/api/runs/gone/chart", 404)["code"].asString())
        assertEquals("DATASET_NOT_FOUND", body("/api/datasets/missing/chart", 404)["code"].asString())
        assertEquals("from", body("/api/runs/r/chart?from=yesterday", 400)["fields"].propertyNames().single())
        assertEquals("maxBuckets", body("/api/runs/r/chart?maxBuckets=many", 400)["fields"].propertyNames().single())
    }

    @Test
    fun `the chart endpoints are registered only when the console is enabled and share its error handling`() {
        assertNotNull(ChartController::class.java.getAnnotation(ConditionalOnConsoleEnabled::class.java))
        assertNotNull(ConsoleExceptionHandler::class.java.getAnnotation(ConditionalOnConsoleEnabled::class.java))
        val advice = ConsoleExceptionHandler::class.java.getAnnotation(org.springframework.web.bind.annotation.RestControllerAdvice::class.java)
        assertTrue(ChartController::class in advice.assignableTypes.toList())
    }

    private fun body(path: String, status: Int): JsonNode {
        val response = mvc.perform(get(path)).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return json.readTree(response.contentAsString)
    }

    private fun view() = ChartView(
        bucketSeconds = 60,
        range = ChartRange(at("2026-10-01T00:00:00Z"), at("2026-10-01T01:00:00Z")),
        extent = ChartExtent(at("2026-09-30T23:00:00Z"), at("2026-10-01T02:00:00Z"), 100, 9_000),
        trackedWallet = TRACKED,
        valuationBasis = ValuationBasisView("total-supply valuation (FDV), USDT", "1000000000", "PUMPFUN_FIXED_SUPPLY", "USDT", null, "MINUTE", false,
            ValuationBasisReason.RATES_UNAVAILABLE),
        candles = listOf(CandleView(at("2026-10-01T00:00:00Z"), 2, Ohlc(price("0"), price("0"), price("0"), price("0")), null, 0, 3)),
        markers = MarkersView(true, null, MarkerMode.CLUSTERED, 3, listOf(
            ObservedMarkerView(MarkerKind.OBSERVED, "o1", TRACKED, true, TradeSide.BUY, BigInteger.TEN, BigInteger("18446744073709551615"),
                price("1"), null, 7, at("2026-10-01T00:00:01Z"), at("2026-10-01T00:00:01.250Z"), null, "pumpfun-bonding-curve-trade/v1")), null, listOf(
            MarkerClusterView(at("2026-10-01T00:00:00Z"), 2, 1, 1, 1, 2, at("2026-10-01T00:00:00Z"), at("2026-10-01T00:00:01Z")))),
        paperFills = listOf(PaperFillView(MarkerKind.PAPER, PaperPhase.EXIT, TradeSide.SELL, "i1", 9, at("2026-10-01T00:00:02Z"),
            at("2026-10-01T00:00:02.500Z"), price("1"), null, BigDecimal("0.1").setScale(12), "TAKE_PROFIT")),
        millisPerSlot = 216.2,
        coverage = listOf(ChartCoverage(8, 9_000)),
        ticks = TicksView(true, 1, listOf(TickView("o1", 7, 0, true, at("2026-10-01T00:00:01Z"), at("2026-10-01T00:00:01.250Z"), price("1"), null,
            price("2"), null, TradeSide.BUY, TRACKED, true, BigInteger.TEN, BigInteger("18446744073709551615")))),
    )

    private fun price(value: String): BigDecimal = BigDecimal(value).setScale(8)
}
