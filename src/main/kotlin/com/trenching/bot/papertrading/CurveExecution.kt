package com.trenching.bot.papertrading

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.papertrading.domain.CurveTrade
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * The economics shared by paper entries and exits on a Pump.fun bonding curve, per the bonding-curve-execution ADR, and
 * the one place SOL and token amounts convert between raw units and decimals.
 */
object CurveExecution {
    /** [lamports] traded for [rawTokens] against [state], with fees charged on [lamports] at the state's basis points. */
    fun trade(state: CurveState, lamports: BigInteger, rawTokens: BigInteger): CurveTrade {
        val feeLamports = BondingCurve.fee(lamports, BigInteger.valueOf(state.feeBps.toLong()))
            .add(BondingCurve.fee(lamports, BigInteger.valueOf(state.creatorFeeBps.toLong())))
        val executionPrice = BondingCurve.price(lamports, rawTokens)
        return CurveTrade(lamports, rawTokens, feeLamports, state.feeBps + state.creatorFeeBps, executionPrice,
            impactBps(executionPrice, BondingCurve.spotPrice(state.reserves)))
    }

    fun lamports(sol: BigDecimal): BigInteger = raw(sol, PumpFunLayout.SOL_AMOUNT_DECIMALS)

    fun sol(lamports: BigInteger): BigDecimal = DecimalPolicy.scale(BigDecimal(lamports, PumpFunLayout.SOL_AMOUNT_DECIMALS))

    fun rawTokens(quantity: BigDecimal): BigInteger = raw(quantity, PumpFunLayout.TOKEN_DECIMALS)

    fun quantity(rawTokens: BigInteger): BigDecimal = DecimalPolicy.scale(BigDecimal(rawTokens, PumpFunLayout.TOKEN_DECIMALS))

    /** Exact: every simulated SOL amount and token quantity is a whole number of raw units. */
    private fun raw(value: BigDecimal, decimals: Int): BigInteger =
        value.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact()

    private fun impactBps(executionPrice: BigDecimal, spotPrice: BigDecimal): Int =
        executionPrice.subtract(spotPrice).multiply(DecimalPolicy.BASIS_POINTS)
            .divide(spotPrice, 0, DecimalPolicy.ROUNDING).intValueExact()
}
