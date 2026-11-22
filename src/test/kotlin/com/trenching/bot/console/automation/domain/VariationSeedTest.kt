package com.trenching.bot.console.automation.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The seed numbers entries from zero, so every label must be the one an edit's sequence number would give it, or an edit would meet its key. */
class VariationSeedTest {
    private val now = Instant.parse("2026-11-12T13:00:00Z")
    private val agreed = SweepPlans.DECU_SWEEP_1.entries.first().config

    @Test
    fun `a code plan whose labels are V and the index is seeded in order`() {
        val seeded = Variation.seed(SweepPlans.DECU_SWEEP_1, now)

        assertEquals(SweepPlans.DECU_SWEEP_1.entries.map { it.label }, seeded.map { it.label })
        assertEquals((0..9).toList(), seeded.map { it.seq })
    }

    @Test
    fun `a code plan whose labels skip one is refused, naming the plan and the label`() {
        val skipping = SweepPlan("skipping-plan", listOf(SweepEntry("V00", agreed), SweepEntry("V02", agreed)))

        val refused = assertFailsWith<IllegalArgumentException> { Variation.seed(skipping, now) }

        assertTrue(refused.message.orEmpty().contains("skipping-plan"), refused.message)
        assertTrue(refused.message.orEmpty().contains("V02"), refused.message)
    }
}
