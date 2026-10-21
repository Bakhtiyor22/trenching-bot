package com.trenching.bot.console.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.domain.MarketObservation
import java.math.BigDecimal
import java.time.Duration

/**
 * How long a slot took while a run's market was recorded, from the observations' block times. Mainnet's slots run faster
 * than the nominal 400 ms, so a horizon in slots is converted to time with this whenever a run has it.
 */
object SlotPace {
    /** Block times are whole seconds, so a span of at least this many slots keeps the pace within about 2 ms. */
    const val MIN_SLOTS = 500L
    private const val MILLIS_SCALE = 1

    /** Milliseconds per slot, to a tenth, from the earliest to the latest observed slot; null over fewer than [MIN_SLOTS]. */
    fun millisPerSlot(observations: List<MarketObservation>): Double? {
        val first = observations.minByOrNull { it.slot.value } ?: return null
        val last = observations.maxBy { it.slot.value }
        val slots = last.slot.value - first.slot.value
        val millis = Duration.between(first.observedTime, last.observedTime).toMillis()
        if (slots < MIN_SLOTS || millis <= 0) return null
        return BigDecimal(millis).divide(BigDecimal(slots), MILLIS_SCALE, DecimalPolicy.ROUNDING).toDouble()
    }
}
