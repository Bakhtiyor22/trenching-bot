package com.trenching.bot.apiusage

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.apiusage.application.ApiUsageAccumulator
import com.trenching.bot.apiusage.domain.ApiUsageBucketKey
import com.trenching.bot.apiusage.domain.ApiUsageDelta
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ApiUsageAccumulatorTest {
    private val clock = SettableClock(Instant.parse("2026-10-16T20:13:07Z"))
    private val accumulator = ApiUsageAccumulator(clock)

    @Test
    fun `records from eight threads add up exactly in one bucket`() {
        val threads = 8
        val perThread = 1_000
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            executor.execute {
                start.await()
                repeat(perThread) { accumulator.record(call(ApiCallOutcome.OK, responseBytes = 3)) }
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        val delta = pending().values.single()
        assertEquals(threads.toLong() * perThread, delta.requests)
        assertEquals(threads.toLong() * perThread * 3, delta.totals.responseBytes)
    }

    @Test
    fun `buckets are five minute UTC windows floored from the clock`() {
        listOf(299L to 0L, 300L to 300L, 301L to 300L, 599L to 300L, 600L to 600L).forEach { (second, bucket) ->
            val accumulator = ApiUsageAccumulator(SettableClock(Instant.ofEpochSecond(second)))
            accumulator.record(call(ApiCallOutcome.OK))
            assertEquals(Instant.ofEpochSecond(bucket), accumulator.withPending { it.keys.single().bucketStart }, "second=$second")
        }
    }

    @Test
    fun `an operation that looks like a url or query is counted as other and never logged`() {
        val secret = "https://rpc.example.test/?api-key=SECRET-KEY-123"
        val events = captureLogs {
            accumulator.record(call(ApiCallOutcome.OK, operation = secret))
            accumulator.record(call(ApiCallOutcome.OK, operation = "getTransaction?x=1"))
            accumulator.record(call(ApiCallOutcome.OK, operation = ""))
            accumulator.record(call(ApiCallOutcome.OK, operation = "a".repeat(65)))
        }

        val keys = pending().keys
        assertEquals(setOf("other"), keys.map { it.operation }.toSet())
        assertEquals(4L, pending().values.single().requests)
        assertEquals(4, events.size)
        assertTrue(events.none { it.formattedMessage.contains("SECRET") || it.formattedMessage.contains("rpc.example.test") }, events.toString())
    }

    @Test
    fun `failures and rate limits are requests while a cache hit is not`() {
        accumulator.record(call(ApiCallOutcome.OK, responseBytes = 10))
        accumulator.record(call(ApiCallOutcome.FAILED, responseBytes = 5))
        accumulator.record(call(ApiCallOutcome.RATE_LIMITED, responseBytes = 2))
        accumulator.record(call(ApiCallOutcome.CACHE_HIT))

        val delta = pending().values.single()
        assertEquals(ApiUsageTotals(requests = 3, failedRequests = 1, rateLimitedResponses = 1, responseBytes = 17, cacheHits = 1), delta.totals)
        assertEquals(clock.instant(), delta.lastCallAt)
    }

    @Test
    fun `each provider, operation, task and capture is its own bucket key`() {
        accumulator.record(call(ApiCallOutcome.OK))
        accumulator.record(call(ApiCallOutcome.OK, captureId = "capture-1"))
        accumulator.record(call(ApiCallOutcome.OK, task = ApiTask.MARKET_STEP))
        accumulator.record(call(ApiCallOutcome.OK, operation = "getSignaturesForAddress"))
        accumulator.record(ApiCall(ApiProvider.BINANCE, "/api/v3/klines", ApiUsageContext.UNATTRIBUTED, ApiCallOutcome.OK, 0))

        val keys = pending().keys
        assertEquals(5, keys.size)
        assertEquals(setOf("", "capture-1"), keys.map { it.captureScope }.toSet())
    }

    @Test
    fun `a drain hands every delta over once and restores them when the write fails`() {
        accumulator.record(call(ApiCallOutcome.OK))

        assertFailsWith<IllegalStateException> { accumulator.drainTo { error("database down") } }
        assertEquals(1L, pending().values.single().requests)

        val written = mutableListOf<Map<ApiUsageBucketKey, ApiUsageDelta>>()
        assertEquals(1, accumulator.drainTo { written += it })
        assertEquals(0, accumulator.drainTo { written += it })
        assertEquals(1, written.size)
        assertEquals(1L, written.single().values.single().requests)
        assertTrue(pending().isEmpty())
    }

    private fun pending(): Map<ApiUsageBucketKey, ApiUsageDelta> = accumulator.withPending { it }

    private fun call(
        outcome: ApiCallOutcome,
        operation: String = "getTransaction",
        task: ApiTask = ApiTask.WALLET_STEP,
        captureId: String? = null,
        responseBytes: Long = 0,
    ) = ApiCall(ApiProvider.SOLANA_RPC, operation, ApiUsageContext(task, captureId), outcome, responseBytes)

    private fun captureLogs(action: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(ApiUsageAccumulator::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.toList()
    }

    private class SettableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
