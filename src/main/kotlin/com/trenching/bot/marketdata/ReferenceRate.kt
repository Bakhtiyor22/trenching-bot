package com.trenching.bot.marketdata

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.ReplayLimits
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A recorded SOL/USDT rate for one UTC minute, the 1-minute open of [source]. Replay datasets carry these so the
 * market-cap rule never needs the network and a rerun always sees the same rate.
 */
data class ReferenceRate(val minute: Instant, val rate: BigDecimal, val source: String) {
    init {
        require(minute == minute.truncatedTo(ChronoUnit.MINUTES)) { "A reference rate minute starts on a whole minute minute=$minute" }
        require(rate.signum() > 0) { "A reference rate is positive minute=$minute" }
        DecimalPolicy.requireExactPrice(rate)
        require(source.isNotBlank() && source.length <= ReplayLimits.MAX_SOURCE_LENGTH) { "Invalid reference rate source" }
    }

    companion object {
        fun minuteOf(time: Instant): Instant = time.truncatedTo(ChronoUnit.MINUTES)
    }
}
