package com.trenching.bot.papertrading.application

import com.trenching.bot.common.DecimalPolicy
import java.math.BigDecimal

/** Observed price moved adversely by the configured slippage, kept at [DecimalPolicy.PRICE_SCALE]. */
object ExecutionPrice {
    fun buy(observed: BigDecimal, slippageBps: Int): BigDecimal =
        DecimalPolicy.price(observed.multiply(BigDecimal.ONE.add(DecimalPolicy.fraction(slippageBps))))

    fun sell(observed: BigDecimal, slippageBps: Int): BigDecimal =
        DecimalPolicy.price(observed.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(slippageBps))))
}
