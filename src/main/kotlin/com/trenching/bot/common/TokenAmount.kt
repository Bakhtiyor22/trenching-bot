package com.trenching.bot.common

import java.math.BigDecimal
import java.math.BigInteger

@ConsistentCopyVisibility
data class TokenAmount private constructor(val raw: BigInteger, val decimals: Int) {
    fun quantity(): BigDecimal = DecimalPolicy.scale(BigDecimal(raw, decimals))

    companion object {
        private val MAX_RAW = BigInteger("18446744073709551615")
        fun of(raw: BigInteger, decimals: Int): TokenAmount {
            require(raw.signum() >= 0 && raw <= MAX_RAW) { "Raw amount is outside u64 range" }
            require(decimals in 0..255) { "Invalid mint decimals" }
            return TokenAmount(raw, decimals)
        }
    }
}
