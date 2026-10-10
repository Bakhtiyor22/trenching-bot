package com.trenching.bot.marketdata

import java.math.BigDecimal
import java.time.Instant

/** Recorded SOL/USDT rates a run reads; never the network. */
interface ReferenceRates {
    /** The rate of the UTC minute containing [time], or null when none was recorded. */
    fun rateAt(time: Instant): BigDecimal?
}
