package com.trenching.bot.console

import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.support.AdditionalClockConfiguration
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.runRowCounts
import java.math.BigDecimal
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

/** The USDT view over the imported dip sample, with a fake display rate of 200 USDT per SOL for every minute. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["trenching.console.enabled=true"])
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class, AdditionalClockConfiguration::class, RunUsdtViewIntegrationTest.FixedRates::class)
class RunUsdtViewIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource

    private val http by lazy { ConsoleHttp(port, json) }

    @Test
    fun `the sample run converts to hand-checked USDT strings and viewing it changes nothing stored`() {
        val content = ConsoleTestData.variant(json, "usdt-view").toByteArray()
        val datasetId = http.upload(content, "usdt-view.json").body.path("dataset").path("id").asString()
        val runId = http.post("/api/runs", """{"datasetId":"$datasetId"}""").body.path("run").path("id").asString()
        val report = http.get("/api/runs/$runId/report").raw
        val rows = runRowCounts(dataSource, runId)

        val view = http.get("/api/runs/$runId/usdt")

        assertEquals(200, view.status)
        val body = view.body
        assertTrue(body.path("unavailableReason").isNull, view.raw)
        assertEquals("binance:SOLUSDT:1m:open", body.path("rateSource").asString())
        assertEquals("-2.697564800000", body.path("realizedPnlUsdt").asString())
        assertEquals(listOf("5.652086800000", "-8.349651600000"),
            body.path("scenarios").toList().filter { !it.path("close").isNull }.map { it.path("close").path("realizedPnlUsdt").asString() })
        val fill = body.path("scenarios").first().path("fill")
        assertEquals("0.00001723269028708764211495110270087600", fill.path("executionPriceUsdt").asString())
        assertEquals("20.450000000000", fill.path("costBasisUsdt").asString())
        assertEquals("0.200000000000", fill.path("priorityFeeUsdt").asString())
        assertEquals(List(2) { "0.200000000000" }, body.path("scenarios").toList().filter { !it.path("close").isNull }
            .map { it.path("close").path("transactionCostUsdt").asString() })
        assertEquals("200", fill.path("rate").asString())
        assertEquals("2026-10-01T00:00:00Z", fill.path("minute").asString())
        assertEquals(31, body.path("observations").size())
        assertEquals("0.00002000000000000000000000000000000000", body.path("observations").first().path("priceUsdt").asString())

        assertEquals(report, http.get("/api/runs/$runId/report").raw)
        assertEquals(rows, runRowCounts(dataSource, runId))
        assertEquals(404, http.get("/api/runs/${"0".repeat(64)}/usdt").status)
    }

    @Test
    fun `the current SOL price is served with its source and time`() {
        val price = http.get("/api/market/sol-usdt")

        assertEquals(200, price.status)
        assertEquals("212.34000000", price.body.path("rate").asString())
        assertEquals("binance:SOLUSDT:ticker", price.body.path("source").asString())
        assertEquals("2026-10-05T12:00:00Z", price.body.path("asOf").asString())
        assertFalse(price.body.hasNonNull("unavailableReason"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedRates {
        @Bean
        @Primary
        fun fixedSolUsdtRates(): SolUsdtRateSource = object : SolUsdtRateSource {
            override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> =
                minutes.associateWith { RateLookup.Available(BigDecimal("200"), it, "binance:SOLUSDT:1m:open") }

            override fun current(): RateLookup =
                RateLookup.Available(BigDecimal("212.34000000"), Instant.parse("2026-10-05T12:00:00Z"), "binance:SOLUSDT:ticker")
        }
    }
}
