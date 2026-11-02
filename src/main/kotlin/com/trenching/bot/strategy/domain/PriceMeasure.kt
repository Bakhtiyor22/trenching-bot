package com.trenching.bot.strategy.domain

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.protocol.pumpfun.BondingCurve
import java.math.BigDecimal

/**
 * Which price of a recorded trade the strategy's rules compare. [TRADE_AVERAGE] is the trade's own average fill price, the
 * behaviour up to engine dip-paper-v4; [POST_TRADE] is spec Section 5's one measure, the curve's spot price right after the trade.
 */
enum class PriceMeasure {
    TRADE_AVERAGE,
    POST_TRADE,
    ;

    fun of(observation: MarketObservation): BigDecimal = when (this) {
        TRADE_AVERAGE -> observation.price
        POST_TRADE -> BondingCurve.spotPrice(observation.curve.reserves)
    }
}
