package com.trenching.bot.console.application

import com.trenching.bot.marketdata.domain.MarketObservation
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Places a slot inside its block time's second. Block times are whole seconds, so the several slots of one second share one.
 * Within each second the known slots are spread at the pace the observations were recorded at, centred on the second's
 * middle, and kept inside the second so an estimate never contradicts the chain. A fit per second, rather than one line over
 * the whole recording, follows the block times however far the slot pace drifts. Without a measurable pace the estimate is
 * the block time itself.
 */
class SlotClock private constructor(
    /** The recorded pace, null when [SlotPace] could not measure it. */
    val millisPerSlot: Double?,
    /** Per block-time epoch second, the middle of the known slots that carry it. */
    private val centres: Map<Long, Double>,
) {
    fun estimate(slot: Long, blockTime: Instant): Instant {
        val pace = millisPerSlot ?: return blockTime
        val second = blockTime.epochSecond
        val centre = centres[second] ?: slot.toDouble()
        val offset = (HALF_SECOND_MILLIS + (slot - centre) * pace).roundToLong().coerceIn(0, LAST_MILLI)
        return Instant.ofEpochSecond(second).plusMillis(offset)
    }

    companion object {
        private const val HALF_SECOND_MILLIS = 500.0
        private const val LAST_MILLI = 999L

        /** [observations] of one pair measure the pace; they and [otherSlots] (slot to block time, e.g. the wallet's own trades) place each second. */
        fun of(observations: List<MarketObservation>, otherSlots: Map<Long, Instant> = emptyMap()): SlotClock {
            val pace = SlotPace.millisPerSlot(observations) ?: return SlotClock(null, emptyMap())
            val slots = observations.associate { it.slot.value to it.observedTime } + otherSlots
            val centres = slots.entries.groupBy({ it.value.truncatedTo(ChronoUnit.SECONDS).epochSecond }, { it.key })
                .mapValues { (_, inSecond) -> (inSecond.min() + inSecond.max()) / 2.0 }
            return SlotClock(pace, centres)
        }
    }
}
