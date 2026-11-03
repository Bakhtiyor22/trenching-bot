package com.trenching.bot.strategy.domain

import com.trenching.bot.protocol.pumpfun.PumpFunFees
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Spec P3's signal size, fee inclusive (`docs/adr/20261102-fee-inclusive-signal-size.md`): the SOL the wallet's buy paid into
 * the curve plus the program's protocol and creator fees on it, each rounded up to a whole lamport. A signal must be strictly
 * above [SimulationConfig.minSignalSol].
 */
object SignalSize {
    fun lamports(curvePaymentLamports: BigInteger, feeBps: Int, creatorFeeBps: Int): BigInteger =
        curvePaymentLamports.add(PumpFunFees.lamports(curvePaymentLamports, feeBps))
            .add(PumpFunFees.lamports(curvePaymentLamports, creatorFeeBps))

    fun exceeds(signalLamports: BigInteger, minimumSol: BigDecimal): Boolean = signalLamports > minimumLamports(minimumSol)

    /** Exact: a minimum is a whole number of lamports, per [SimulationConfigBounds.MIN_SIGNAL_SOL]. */
    fun minimumLamports(minimumSol: BigDecimal): BigInteger =
        minimumSol.movePointRight(PumpFunLayout.SOL_AMOUNT_DECIMALS).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact()
}
