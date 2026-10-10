package com.trenching.bot.protocol.pumpfun

import java.math.BigInteger

/** A bonding curve's reserves in raw units: lamports for SOL, raw token units for the token. Each is a u64. */
data class CurveReserves(
    val virtualSol: BigInteger,
    val virtualToken: BigInteger,
    val realSol: BigInteger,
    val realToken: BigInteger,
) {
    init {
        require(isValid(virtualSol, virtualToken, realSol, realToken)) {
            "Invalid bonding-curve reserves virtualSol=$virtualSol virtualToken=$virtualToken realSol=$realSol realToken=$realToken"
        }
    }

    companion object {
        private val MAX_U64: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)

        /** Each side's virtual reserve is a positive u64, and its real reserve lies between zero and the virtual one. */
        fun isValid(virtualSol: BigInteger, virtualToken: BigInteger, realSol: BigInteger, realToken: BigInteger): Boolean =
            isValidSide(virtualSol, realSol) && isValidSide(virtualToken, realToken)

        private fun isValidSide(virtual: BigInteger, real: BigInteger): Boolean =
            virtual.signum() > 0 && virtual <= MAX_U64 && real.signum() >= 0 && real <= virtual
    }
}
