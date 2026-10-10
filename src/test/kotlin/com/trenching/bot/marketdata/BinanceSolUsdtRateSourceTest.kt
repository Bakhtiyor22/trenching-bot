package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.infrastructure.rates.BinanceSolUsdtRateSource
import com.trenching.bot.marketdata.infrastructure.rates.DisabledSolUsdtRateSource
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

class BinanceSolUsdtRateSourceTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val clock = MutableClock(NOW)

    @AfterEach
    fun verify() = server.verify()

    @Test
    fun `converts each requested minute to the exact open price of its kline with one request per contiguous range`() {
        expectKlines(FIRST, FIRST.plusSeconds(60), klines(FIRST to "230.12000000", FIRST.plusSeconds(60) to "231.50000000"))

        val rates = source().minuteRates(setOf(FIRST, FIRST.plusSeconds(60)))

        assertEquals(RateLookup.Available(BigDecimal("230.12000000"), FIRST, KLINE_SOURCE), rates[FIRST])
        assertEquals(RateLookup.Available(BigDecimal("231.50000000"), FIRST.plusSeconds(60), KLINE_SOURCE), rates[FIRST.plusSeconds(60)])
    }

    @Test
    fun `splits minutes a thousand minutes apart into separate requests and stops at the request cap`() {
        val second = FIRST.plus(Duration.ofMinutes(1000))
        val third = FIRST.plus(Duration.ofMinutes(2000))
        expectKlines(FIRST, FIRST, klines(FIRST to "230"))
        expectKlines(second, second, klines(second to "240"))

        val rates = source(maxRequests = 2).minuteRates(setOf(third, second, FIRST))

        assertEquals(BigDecimal("230"), (rates[FIRST] as RateLookup.Available).rate)
        assertEquals(BigDecimal("240"), (rates[second] as RateLookup.Available).rate)
        assertEquals(RateLookup.Unavailable(RateUnavailableReason.REQUEST_LIMIT), rates[third])
    }

    @Test
    fun `minutes up to 999 apart share one request`() {
        val last = FIRST.plus(Duration.ofMinutes(999))
        expectKlines(FIRST, last, klines(FIRST to "230", last to "240"))

        val rates = source().minuteRates(setOf(FIRST, last))

        assertEquals(BigDecimal("240"), (rates[last] as RateLookup.Available).rate)
    }

    @Test
    fun `evicts the oldest cached minute beyond the cache bound`() {
        val second = FIRST.plusSeconds(60)
        expectKlines(FIRST, FIRST, klines(FIRST to "230"))
        expectKlines(second, second, klines(second to "231"))
        expectKlines(FIRST, FIRST, klines(FIRST to "230"))
        val source = source(maxCachedMinutes = 1)

        source.minuteRates(setOf(FIRST))
        source.minuteRates(setOf(second))
        source.minuteRates(setOf(FIRST))
    }

    @Test
    fun `after a rate limit it answers with that reason without calling Binance until the backoff passes`() {
        server.expect(ExpectedCount.once(), requestTo(klinesUrl(FIRST, FIRST))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(ExpectedCount.once(), requestTo(klinesUrl(FIRST, FIRST)))
            .andRespond(withSuccess(klines(FIRST to "230"), MediaType.APPLICATION_JSON))
        val source = source()

        source.minuteRates(setOf(FIRST))
        val blocked = source.minuteRates(setOf(FIRST))
        val ticker = source.current()
        clock.advance(Duration.ofSeconds(31))
        val recovered = source.minuteRates(setOf(FIRST))

        assertEquals(RateLookup.Unavailable(RateUnavailableReason.RATE_LIMITED), blocked[FIRST])
        assertEquals(RateLookup.Unavailable(RateUnavailableReason.RATE_LIMITED), ticker)
        assertEquals(BigDecimal("230"), (recovered[FIRST] as RateLookup.Available).rate)
    }

    @Test
    fun `answers completed minutes from the cache and never caches the current minute`() {
        val current = NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        expectKlines(FIRST, FIRST, klines(FIRST to "230"))
        expectKlines(current, current, klines(current to "250"))
        expectKlines(current, current, klines(current to "251"))
        val source = source()

        source.minuteRates(setOf(FIRST))
        source.minuteRates(setOf(current))
        val again = source.minuteRates(setOf(FIRST, current))

        assertEquals(BigDecimal("230"), (again[FIRST] as RateLookup.Available).rate)
        assertEquals(BigDecimal("251"), (again[current] as RateLookup.Available).rate)
    }

    @Test
    fun `minutes before the SOLUSDT history or after the current minute are out of range without a request`() {
        val rates = source().minuteRates(setOf(Instant.parse("2020-08-10T23:59:00Z"), NOW.plus(Duration.ofMinutes(2)).truncatedTo(
            java.time.temporal.ChronoUnit.MINUTES)))
        assertEquals(setOf(RateLookup.Unavailable(RateUnavailableReason.OUT_OF_RANGE)), rates.values.toSet())
        assertEquals(2, rates.size)
    }

    @Test
    fun `a minute missing from the answer has no market data and is never filled from a neighbour`() {
        val gap = FIRST.plusSeconds(60)
        val last = FIRST.plusSeconds(120)
        expectKlines(FIRST, last, klines(FIRST to "230", last to "232"))

        val rates = source().minuteRates(setOf(FIRST, gap, last))

        assertEquals(RateLookup.Unavailable(RateUnavailableReason.NO_MARKET_DATA), rates[gap])
    }

    @Test
    fun `maps rate limits region blocks timeouts server errors and malformed answers to categories`() {
        val cases = listOf(
            HttpStatus.TOO_MANY_REQUESTS to RateUnavailableReason.RATE_LIMITED,
            HttpStatus.I_AM_A_TEAPOT to RateUnavailableReason.RATE_LIMITED,
            HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS to RateUnavailableReason.REGION_BLOCKED,
            HttpStatus.FORBIDDEN to RateUnavailableReason.REGION_BLOCKED,
            HttpStatus.INTERNAL_SERVER_ERROR to RateUnavailableReason.UNAVAILABLE,
        )
        cases.forEach { (status, _) -> server.expect(requestTo(klinesUrl(FIRST, FIRST))).andRespond(withStatus(status)) }
        server.expect(requestTo(klinesUrl(FIRST, FIRST))).andRespond(withException(SocketTimeoutException()))
        listOf("{}", "[[${FIRST.toEpochMilli()},\"0\"]]", "[[${FIRST.toEpochMilli() + 1},\"230\"]]", "[[${FIRST.toEpochMilli()},\"abc\"]]")
            .forEach { body -> server.expect(requestTo(klinesUrl(FIRST, FIRST))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)) }
        val expected = cases.map { it.second } + RateUnavailableReason.TIMEOUT + List(4) { RateUnavailableReason.MALFORMED }

        val actual = expected.map { source().minuteRates(setOf(FIRST))[FIRST] }

        assertEquals(expected.map { RateLookup.Unavailable(it) }, actual)
    }

    @Test
    fun `a failed range gives its reason to every remaining range without further requests`() {
        val second = FIRST.plus(Duration.ofMinutes(1000))
        server.expect(ExpectedCount.once(), requestTo(klinesUrl(FIRST, FIRST))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val rates = source().minuteRates(setOf(FIRST, second))

        assertEquals(List(2) { RateLookup.Unavailable(RateUnavailableReason.RATE_LIMITED) }, listOf(rates[FIRST], rates[second]))
    }

    @Test
    fun `the current price comes from the ticker and is reused until its cache time passes`() {
        server.expect(ExpectedCount.twice(), requestTo("$BASE/api/v3/ticker/price?symbol=SOLUSDT"))
            .andRespond(withSuccess("""{"symbol":"SOLUSDT","price":"212.34000000"}""", MediaType.APPLICATION_JSON))
        val source = source()

        val first = source.current()
        clock.advance(Duration.ofSeconds(29))
        val cached = source.current()
        clock.advance(Duration.ofSeconds(2))
        source.current()

        assertEquals(RateLookup.Available(BigDecimal("212.34000000"), NOW, TICKER_SOURCE), first)
        assertEquals(first, cached)
    }

    @Test
    fun `refuses a requested minute that is not a minute start`() {
        assertFailsWith<IllegalArgumentException> { source().minuteRates(setOf(FIRST.plusSeconds(1))) }
    }

    @Test
    fun `the disabled source reports every rate as not configured`() {
        val disabled = DisabledSolUsdtRateSource()
        assertEquals(mapOf(FIRST to RateLookup.Unavailable(RateUnavailableReason.NOT_CONFIGURED)), disabled.minuteRates(setOf(FIRST)))
        assertEquals(RateLookup.Unavailable(RateUnavailableReason.NOT_CONFIGURED), disabled.current())
    }

    private fun source(maxRequests: Int = 20, maxCachedMinutes: Int = 100_000) = BinanceSolUsdtRateSource(builder.build(),
        ReferenceRateProperties(enabled = true, baseUrl = BASE, maxRequestsPerLookup = maxRequests, maxCachedMinutes = maxCachedMinutes),
        JsonMapper.builder().build(), clock)

    private fun expectKlines(from: Instant, to: Instant, body: String) {
        server.expect(requestTo(klinesUrl(from, to))).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun klinesUrl(from: Instant, to: Instant) =
        "$BASE/api/v3/klines?symbol=SOLUSDT&interval=1m&startTime=${from.toEpochMilli()}&endTime=${to.toEpochMilli() + 59_999}&limit=1000"

    /** Binance kline rows: open time, open, high, low, close, volume, close time, then fields the adapter ignores. */
    private fun klines(vararg rows: Pair<Instant, String>): String = rows.joinToString(",", "[", "]") { (minute, open) ->
        val start = minute.toEpochMilli()
        """[$start,"$open","999.00000000","1.00000000","$open","10.0",${start + 59_999},"0",1,"0","0","0"]"""
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val BASE = "https://binance.test"
        const val KLINE_SOURCE = "binance:SOLUSDT:1m:open"
        const val TICKER_SOURCE = "binance:SOLUSDT:ticker"
        val NOW: Instant = Instant.parse("2026-10-05T12:00:30Z")
        val FIRST: Instant = Instant.parse("2026-10-03T17:47:00Z")
    }
}
