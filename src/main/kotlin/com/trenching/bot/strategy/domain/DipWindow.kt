package com.trenching.bot.strategy.domain

import java.time.Instant

/**
 * Spec P7: a trade can trigger the dip entry only if it landed no later than [SimulationConfig.dipWindowMs] after t0, the
 * wallet buy's block time, inclusive. The watch ends at the first trade outside the window.
 */
sealed interface DipWindow {
    fun admits(observedTime: Instant): Boolean

    /** No dip window is configured, so only the horizon bounds the watch. */
    data object Unbounded : DipWindow {
        override fun admits(observedTime: Instant): Boolean = true
    }

    data class Until(val end: Instant) : DipWindow {
        override fun admits(observedTime: Instant): Boolean = !observedTime.isAfter(end)
    }

    /** A window is configured but the buy has no block time to count it from, so no trade can trigger. */
    data object Closed : DipWindow {
        override fun admits(observedTime: Instant): Boolean = false
    }

    companion object {
        fun of(config: SimulationConfig, t0: Instant?): DipWindow {
            val windowMs = config.dipWindowMs ?: return Unbounded
            return t0?.let { Until(it.plusMillis(windowMs)) } ?: Closed
        }
    }
}
