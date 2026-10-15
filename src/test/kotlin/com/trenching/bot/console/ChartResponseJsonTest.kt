package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.api.dto.CandleResponse
import com.trenching.bot.console.api.dto.ObservedMarkerResponse
import com.trenching.bot.console.api.dto.OhlcResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

/** Serializes with the mapper the application gets from Spring Boot (Kotlin module included), not a hand-built one. */
class ChartResponseJsonTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))

    @Test
    fun `the tracked flag is the isTrackedWallet key and decimals stay plain strings`() {
        runner.run { context ->
            val json = context.getBean(JsonMapper::class.java)
            val marker = ObservedMarkerResponse("OBSERVED", "o1", TRACKED, true, "BUY", "10", "18446744073709551615", "0.00000010", "1E+3", 7,
                at("2026-10-01T00:00:01Z"), null, "pumpfun-bonding-curve-trade/v1")
            val candle = CandleResponse(at("2026-10-01T00:00:00Z"), 2, OhlcResponse("0.00000001", "0.00000002", "0.00000001", "0.00000002"), null, 1, 2)

            val markerNode = json.readTree(json.writeValueAsString(marker))
            val candleNode = json.readTree(json.writeValueAsString(candle))

            assertTrue(markerNode.has("isTrackedWallet"))
            assertTrue(markerNode["isTrackedWallet"].asBoolean())
            assertFalse(markerNode.has("trackedWallet") || markerNode.has("isTracked") || markerNode.has("tracked"))
            assertTrue(markerNode["price"].isString)
            assertEquals("0.00000010", markerNode["price"].asString())
            assertEquals("18446744073709551615", markerNode["tokenRaw"].asString())
            assertTrue(candleNode["price"]["open"].isString)
            assertEquals(1, candleNode["mcapMissingPoints"].asInt())
            assertEquals(2, candleNode["mcapUnsupportedPoints"].asInt())
        }
    }
}
