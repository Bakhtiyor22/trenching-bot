package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Plan Rule 5: a wallet buy is captured when its curve payment, raised by [feeMarginBps] to stand in for the fees the
 * listing leaves out and rounded down to whole lamports, is strictly above [minSignalLamports].
 */
data class CaptureFilter(val minSignalLamports: BigInteger, val feeMarginBps: Int) {
    fun passes(curvePaymentLamports: BigInteger): Boolean {
        val raised = BigDecimal(curvePaymentLamports.multiply(BigInteger.valueOf(BASIS_POINTS + feeMarginBps)))
            .divide(DecimalPolicy.BASIS_POINTS, 0, RoundingMode.DOWN)
        return raised.toBigIntegerExact() > minSignalLamports
    }

    companion object {
        private const val BASIS_POINTS = 10_000L

        fun of(minSignalSol: BigDecimal, feeMarginBps: Int): CaptureFilter {
            require(minSignalSol.stripTrailingZeros().scale() <= PumpFunLayout.SOL_AMOUNT_DECIMALS) {
                "The capture minimum must be whole lamports minSignalSol=${minSignalSol.toPlainString()}"
            }
            return CaptureFilter(minSignalSol.movePointRight(PumpFunLayout.SOL_AMOUNT_DECIMALS).toBigIntegerExact(), feeMarginBps)
        }
    }
}
