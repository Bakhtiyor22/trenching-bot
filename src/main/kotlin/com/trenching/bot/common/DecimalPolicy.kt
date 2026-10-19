package com.trenching.bot.common

import java.math.BigDecimal
import java.math.RoundingMode

object DecimalPolicy {
    /** Scale of quote amounts and token quantities. */
    const val SCALE = 12
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
    val BASIS_POINTS: BigDecimal = BigDecimal("10000")
    const val STORAGE_PRECISION = 38

    /**
     * Prices keep the full observed storage scale: memecoin prices are often 1e-8..1e-11
     * quote per token, which scale 12 would cut to one to four significant digits.
     */
    const val PRICE_SCALE = 38
    const val PRICE_PRECISION = 78

    fun requireExact(value: BigDecimal, precision: Int = STORAGE_PRECISION, scale: Int = SCALE) {
        val stored = value.setScale(scale, RoundingMode.UNNECESSARY)
        require(stored.precision() <= precision) { "Decimal exceeds exact storage precision value=$value" }
    }
    fun requireExactPrice(value: BigDecimal) = requireExact(value, PRICE_PRECISION, PRICE_SCALE)
    fun scale(value: BigDecimal): BigDecimal = value.setScale(SCALE, ROUNDING)
    fun price(value: BigDecimal): BigDecimal = value.setScale(PRICE_SCALE, ROUNDING)
    fun fraction(basisPoints: Int): BigDecimal =
        BigDecimal(basisPoints).divide(BASIS_POINTS, SCALE, ROUNDING)

    /** The price a swap was made at: [quote] per whole unit of [base], at the price scale. */
    fun swapPrice(quote: TokenAmount, base: TokenAmount): BigDecimal =
        BigDecimal(quote.raw, quote.decimals).divide(BigDecimal(base.raw, base.decimals), PRICE_SCALE, ROUNDING)
}
