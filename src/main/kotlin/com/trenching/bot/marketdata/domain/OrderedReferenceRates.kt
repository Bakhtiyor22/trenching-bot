package com.trenching.bot.marketdata.domain

import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRates
import java.math.BigDecimal
import java.time.Instant

/** Recorded SOL/USDT rates by minute; one rate per minute, in minute order. */
class OrderedReferenceRates(rates: List<ReferenceRate>) : ReferenceRates {
    private val byMinute: Map<Instant, ReferenceRate> = canonical(rates).associateBy { it.minute }

    override fun rateAt(time: Instant): BigDecimal? = byMinute[ReferenceRate.minuteOf(time)]?.rate

    companion object {
        fun canonical(rates: List<ReferenceRate>): List<ReferenceRate> =
            rates.groupBy { it.minute }.map { (minute, duplicates) ->
                require(duplicates.map { it.copy(rate = it.rate.stripTrailingZeros()) }.distinct().size == 1) {
                    "Conflicting reference rates minute=$minute"
                }
                duplicates.first()
            }.sortedBy { it.minute }
    }
}
