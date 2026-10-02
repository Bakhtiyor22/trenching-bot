package com.trenching.bot.papertrading.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.strategy.domain.*
import java.math.BigDecimal

class FillModel(private val selector: ObservationSelector = ObservationSelector()) {
    fun fill(intent: TradeIntent, source: MarketDataSource, config: SimulationConfig): FillOutcome {
        return when (val selected = selector.select(source, intent.pair, intent.targetSlot, config, intent.notional)) {
            is ObservationSelection.Unavailable -> FillOutcome.Rejected(intent.id, selected.reason)
            is ObservationSelection.Selected -> {
                val observation = selected.observation
                val price = ExecutionPrice.buy(observation.price, config.slippageBps)
                if (price.signum() <= 0) return FillOutcome.Rejected(intent.id, RejectionReason.INVALID_QUALITY)
                val quantity = intent.notional.divide(price, DecimalPolicy.SCALE, DecimalPolicy.ROUNDING)
                if (quantity.signum() <= 0) return FillOutcome.Rejected(intent.id, RejectionReason.ZERO_QUANTITY)
                val fee = DecimalPolicy.scale(intent.notional.multiply(DecimalPolicy.fraction(config.feeBps)))
                FillOutcome.Filled(PaperFill(intent.id, intent.targetSlot, observation.slot, observation.id,
                    price, quantity, fee, DecimalPolicy.scale(intent.notional.add(fee)),
                    config.feeBps, config.slippageBps, observation.source, observation.quality,
                    DecimalPolicy.scale(BigDecimal.ZERO), 0))
            }
        }
    }
}
