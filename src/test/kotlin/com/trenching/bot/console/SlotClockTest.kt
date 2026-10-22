package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.SlotClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SlotClockTest {
    private val start = at("2026-10-21T04:10:00Z")

    /** Four slots a second, 250 ms each, over 600 slots; block times are whole seconds. */
    private val observations = (0..600).map { index ->
        observation("o$index", start.plusSeconds(index / 4L), "0.0000001", slot = FIRST_SLOT + index)
    }

    @Test
    fun `slots sharing a block time second are spread across it at the recorded pace`() {
        val clock = SlotClock.of(observations)
        val second = start.plusSeconds(10)

        val estimates = (0..3).map { clock.estimate(FIRST_SLOT + 40 + it, second) }

        assertEquals(250.0, clock.millisPerSlot)
        assertEquals(estimates.sorted(), estimates)
        estimates.zipWithNext { earlier, later -> assertEquals(250, Duration.between(earlier, later).toMillis()) }
        assertTrue(estimates.all { it >= second && it < second.plusSeconds(1) })
    }

    @Test
    fun `an estimate never leaves its block time's second`() {
        val clock = SlotClock.of(observations)

        assertEquals(start.plusSeconds(100), clock.estimate(FIRST_SLOT, start.plusSeconds(100)))
        assertEquals(start.plusMillis(999), clock.estimate(FIRST_SLOT + 400, start))
    }

    @Test
    fun `slots known from elsewhere, like the wallet's own trades, widen their second`() {
        val first = start.plusSeconds(10)
        val withWalletSlot = SlotClock.of(observations, mapOf(FIRST_SLOT + 39 to first))

        assertEquals(first, withWalletSlot.estimate(FIRST_SLOT + 39, first))
        assertEquals(first.plusMillis(250), withWalletSlot.estimate(FIRST_SLOT + 40, first))
    }

    @Test
    fun `without enough slots to measure the pace the estimate is the block time`() {
        val clock = SlotClock.of(observations.take(10))

        assertNull(clock.millisPerSlot)
        assertEquals(start.plusSeconds(1), clock.estimate(FIRST_SLOT + 5, start.plusSeconds(1)))
    }

    private companion object {
        const val FIRST_SLOT = 459_490_744L
    }
}
