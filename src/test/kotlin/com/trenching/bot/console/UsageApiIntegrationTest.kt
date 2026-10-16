package com.trenching.bot.console

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

/** Shares the empty-database console context; it records only calls under its own capture id and reads only those back. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["trenching.console.enabled=true", "spring.application.name=console-dashboard-empty"],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class UsageApiIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var recorder: ApiUsageRecorder

    @Test
    fun `the last hour of a capture's recorded calls comes back with purposes, its task and the configured pacing`() {
        val usage = ApiUsageContext(ApiTask.WALLET_ACTIVITY, CAPTURE)
        recorder.record(ApiCall(ApiProvider.SOLANA_RPC, "getSignaturesForAddress", usage, ApiCallOutcome.OK, 900))
        repeat(2) { recorder.record(ApiCall(ApiProvider.SOLANA_RPC, "getTransaction", usage, ApiCallOutcome.OK, 4000)) }
        recorder.record(ApiCall(ApiProvider.SOLANA_RPC, "getTransaction", usage, ApiCallOutcome.CACHE_HIT, 0))

        val reply = ConsoleHttp(port, json).get("/api/usage?period=LAST_HOUR&captureId=$CAPTURE")

        assertEquals(200, reply.status, reply.raw)
        val body = reply.body
        val from = Instant.parse(body["from"].asString())
        val to = Instant.parse(body["to"].asString())
        assertEquals(0, from.epochSecond % 300)
        assertTrue(Duration.between(from, to) >= Duration.ofHours(1) && Duration.between(from, to) < Duration.ofMinutes(65), reply.raw)
        val calls = List(body["byCall"].size()) { body["byCall"][it] }.associateBy { it["operation"].asString() }
        assertEquals(1, calls.getValue("getSignaturesForAddress")["totals"]["requests"].asInt())
        assertEquals("LIST_SIGNATURES", calls.getValue("getSignaturesForAddress")["purpose"].asString())
        assertEquals(2, calls.getValue("getTransaction")["totals"]["requests"].asInt())
        assertEquals(1, calls.getValue("getTransaction")["totals"]["cacheHits"].asInt())
        assertEquals("READ_TRANSACTION", calls.getValue("getTransaction")["purpose"].asString())
        assertEquals("""[{"task":"WALLET_ACTIVITY","totals":{"requests":3,"failedRequests":0,"rateLimitedResponses":0,"responseBytes":8900,"cacheHits":1}}]""",
            body["byTask"].toString())
        assertEquals("""[{"provider":"SOLANA_RPC","minRequestIntervalMillis":125,"maxConcurrentRequests":4}]""", body["pacing"].toString())
    }

    @Test
    fun `an unknown period is the console's invalid request error`() {
        val reply = ConsoleHttp(port, json).get("/api/usage?period=YESTERDAY")

        assertEquals(400, reply.status, reply.raw)
        assertEquals("INVALID_REQUEST", reply.body["code"].asString())
    }

    private companion object {
        const val CAPTURE = "usage-api-integration"
    }
}
