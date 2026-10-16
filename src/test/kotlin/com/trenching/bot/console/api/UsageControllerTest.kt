package com.trenching.bot.console.api

import com.trenching.bot.apiusage.ApiCallUsage
import com.trenching.bot.apiusage.ApiPacing
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiPurpose
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiTaskUsage
import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsagePoint
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.console.application.UsageConsoleService
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.UsageResponseMapperImpl
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class UsageControllerTest {
    private val json = JsonMapper.builder().build()
    private val asked = mutableListOf<Pair<ApiUsagePeriod, String?>>()
    private val reports = ApiUsageReports { period, captureId -> asked += period to captureId; REPORT }
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(UsageController(UsageConsoleService(reports), UsageResponseMapperImpl()))
        .setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl()))
        .build()

    @Test
    fun `the report carries its window, units, calls with purposes, tasks, series and pacing`() {
        val body = body("/api/usage?period=LAST_HOUR", 200)

        assertEquals(listOf<Pair<ApiUsagePeriod, String?>>(ApiUsagePeriod.LAST_HOUR to null), asked)
        assertEquals(listOf("from", "to", "bucketSeconds", "recordingStartedAt", "totals", "byCall", "byTask", "series", "pacing"),
            body.propertyNames().toList())
        assertEquals("2026-10-16T19:10:00Z", body["from"].asString())
        assertEquals("2026-10-16T20:13:07Z", body["to"].asString())
        assertEquals(300, body["bucketSeconds"].asInt())
        assertEquals("2026-10-14T08:00:00Z", body["recordingStartedAt"].asString())
        assertEquals("""{"requests":3,"failedRequests":1,"rateLimitedResponses":1,"responseBytes":120,"cacheHits":2}""", body["totals"].toString())
        assertEquals("""{"provider":"SOLANA_RPC","operation":"getTransaction","purpose":"READ_TRANSACTION",""" +
            """"totals":{"requests":2,"failedRequests":1,"rateLimitedResponses":0,"responseBytes":100,"cacheHits":2},""" +
            """"sharePercent":"66.7","lastCallAt":"2026-10-16T20:13:07Z"}""", body["byCall"][0].toString())
        assertEquals("BINANCE", body["byCall"][1]["provider"].asString())
        assertEquals("MINUTE_RATES", body["byCall"][1]["purpose"].asString())
        assertEquals(true, body["byCall"][1]["lastCallAt"].isNull)
        assertEquals(listOf("WALLET_STEP", "RATES_STEP"), List(body["byTask"].size()) { body["byTask"][it]["task"].asString() })
        assertEquals("""{"bucketStart":"2026-10-16T20:10:00Z","provider":"SOLANA_RPC","requests":2}""", body["series"][0].toString())
        assertEquals("""[{"provider":"SOLANA_RPC","minRequestIntervalMillis":125,"maxConcurrentRequests":4}]""", body["pacing"].toString())
    }

    @Test
    fun `a capture id narrows the report and a blank one means every capture`() {
        body("/api/usage?period=ALL&captureId=capture-1", 200)
        body("/api/usage?period=LAST_7_DAYS&captureId=", 200)
        body("/api/usage", 200)

        assertEquals(listOf<Pair<ApiUsagePeriod, String?>>(ApiUsagePeriod.ALL to "capture-1", ApiUsagePeriod.LAST_7_DAYS to null, ApiUsagePeriod.LAST_HOUR to null), asked)
    }

    @Test
    fun `an unknown period or a capture id that is not an identifier is a console 400`() {
        val period = body("/api/usage?period=LAST_YEAR", 400)
        val capture = body("/api/usage?period=ALL&captureId=../secrets", 400)

        assertEquals("INVALID_REQUEST", period["code"].asString())
        assertEquals("""{"period":"has an invalid value"}""", period["fields"].toString())
        assertEquals("INVALID_REQUEST", capture["code"].asString())
        assertEquals(listOf("captureId"), capture["fields"].propertyNames().toList())
        assertEquals(emptyList<Pair<ApiUsagePeriod, String?>>(), asked)
    }

    private fun body(path: String, status: Int): JsonNode {
        val response = mvc.perform(get(path)).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return json.readTree(response.contentAsString)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T20:13:07Z")
        val BUCKET: Instant = Instant.parse("2026-10-16T20:10:00Z")
        val REPORT = ApiUsageReport(
            from = Instant.parse("2026-10-16T19:10:00Z"), to = NOW, bucketSeconds = 300, recordingStartedAt = Instant.parse("2026-10-14T08:00:00Z"),
            totals = ApiUsageTotals(3, 1, 1, 120, 2),
            byCall = listOf(
                ApiCallUsage(ApiProvider.SOLANA_RPC, "getTransaction", ApiPurpose.READ_TRANSACTION, ApiUsageTotals(2, 1, 0, 100, 2), "66.7", NOW),
                ApiCallUsage(ApiProvider.BINANCE, "/api/v3/klines", ApiPurpose.MINUTE_RATES, ApiUsageTotals(1, 0, 1, 20, 0), "33.3", null),
            ),
            byTask = listOf(ApiTaskUsage(ApiTask.WALLET_STEP, ApiUsageTotals(2, 1, 0, 100, 2)), ApiTaskUsage(ApiTask.RATES_STEP, ApiUsageTotals(1, 0, 1, 20, 0))),
            series = listOf(ApiUsagePoint(BUCKET, ApiProvider.SOLANA_RPC, 2), ApiUsagePoint(BUCKET, ApiProvider.BINANCE, 1)),
            pacing = listOf(ApiPacing(ApiProvider.SOLANA_RPC, 125, 4)),
        )
    }
}
