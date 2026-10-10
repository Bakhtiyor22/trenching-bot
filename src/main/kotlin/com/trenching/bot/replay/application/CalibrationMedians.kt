package com.trenching.bot.replay.application

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Medians of the calibration report, from values sorted ascending: the middle value for an odd count, the mean of the
 * middle two for an even count, rounded HALF_EVEN. Null for no values.
 */
object CalibrationMedians {
    const val BASIS_POINTS_SCALE = 2
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
    private val TWO: BigDecimal = BigDecimal.valueOf(2)

    /** At scale [BASIS_POINTS_SCALE]. */
    fun basisPoints(values: List<BigDecimal>): BigDecimal? = median(values, BASIS_POINTS_SCALE)

    /** Exact lamport median; an even count can produce half a lamport. */
    fun lamports(values: List<BigInteger>): BigDecimal? = median(values.map(::BigDecimal), 1)?.stripTrailingZeros()

    private fun median(values: List<BigDecimal>, scale: Int): BigDecimal? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].setScale(scale, ROUNDING)
        else sorted[middle - 1].add(sorted[middle]).divide(TWO, scale, ROUNDING)
    }
}
