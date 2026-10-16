package com.trenching.bot.apiusage

import com.trenching.bot.apiusage.application.ApiUsageAccumulator
import com.trenching.bot.apiusage.application.ApiUsageBucketStore
import com.trenching.bot.apiusage.application.ApiUsageFlusher
import com.trenching.bot.apiusage.application.ApiUsageReportService
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageBucketEntity
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageBucketRepository
import com.trenching.bot.apiusage.mapper.ApiUsageMapper
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/** A context of its own (the extra property keeps it out of the shared cache), so no other test's usage lands in its table. */
@SpringBootTest(properties = ["spring.application.name=api-usage-persistence"])
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class ApiUsageBucketPersistenceIntegrationTest {
    @Autowired private lateinit var repository: ApiUsageBucketRepository
    @Autowired private lateinit var store: ApiUsageBucketStore
    @Autowired private lateinit var mapper: ApiUsageMapper

    private val clock = SettableClock(NOW)
    private val accumulator = ApiUsageAccumulator(clock)
    private val pacing = listOf(ApiPacing(ApiProvider.SOLANA_RPC, 125, 4))

    @BeforeEach
    fun emptyTable() = repository.deleteAll()

    @Test
    fun `two flushes of separate deltas add up in one bucket row`() {
        val flusher = ApiUsageFlusher(accumulator, store, java.time.Duration.ofSeconds(15))
        repeat(2) { accumulator.record(rpc(ApiCallOutcome.OK, bytes = 100)) }
        assertEquals(1, flusher.flush())
        accumulator.record(rpc(ApiCallOutcome.FAILED, bytes = 10))
        accumulator.record(rpc(ApiCallOutcome.RATE_LIMITED, bytes = 1))
        accumulator.record(rpc(ApiCallOutcome.CACHE_HIT))
        assertEquals(1, flusher.flush())
        assertEquals(0, flusher.flush())

        val row = repository.findAll().single()
        assertEquals(listOf(4L, 1L, 1L, 211L, 1L), listOf(row.requests, row.failedRequests, row.rateLimitedResponses, row.responseBytes, row.cacheHits))
        assertEquals(NOW, row.lastCallAt)
        assertEquals(Instant.parse("2026-10-16T20:10:00Z"), row.bucketStart)
        assertEquals("", row.captureScope)
    }

    @Test
    fun `a bucket holding only cache hits has no last call`() {
        accumulator.record(rpc(ApiCallOutcome.CACHE_HIT))
        accumulator.drainTo(store::add)

        val row = repository.findAll().single()
        assertEquals(0L, row.requests)
        assertEquals(1L, row.cacheHits)
        assertNull(row.lastCallAt)
    }

    @Test
    fun `concurrent flushes of the same new bucket from separate writers converge`() {
        val threads = 8
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) {
            executor.execute {
                val writer = ApiUsageAccumulator(clock)
                writer.record(ApiCall(ApiProvider.SOLANA_RPC, "getTransaction", ApiUsageContext(ApiTask.MARKET_STEP, "capture-1"), ApiCallOutcome.OK, 7))
                start.await()
                try { ApiUsageFlusher(writer, store, java.time.Duration.ofSeconds(15)).flush() } catch (exception: RuntimeException) { failures += exception }
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), failures.toString())
        val row = repository.findAll().single()
        assertEquals(threads.toLong(), row.requests)
        assertEquals(threads * 7L, row.responseBytes)
    }

    @Test
    fun `the bucket identity is unique in the database`() {
        repository.saveAndFlush(entity("a".repeat(64)))

        assertFailsWith<DataIntegrityViolationException> { repository.saveAndFlush(entity("b".repeat(64))) }
    }

    @Test
    fun `a report counts unflushed requests once, before and after they are flushed`() {
        accumulator.record(rpc(ApiCallOutcome.OK, bytes = 50))
        accumulator.drainTo(store::add)
        accumulator.record(rpc(ApiCallOutcome.OK, bytes = 50))
        accumulator.record(ApiCall(ApiProvider.BINANCE, "/api/v3/klines", ApiUsageContext(ApiTask.RATES_STEP, "capture-1"), ApiCallOutcome.OK, 20))

        val before = reports().report(ApiUsagePeriod.LAST_HOUR, null)
        accumulator.drainTo(store::add)
        val after = reports().report(ApiUsagePeriod.LAST_HOUR, null)

        assertEquals(before, after)
        assertEquals(ApiUsageTotals(3, 0, 0, 120, 0), after.totals)
        assertEquals(listOf("getTransaction" to "66.7", "/api/v3/klines" to "33.3"), after.byCall.map { it.operation to it.sharePercent })
        assertEquals(listOf(ApiPurpose.READ_TRANSACTION, ApiPurpose.MINUTE_RATES), after.byCall.map { it.purpose })
        assertEquals(listOf(ApiTask.WALLET_STEP to 2L, ApiTask.RATES_STEP to 1L), after.byTask.map { it.task to it.totals.requests })
        assertEquals(listOf(ApiUsagePoint(Instant.parse("2026-10-16T20:10:00Z"), ApiProvider.SOLANA_RPC, 2),
            ApiUsagePoint(Instant.parse("2026-10-16T20:10:00Z"), ApiProvider.BINANCE, 1)), after.series)
        assertEquals(pacing, after.pacing)
    }

    @Test
    fun `periods end now and start at the five minute bucket of their duration, and ALL at the oldest bucket`() {
        clock.now = Instant.parse("2026-10-14T08:02:30Z")
        accumulator.record(rpc(ApiCallOutcome.OK, captureId = "capture-1"))
        accumulator.drainTo(store::add)
        clock.now = NOW
        accumulator.record(rpc(ApiCallOutcome.OK))

        val hour = reports().report(ApiUsagePeriod.LAST_HOUR, null)
        val day = reports().report(ApiUsagePeriod.LAST_24_HOURS, null)
        val week = reports().report(ApiUsagePeriod.LAST_7_DAYS, null)
        val all = reports().report(ApiUsagePeriod.ALL, null)
        val capture = reports().report(ApiUsagePeriod.ALL, "capture-1")

        assertEquals(Instant.parse("2026-10-16T19:10:00Z") to NOW, hour.from to hour.to)
        assertEquals(Instant.parse("2026-10-15T20:10:00Z"), day.from)
        assertEquals(Instant.parse("2026-10-09T20:10:00Z"), week.from)
        assertEquals(Instant.parse("2026-10-14T08:00:00Z"), all.from)
        assertEquals(Instant.parse("2026-10-14T08:00:00Z"), all.recordingStartedAt)
        assertEquals(listOf(1L, 1L, 2L, 2L), listOf(hour, day, week, all).map { it.totals.requests })
        assertEquals(1L, capture.totals.requests)
        assertEquals(300, hour.bucketSeconds)
    }

    @Test
    fun `an empty table reports zeros, no recording start and a zero share`() {
        val report = reports().report(ApiUsagePeriod.ALL, null)

        assertNull(report.recordingStartedAt)
        assertEquals(ApiUsageTotals.ZERO, report.totals)
        assertTrue(report.byCall.isEmpty() && report.byTask.isEmpty() && report.series.isEmpty())
        assertEquals(Instant.parse("2026-10-16T20:10:00Z"), report.from)
    }

    private fun reports() = ApiUsageReportService(repository, accumulator, mapper, { pacing }, clock)

    private fun rpc(outcome: ApiCallOutcome, bytes: Long = 0, captureId: String? = null) =
        ApiCall(ApiProvider.SOLANA_RPC, "getTransaction", ApiUsageContext(ApiTask.WALLET_STEP, captureId), outcome, bytes)

    private fun entity(id: String) = ApiUsageBucketEntity(id, Instant.parse("2026-10-16T20:10:00Z"), ApiProvider.SOLANA_RPC, "getSlot",
        ApiTask.CONNECTION_CHECK, "", 1, 0, 0, 0, 0, NOW)

    private class SettableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T20:13:07Z")
    }
}
