package com.trenching.bot.console.domain

import java.math.BigDecimal

sealed interface PercentToBps {
    data class Converted(val bps: Int) : PercentToBps
    /** Not a plain decimal with at most two decimals, such as `"20"` or `"15.5"`. */
    data object Malformed : PercentToBps
    data class OutOfRange(val range: NumberRange) : PercentToBps
}

/**
 * The one exact conversion between basis points and percent strings: a percent with at most two decimals is a whole
 * number of basis points, so neither direction ever rounds.
 */
object PercentConversion {
    private const val BPS_PER_PERCENT_DIGITS = 2
    private val PERCENT = Regex("[0-9]+(\\.[0-9]{1,2})?")

    fun toBps(percent: String, range: NumberRange): PercentToBps {
        if (!PERCENT.matches(percent)) return PercentToBps.Malformed
        val bps = BigDecimal(percent).movePointRight(BPS_PER_PERCENT_DIGITS)
        val max = range.max
        if (bps < BigDecimal.valueOf(range.min) || (max != null && bps > BigDecimal.valueOf(max))) return PercentToBps.OutOfRange(range)
        return PercentToBps.Converted(bps.intValueExact())
    }

    fun toPercent(bps: Long): String = BigDecimal.valueOf(bps).movePointLeft(BPS_PER_PERCENT_DIGITS).stripTrailingZeros().toPlainString()
}
