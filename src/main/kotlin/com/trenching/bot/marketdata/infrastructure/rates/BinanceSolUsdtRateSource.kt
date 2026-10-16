package com.trenching.bot.marketdata.infrastructure.rates

import com.trenching.bot.common.ExceptionCauses
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.ReferenceRateProperties
import com.trenching.bot.marketdata.SolUsdtRateSource
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * SOL/USDT from Binance's public spot API: the open price of the 1-minute kline containing each requested minute, and
 * the ticker for the current price. One GET at a time, no retries, no key. Completed minutes are cached in memory;
 * failures become [RateUnavailableReason]s whose text never carries the URL. After a rate limit or region block, every
 * lookup answers with that reason for `tickerCacheTtl` without calling Binance, which bans IPs that keep calling after a 429.
 */
class BinanceSolUsdtRateSource(
    private val restClient: RestClient,
    private val properties: ReferenceRateProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : SolUsdtRateSource {
    private val log = LoggerFactory.getLogger(BinanceSolUsdtRateSource::class.java)
    private val cache = object : LinkedHashMap<Instant, BigDecimal>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Instant, BigDecimal>?): Boolean = size > properties.maxCachedMinutes
    }
    @Volatile private var ticker: RateLookup.Available? = null
    @Volatile private var backoff: Backoff? = null

    override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> {
        minutes.forEach { require(it == it.truncatedTo(ChronoUnit.MINUTES)) { "Rate minute must be a minute start minute=$it" } }
        val currentMinute = clock.instant().truncatedTo(ChronoUnit.MINUTES)
        val result = mutableMapOf<Instant, RateLookup>()
        val missing = mutableListOf<Instant>()
        minutes.forEach { minute ->
            val cached = synchronized(cache) { cache[minute] }
            when {
                minute < FIRST_MINUTE || minute > currentMinute -> result[minute] = RateLookup.Unavailable(RateUnavailableReason.OUT_OF_RANGE)
                cached != null -> result[minute] = RateLookup.Available(cached, minute, KLINE_SOURCE)
                else -> missing += minute
            }
        }
        var requests = 0
        var failure: RateUnavailableReason? = activeBackoff()
        for (range in ranges(missing.sorted())) {
            val stop = failure ?: RateUnavailableReason.REQUEST_LIMIT.takeIf { requests >= properties.maxRequestsPerLookup }
            if (stop != null) {
                range.forEach { result[it] = RateLookup.Unavailable(stop) }
                continue
            }
            requests++
            when (val fetched = klines(range.first(), range.last())) {
                is Fetch.Failed -> {
                    failure = fetched.reason
                    range.forEach { result[it] = RateLookup.Unavailable(fetched.reason) }
                    log.warn("reference_rates klines failed reason={} cause={} requests={}", fetched.reason, fetched.cause, requests)
                    startBackoff(fetched.reason)
                }
                is Fetch.Rates -> range.forEach { minute ->
                    val rate = fetched.opens[minute]
                    result[minute] = if (rate == null) RateLookup.Unavailable(RateUnavailableReason.NO_MARKET_DATA)
                    else RateLookup.Available(rate, minute, KLINE_SOURCE)
                    if (rate != null && minute < currentMinute) synchronized(cache) { cache[minute] = rate }
                }
            }
        }
        log.debug("reference_rates klines minutes={} requests={}", minutes.size, requests)
        return result
    }

    override fun current(): RateLookup {
        val now = clock.instant()
        ticker?.takeIf { Duration.between(it.at, now) < properties.tickerCacheTtl }?.let { return it }
        activeBackoff()?.let { return RateLookup.Unavailable(it) }
        val uri = UriComponentsBuilder.fromUriString(properties.baseUrl).path(TICKER_PATH).queryParam("symbol", SYMBOL).build().toUri()
        return when (val fetched = get(uri) { body -> positive(body.path("price"))?.let { Fetch.Rates(mapOf(now to it)) } }) {
            is Fetch.Failed -> {
                log.warn("reference_rates ticker failed reason={} cause={}", fetched.reason, fetched.cause)
                startBackoff(fetched.reason)
                RateLookup.Unavailable(fetched.reason)
            }
            is Fetch.Rates -> RateLookup.Available(fetched.opens.getValue(now), now, TICKER_SOURCE).also { ticker = it }
        }
    }

    private fun activeBackoff(): RateUnavailableReason? = backoff?.takeIf { clock.instant() < it.until }?.reason

    private fun startBackoff(reason: RateUnavailableReason) {
        if (reason in BACKOFF_REASONS) backoff = Backoff(reason, clock.instant().plus(properties.tickerCacheTtl))
    }

    /** Sorted minutes grouped so each group spans at most [KLINE_LIMIT] minutes: one klines request each. */
    private fun ranges(sorted: List<Instant>): List<List<Instant>> {
        val ranges = mutableListOf<MutableList<Instant>>()
        sorted.forEach { minute ->
            val open = ranges.lastOrNull()
            if (open != null && Duration.between(open.first(), minute).toMinutes() < KLINE_LIMIT) open += minute else ranges += mutableListOf(minute)
        }
        return ranges
    }

    private fun klines(from: Instant, to: Instant): Fetch {
        val uri = UriComponentsBuilder.fromUriString(properties.baseUrl).path(KLINES_PATH)
            .queryParam("symbol", SYMBOL).queryParam("interval", INTERVAL)
            .queryParam("startTime", from.toEpochMilli()).queryParam("endTime", to.toEpochMilli() + MINUTE_MILLIS - 1)
            .queryParam("limit", KLINE_LIMIT).build().toUri()
        return get(uri, ::opens)
    }

    /** Open time and open price of each kline row, or null when any row is not a minute-aligned positive price. */
    private fun opens(body: JsonNode): Fetch.Rates? {
        if (!body.isArray) return null
        val opens = mutableMapOf<Instant, BigDecimal>()
        for (row in body) {
            val openTime = row.path(0)
            if (!openTime.isIntegralNumber || openTime.asLong() % MINUTE_MILLIS != 0L) return null
            opens[Instant.ofEpochMilli(openTime.asLong())] = positive(row.path(1)) ?: return null
        }
        return Fetch.Rates(opens)
    }

    private fun positive(node: JsonNode): BigDecimal? =
        node.takeIf { it.isString }?.asString()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private fun get(uri: URI, parse: (JsonNode) -> Fetch.Rates?): Fetch = try {
        restClient.get().uri(uri).exchange { _, response ->
            val status = response.statusCode
            when {
                status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) || status.isSameCodeAs(HttpStatus.I_AM_A_TEAPOT) ->
                    Fetch.Failed(RateUnavailableReason.RATE_LIMITED)
                status.isSameCodeAs(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS) || status.isSameCodeAs(HttpStatus.FORBIDDEN) ->
                    Fetch.Failed(RateUnavailableReason.REGION_BLOCKED)
                !status.is2xxSuccessful -> Fetch.Failed(RateUnavailableReason.UNAVAILABLE)
                else -> parse(mapper.readTree(response.bodyTo(String::class.java).orEmpty())) ?: Fetch.Failed(RateUnavailableReason.MALFORMED)
            }
        }
    } catch (_: JacksonException) {
        Fetch.Failed(RateUnavailableReason.MALFORMED)
    } catch (exception: RestClientException) {
        Fetch.Failed(classify(exception), ExceptionCauses.rootName(exception))
    }

    private fun classify(exception: RestClientException): RateUnavailableReason {
        val causes = ExceptionCauses.chain(exception)
        return when {
            causes.any { it is HttpTimeoutException || it is SocketTimeoutException || it is TimeoutException } -> RateUnavailableReason.TIMEOUT
            causes.any { it is JacksonException } -> RateUnavailableReason.MALFORMED
            else -> RateUnavailableReason.UNAVAILABLE
        }
    }

    private sealed interface Fetch {
        data class Rates(val opens: Map<Instant, BigDecimal>) : Fetch

        /** [cause] is the innermost exception's class name for diagnosis; its message is never kept, because it quotes the URL. */
        data class Failed(val reason: RateUnavailableReason, val cause: String? = null) : Fetch
    }

    private data class Backoff(val reason: RateUnavailableReason, val until: Instant)

    private companion object {
        const val SYMBOL = "SOLUSDT"
        const val INTERVAL = "1m"
        const val KLINES_PATH = "/api/v3/klines"
        const val TICKER_PATH = "/api/v3/ticker/price"
        const val KLINE_LIMIT = 1000
        const val MINUTE_MILLIS = 60_000L
        const val KLINE_SOURCE = "binance:SOLUSDT:1m:open"
        const val TICKER_SOURCE = "binance:SOLUSDT:ticker"
        val BACKOFF_REASONS = setOf(RateUnavailableReason.RATE_LIMITED, RateUnavailableReason.REGION_BLOCKED)

        /** Binance listed SOLUSDT on 2020-08-11; earlier minutes cannot have a rate. */
        val FIRST_MINUTE: Instant = Instant.parse("2020-08-11T00:00:00Z")
    }
}
