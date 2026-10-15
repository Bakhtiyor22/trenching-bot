package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.Base58
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigInteger

enum class TradeSide { BUY, SELL }

/**
 * The trade behind a Pump.fun observation: the [trader] that signed it (the `TradeEvent` user, never the fee payer or a
 * router), its [side], and the lamports and raw token units that changed hands. Both amounts are positive u64 values.
 */
@ConsistentCopyVisibility
data class ObservedTrade private constructor(
    val trader: String,
    val side: TradeSide,
    val solLamports: BigInteger,
    val tokenRaw: BigInteger,
) {
    companion object {
        /** A base58 public key of 32 bytes never needs more characters than this. */
        const val MAX_TRADER_LENGTH = 44
        private val MAX_RAW: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)

        fun of(trader: String, side: TradeSide, solLamports: BigInteger, tokenRaw: BigInteger): ObservedTrade {
            require(trader.length <= MAX_TRADER_LENGTH && Base58.decode(trader)?.size == PumpFunLayout.PUBLIC_KEY_BYTES) {
                "Invalid trade trader=${trader.take(MAX_TRADER_LENGTH + 1)}"
            }
            require(isPositiveU64(solLamports)) { "Invalid trade solLamports=$solLamports" }
            require(isPositiveU64(tokenRaw)) { "Invalid trade tokenRaw=$tokenRaw" }
            return ObservedTrade(trader, side, solLamports, tokenRaw)
        }

        private fun isPositiveU64(value: BigInteger): Boolean = value.signum() > 0 && value <= MAX_RAW
    }
}
