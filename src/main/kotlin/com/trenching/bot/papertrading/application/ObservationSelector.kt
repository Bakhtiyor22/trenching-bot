package com.trenching.bot.papertrading.application

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal

class ObservationSelector {
    fun select(source: MarketDataSource, pair: MarketPair, target: Slot, config: SimulationConfig,
               notional: BigDecimal): ObservationSelection =
        selectEligible(source, pair, target, config) { notional }

    fun selectForExit(source: MarketDataSource, pair: MarketPair, target: Slot, config: SimulationConfig,
                      quantity: BigDecimal): ObservationSelection =
        selectEligible(source, pair, target, config) { observation ->
            DecimalPolicy.scale(quantity.multiply(ExecutionPrice.sell(observation.price, config.slippageBps)))
        }

    private fun selectEligible(source: MarketDataSource, pair: MarketPair, target: Slot,
                               config: SimulationConfig, requiredLiquidity: (MarketObservation) -> BigDecimal): ObservationSelection {
        val candidates = source.observations(pair, target, target.plus(config.maxWaitSlots))
        if (candidates.isEmpty()) return ObservationSelection.Unavailable(RejectionReason.MISSING_OBSERVATION)
        var firstFailure: RejectionReason? = null
        for (observation in candidates) {
            val failure = when {
                observation.price.signum() <= 0 || observation.quality == ObservationQuality.INVALID ||
                    observation.commitment == Commitment.PROCESSED || observation.priceSlot > observation.slot -> RejectionReason.INVALID_QUALITY
                observation.slot.value - observation.priceSlot.value > config.maxAgeSlots -> RejectionReason.STALE_OBSERVATION
                observation.liquidityQuote != null && observation.liquidityQuote < requiredLiquidity(observation) -> RejectionReason.INSUFFICIENT_LIQUIDITY
                else -> null
            }
            if (failure == null) return ObservationSelection.Selected(observation)
            if (firstFailure == null) firstFailure = failure
        }
        return ObservationSelection.Unavailable(requireNotNull(firstFailure))
    }
}
