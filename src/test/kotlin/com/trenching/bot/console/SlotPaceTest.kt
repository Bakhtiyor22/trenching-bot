package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.SlotPace
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SlotPaceTest {
    @Test
    fun `measures milliseconds per slot from the earliest to the latest observed slot`() {
        val observations = listOf(
            observation("last", at("2026-10-19T16:02:35Z"), "1", slot = 458_892_657),
            observation("first", at("2026-10-19T15:58:43Z"), "1", slot = 458_891_591),
            observation("middle", at("2026-10-19T16:00:00Z"), "1", slot = 458_892_000),
        )

        assertEquals(217.6, SlotPace.millisPerSlot(observations))
    }

    @Test
    fun `too few slots or no elapsed time measure nothing`() {
        val start = at("2026-10-19T15:58:43Z")
        assertNull(SlotPace.millisPerSlot(emptyList()))
        assertNull(SlotPace.millisPerSlot(listOf(observation("a", start, "1", slot = 1_000), observation("b", start.plusSeconds(60), "1", slot = 1_499))))
        assertNull(SlotPace.millisPerSlot(listOf(observation("a", start, "1", slot = 1_000), observation("b", start, "1", slot = 2_000))))
    }
}
