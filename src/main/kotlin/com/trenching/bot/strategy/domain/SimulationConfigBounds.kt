package com.trenching.bot.strategy.domain

object SimulationConfigBounds {
    val BASIS_POINTS: IntRange = 0..9999
    val SLOT_WINDOW: LongRange = 0L..1000L
    const val MIN_HOLDING_SLOTS = 1L
}
