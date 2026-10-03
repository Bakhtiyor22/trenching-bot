package com.trenching.bot.strategy.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import java.math.BigDecimal

@ConsistentCopyVisibility
data class SimulationConfig private constructor(
    val strategyName: String,
    val strategyVersion: String,
    val notional: BigDecimal,
    val feeBps: Int,
    val slippageBps: Int,
    val maxWaitSlots: Long,
    val maxAgeSlots: Long,
    val holdingSlots: Long,
    val quoteMint: MintAddress,
) {
    companion object {
        fun of(notional: BigDecimal, feeBps: Int, slippageBps: Int, maxWaitSlots: Long, maxAgeSlots: Long,
               holdingSlots: Long, strategyName: String = "confirmed-buy", strategyVersion: String = "1",
               quoteMint: MintAddress = MintAddress.WRAPPED_SOL): SimulationConfig {
            val normalizedNotional = DecimalPolicy.scale(notional)
            require(normalizedNotional.signum() > 0) { "Notional must remain positive at the model scale" }
            require(feeBps in SimulationConfigBounds.BASIS_POINTS && slippageBps in SimulationConfigBounds.BASIS_POINTS) { "Invalid basis points" }
            require(maxWaitSlots in SimulationConfigBounds.SLOT_WINDOW && maxAgeSlots in SimulationConfigBounds.SLOT_WINDOW &&
                holdingSlots >= SimulationConfigBounds.MIN_HOLDING_SLOTS) { "Invalid slot bounds" }
            require(strategyName.isNotBlank() && strategyVersion.isNotBlank()) { "Missing strategy identity" }
            return SimulationConfig(strategyName, strategyVersion, normalizedNotional,
                feeBps, slippageBps, maxWaitSlots, maxAgeSlots, holdingSlots, quoteMint)
        }
        fun mvp(): SimulationConfig = of(BigDecimal("100"), 10, 50, 2, 2, 5)
    }
}
