package com.trenching.bot.console.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RunRecommendationsTest {
    @Test
    fun `the legacy comparison warns that a capture cut by the agreed clocks may not cover it`() {
        val comparison = RunRecommendations.of(2).single { it.id == RunRecommendations.ADD_LEGACY_COMPARISON }

        assertEquals(RunRecommendations.COMPARISON_MAY_NEED_FULL_CAPTURE, comparison.caveatCode)
        assertEquals(mapOf("dipPercent" to "15", "takeProfitPercent" to "30", "stopLossPercent" to "15"), comparison.facts)
        assertNull(comparison.setting)
    }

    @Test
    fun `the agreed strategy recommendation carries no caveat`() {
        assertNull(RunRecommendations.of(1).single { it.id == RunRecommendations.USE_AGREED_STRATEGY }.caveatCode)
    }
}
