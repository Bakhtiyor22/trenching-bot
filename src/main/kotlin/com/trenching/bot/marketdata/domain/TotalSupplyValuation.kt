package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal

/**
 * The fully diluted valuation in USDT of a Pump.fun token at an observation: price (SOL per token) x SOL/USDT rate x the
 * fixed bonding-curve supply, rounded like the dip strategy's market cap. The only place this formula lives.
 */
object TotalSupplyValuation {
    private val SUPPLY: BigDecimal = BigDecimal.valueOf(PumpFunLayout.TOKEN_SUPPLY)

    fun of(observation: MarketObservation, solUsdtRate: BigDecimal): ValuationOutcome {
        val unavailable = when {
            observation.source != PumpFunLayout.TRADE_SOURCE -> ValuationUnavailableReason.UNSUPPORTED_TOKEN
            observation.pair.quote != MintAddress.WRAPPED_SOL -> ValuationUnavailableReason.NOT_SOL_QUOTED
            !followsFromTrade(observation) -> ValuationUnavailableReason.DECIMALS_MISMATCH
            else -> null
        }
        return if (unavailable != null) ValuationOutcome.Unavailable(unavailable)
            else ValuationOutcome.Valued(usdt(observation.price, solUsdtRate))
    }

    /** The valuation of a Pump.fun token at [price] SOL per token, for a caller that has already checked it is one. */
    fun usdt(price: BigDecimal, solUsdtRate: BigDecimal): BigDecimal = DecimalPolicy.scale(price.multiply(solUsdtRate).multiply(SUPPLY))

    /** Whether the price is the trade's amounts at the protocol's token decimals; trivially true without a trade. */
    private fun followsFromTrade(observation: MarketObservation): Boolean {
        val trade = observation.trade ?: return true
        return BondingCurve.price(trade.solLamports, trade.tokenRaw).compareTo(observation.price) == 0
    }
}
