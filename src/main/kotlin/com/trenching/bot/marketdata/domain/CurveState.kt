package com.trenching.bot.marketdata.domain

import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.CurveReserves
import java.math.BigInteger

/** A trade's bonding curve after it executed, and the protocol and creator fee basis points the program charged on it. */
data class CurveState(val reserves: CurveReserves, val feeBps: Int, val creatorFeeBps: Int) {
    init {
        require(isValidBasisPoints(feeBps) && isValidBasisPoints(creatorFeeBps)) {
            "Fee basis points out of range feeBps=$feeBps creatorFeeBps=$creatorFeeBps"
        }
    }

    /** Whether the curve has sold all its real tokens, which migrates the token off it. */
    val complete: Boolean get() = reserves.realToken.signum() == 0

    companion object {
        fun isValidBasisPoints(basisPoints: Int): Boolean = basisPoints in 0..BondingCurve.MAX_BASIS_POINTS

        /** [raw] as basis points, or null when it is outside 0..10,000. */
        fun basisPoints(raw: BigInteger): Int? =
            raw.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()?.takeIf(::isValidBasisPoints)
    }
}
