package com.trenching.bot.portfolio.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.papertrading.application.ExecutionPrice
import com.trenching.bot.papertrading.application.ObservationSelector
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.strategy.domain.*

class PositionService(private val selector: ObservationSelector = ObservationSelector()) {
    fun open(intent: TradeIntent, fill: PaperFill): Position {
        require(intent.id == fill.orderId) { "Fill does not belong to intent" }
        require(fill.actualSlot >= intent.targetSlot && fill.quantity.signum() > 0 && fill.costBasis.signum() > 0) {
            "Invalid entry fill"
        }
        return Position(intent.id, intent.runId, intent.id, intent.pair, intent.entryOffset, fill, PositionState.OPEN, null)
    }

    fun close(position: Position, source: MarketDataSource, config: SimulationConfig): CloseOutcome {
        require((position.state == PositionState.CLOSED) == (position.close != null)) { "Inconsistent position state" }
        if (position.state == PositionState.CLOSED) return CloseOutcome.AlreadyClosed(position)
        val target = position.fill.actualSlot.plus(config.holdingSlots)
        return when (val selected = selector.selectForExit(source, position.pair, target, config, position.fill.quantity)) {
            is ObservationSelection.Unavailable -> CloseOutcome.Unavailable(selected.reason)
            is ObservationSelection.Selected -> {
                val observation = selected.observation
                val price = ExecutionPrice.sell(observation.price, config.slippageBps)
                if (price.signum() <= 0) return CloseOutcome.Unavailable(RejectionReason.INVALID_QUALITY)
                val gross = DecimalPolicy.scale(position.fill.quantity.multiply(price))
                val fee = DecimalPolicy.scale(gross.multiply(DecimalPolicy.fraction(config.feeBps)))
                val net = DecimalPolicy.scale(gross.subtract(fee))
                val close = PositionClose(observation.slot, observation.id, price, gross, fee, net,
                    DecimalPolicy.scale(net.subtract(position.fill.costBasis)), CloseReason.MAX_HOLDING_SLOTS)
                CloseOutcome.Closed(position.copy(state = PositionState.CLOSED, close = close))
            }
        }
    }
}
