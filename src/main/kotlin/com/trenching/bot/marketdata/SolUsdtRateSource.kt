package com.trenching.bot.marketdata

import com.trenching.bot.apiusage.ApiUsageContext
import java.math.BigDecimal
import java.time.Instant

/**
 * Read-only SOL/USDT reference rates for display. They never enter simulation, datasets or checksums. A rate that cannot
 * be had is [RateLookup.Unavailable] with its reason; nothing is interpolated or substituted.
 */
interface SolUsdtRateSource {
    /** The rate of each requested minute start (UTC, zero seconds); every requested minute is a key of the result. */
    fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup>

    /** [minuteRates], with any request it sends counted as API usage under [usage]. */
    fun minuteRates(minutes: Set<Instant>, usage: ApiUsageContext): Map<Instant, RateLookup> = minuteRates(minutes)

    /** The latest SOL/USDT price. */
    fun current(): RateLookup
}

sealed interface RateLookup {
    /** [rate] is USDT per SOL, exactly as the source quoted it; [at] is the minute start, or the fetch time of a current price. */
    data class Available(val rate: BigDecimal, val at: Instant, val source: String) : RateLookup

    data class Unavailable(val reason: RateUnavailableReason) : RateLookup
}

enum class RateUnavailableReason {
    NOT_CONFIGURED,
    OUT_OF_RANGE,
    NO_MARKET_DATA,
    RATE_LIMITED,
    REGION_BLOCKED,
    TIMEOUT,
    UNAVAILABLE,
    MALFORMED,
    REQUEST_LIMIT,
}
