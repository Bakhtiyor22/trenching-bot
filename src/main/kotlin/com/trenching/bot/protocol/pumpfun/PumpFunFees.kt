package com.trenching.bot.protocol.pumpfun

import java.math.BigInteger

/** The Pump.fun program's fee on a SOL amount: [amount] times the basis points over 10,000, rounded up to a whole lamport. */
object PumpFunFees {
    private val BASIS_POINTS_DENOMINATOR: BigInteger = BigInteger.valueOf(BondingCurve.MAX_BASIS_POINTS.toLong())

    fun lamports(amount: BigInteger, bps: Int): BigInteger = lamports(amount, BigInteger.valueOf(bps.toLong()))

    /** [bps] as a decoded event records it, unbounded. */
    fun lamports(amount: BigInteger, bps: BigInteger): BigInteger {
        require(amount.signum() >= 0 && bps.signum() >= 0) { "Negative fee input amount=$amount bps=$bps" }
        return amount.multiply(bps).add(BASIS_POINTS_DENOMINATOR).subtract(BigInteger.ONE).divide(BASIS_POINTS_DENOMINATOR)
    }
}
