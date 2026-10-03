package com.trenching.bot.console.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.strategy.domain.SimulationConfigBounds

/** Inclusive bounds; a null [max] means unbounded above. */
data class NumberRange(val min: Long, val max: Long?) {
    operator fun contains(value: Long): Boolean = value >= min && (max == null || value <= max)
}

/**
 * Editable ranges sourced from strategy so each field can be reported separately.
 */
data class SimulationConfigLimits(
    val notionalScale: Int,
    val notionalPrecision: Int,
    val feeBps: NumberRange,
    val slippageBps: NumberRange,
    val maxWaitSlots: NumberRange,
    val maxAgeSlots: NumberRange,
    val holdingSlots: NumberRange,
) {
    companion object {
        private val BASIS_POINTS = NumberRange(SimulationConfigBounds.BASIS_POINTS.first.toLong(), SimulationConfigBounds.BASIS_POINTS.last.toLong())
        private val SLOT_WINDOW = NumberRange(SimulationConfigBounds.SLOT_WINDOW.first, SimulationConfigBounds.SLOT_WINDOW.last)

        val CURRENT = SimulationConfigLimits(
            notionalScale = DecimalPolicy.SCALE,
            notionalPrecision = DecimalPolicy.STORAGE_PRECISION,
            feeBps = BASIS_POINTS,
            slippageBps = BASIS_POINTS,
            maxWaitSlots = SLOT_WINDOW,
            maxAgeSlots = SLOT_WINDOW,
            holdingSlots = NumberRange(SimulationConfigBounds.MIN_HOLDING_SLOTS, null),
        )
    }
}
