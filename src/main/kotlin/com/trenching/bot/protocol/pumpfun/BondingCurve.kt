package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.DecimalPolicy
import java.math.BigDecimal
import java.math.BigInteger

data class CurveBuy(val tokensOut: BigInteger)

data class CurveSell(val lamportsOut: BigInteger)

/**
 * Pump.fun's constant-product bonding curve in raw integer units, rounding outputs down and fees up like the program.
 * The only place curve arithmetic exists, per the bonding-curve-execution plan.
 */
object BondingCurve {
    const val MAX_BASIS_POINTS = 10_000
    private val BASIS_POINTS_DENOMINATOR: BigInteger = BigInteger.valueOf(MAX_BASIS_POINTS.toLong())

    /** Tokens out for [lamportsIn], or null when the input is not positive or the curve's real tokens cannot cover it. */
    fun buy(reserves: CurveReserves, lamportsIn: BigInteger): CurveBuy? {
        if (lamportsIn.signum() <= 0) return null
        val tokensOut = lamportsIn.multiply(reserves.virtualToken).divide(reserves.virtualSol.add(lamportsIn))
        return if (tokensOut > reserves.realToken) null else CurveBuy(tokensOut)
    }

    /**
     * Tokens out for a `buy_exact_quote_in_v3` paying [lamportsIn] to the curve: the program prices the trade on one lamport
     * less than it moves into the reserves (observed exact on every captured buy, per the pumpfun-exact-quote-in-v3-buys ADR).
     */
    fun buyExactQuoteIn(reserves: CurveReserves, lamportsIn: BigInteger): CurveBuy? = buy(reserves, lamportsIn.subtract(BigInteger.ONE))

    /** Lamports out for [tokensIn], or null when the input is not positive or the curve's real SOL cannot cover it. */
    fun sell(reserves: CurveReserves, tokensIn: BigInteger): CurveSell? {
        if (tokensIn.signum() <= 0) return null
        val lamportsOut = tokensIn.multiply(reserves.virtualSol).divide(reserves.virtualToken.add(tokensIn))
        return if (lamportsOut > reserves.realSol) null else CurveSell(lamportsOut)
    }

    fun fee(lamports: BigInteger, basisPoints: BigInteger): BigInteger {
        require(lamports.signum() >= 0 && basisPoints.signum() >= 0) { "Negative fee input lamports=$lamports basisPoints=$basisPoints" }
        return lamports.multiply(basisPoints).add(BASIS_POINTS_DENOMINATOR).subtract(BigInteger.ONE).divide(BASIS_POINTS_DENOMINATOR)
    }

    fun spotPrice(reserves: CurveReserves): BigDecimal = price(reserves.virtualSol, reserves.virtualToken)

    /** SOL per whole token for [lamports] against [rawTokens], at the price scale. */
    fun price(lamports: BigInteger, rawTokens: BigInteger, tokenDecimals: Int = PumpFunLayout.TOKEN_DECIMALS): BigDecimal {
        val quote = BigDecimal(lamports.multiply(BigInteger.TEN.pow(tokenDecimals)))
        val base = BigDecimal(rawTokens.multiply(BigInteger.TEN.pow(PumpFunLayout.SOL_AMOUNT_DECIMALS)))
        return quote.divide(base, DecimalPolicy.PRICE_SCALE, DecimalPolicy.ROUNDING)
    }
}
