package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.application.ReferenceRateCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.ReferenceRateArtifactStore
import com.trenching.bot.replay.ReplayMarketFixtures
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class ReferenceRateCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = ReferenceRateArtifactStore(mapper)
    private val requested = mutableListOf<Set<Instant>>()
    private var available = setOf(FIRST, SECOND)
    private var tradeTimes = listOf(FIRST.plusSeconds(5), FIRST.plusSeconds(40), SECOND.plusSeconds(1))

    @Test
    fun `records one rate per minute in which the market capture has a trade`() {
        val result = service().capture(request())

        assertEquals(listOf(setOf(FIRST, SECOND)), requested)
        assertTrue(result.manifest.complete)
        assertEquals(listOf(FIRST.toString() to "200", SECOND.toString() to "201"), result.manifest.records.map { it.minute to it.rate })
        assertEquals(result.manifest, store.inspect(rates()).manifest)
        assertEquals(listOf(ReferenceRate(FIRST, BigDecimal("200"), SOURCE), ReferenceRate(SECOND, BigDecimal("201"), SOURCE)),
            service().rates(rates()))
    }

    @Test
    fun `keeps an unavailable minute with its reason and asks only for it on the next run`() {
        available = setOf(FIRST)
        val first = service().capture(request())
        assertEquals(false, first.manifest.complete)
        assertEquals(listOf(null, "RATE_LIMITED"), first.manifest.records.map { it.unavailableReason })
        assertCategory("REFERENCE_RATES_INCOMPLETE") { service().rates(rates()) }

        available = setOf(FIRST, SECOND)
        val second = service().capture(request())

        assertEquals(listOf(setOf(FIRST, SECOND), setOf(SECOND)), requested)
        assertTrue(second.manifest.complete)
        assertEquals(first.manifest.capturedAt, second.manifest.capturedAt)
        service().capture(request())
        assertEquals(2, requested.size)
    }

    @Test
    fun `refuses another market capture's minutes, the market directory itself and a capture without trades`() {
        service().capture(request())
        tradeTimes = listOf(FIRST.plusSeconds(5))
        assertCategory("RESUME_IDENTITY") { service().capture(request()) }
        assertCategory("SAME_DIRECTORY") { service().capture(ReferenceRateCaptureRequest(markets(), markets())) }
        tradeTimes = emptyList()
        assertCategory("NO_OBSERVATIONS") { service().capture(ReferenceRateCaptureRequest(markets(), directory.resolve("empty"))) }
        assertEquals(1, requested.size)
    }

    @Test
    fun `refuses forged or inconsistent manifests`() {
        val valid = service().capture(request()).manifest
        val forgeries = mapOf(
            "complete with a missing rate" to (valid.copy(records = listOf(valid.records[0], valid.records[1].copy(rate = null, source = null,
                unavailableReason = "TIMEOUT"))) to "COMPLETE_STATUS"),
            "minutes out of order" to (valid.copy(records = valid.records.reversed()) to "RECORD_ORDER"),
            "a rate without a source" to (valid.copy(records = listOf(valid.records[0].copy(source = null), valid.records[1])) to "RECORD_STATE"),
            "a minute that is not whole" to (valid.copy(records = listOf(valid.records[0].copy(minute = FIRST.plusSeconds(1).toString()),
                valid.records[1])) to "RECORD_VALUE"),
            "a non-positive rate" to (valid.copy(records = listOf(valid.records[0].copy(rate = "0"), valid.records[1])) to "RECORD_VALUE"),
        )
        forgeries.forEach { (name, forged) ->
            store.save(rates(), forged.first)
            val failure = assertFailsWith<CaptureValidationException>(name) { store.inspect(rates()) }
            assertEquals(forged.second, failure.category, name)
        }
    }

    private fun service() = ReferenceRateCaptureService(source, markets, store, Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC))

    private val source = object : SolUsdtRateSource {
        override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> {
            requested += minutes
            return minutes.associateWith { minute ->
                if (minute in available) RateLookup.Available(if (minute == FIRST) BigDecimal("200") else BigDecimal("201"), minute, SOURCE)
                else RateLookup.Unavailable(RateUnavailableReason.RATE_LIMITED)
            }
        }

        override fun current(): RateLookup = error("Rate capture never asks for the current price")
    }

    private val markets = object : RecordedMarketHistory {
        override fun inspect(directory: Path): MarketHistoryCaptureResult = error("Rate capture decodes the market capture")

        override fun decode(directory: Path): MarketHistoryDecodeResult {
            val window = MarketCaptureWindow.of(MintAddress.of("11111111111111111111111111111112"), Slot.of(1001), Slot.of(5500),
                TransactionSignature.of("1".repeat(64)))
            val decoded = ReplayMarketFixtures.decoded(directory, ReplayMarketFixtures.manifest(listOf(window)), tradeTimes.size)
            return decoded.copy(observations = decoded.observations.zip(tradeTimes) { observation, time -> observation.copy(observedTime = time) })
        }
    }

    private fun request() = ReferenceRateCaptureRequest(markets(), rates())

    private fun markets(): Path = directory.resolve("markets")

    private fun rates(): Path = directory.resolve("rates")

    private fun assertCategory(category: String, action: () -> Unit) {
        assertEquals(category, assertFailsWith<CaptureValidationException> { action() }.category)
    }

    private companion object {
        val FIRST: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val SECOND: Instant = Instant.parse("2026-10-01T00:01:00Z")
        const val SOURCE = "binance:SOLUSDT:1m:open"
    }
}
