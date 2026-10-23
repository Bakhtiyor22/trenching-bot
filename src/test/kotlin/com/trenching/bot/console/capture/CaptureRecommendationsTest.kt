package com.trenching.bot.console.capture

import com.trenching.bot.marketdata.MarketCutoffMode
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CaptureRecommendationsTest {
    @Test
    fun `the decided cutoff names the agreed levels, clocks and four-minute minimum it cuts with`() {
        val cutoff = CaptureRecommendations.of(null, null, null, ratesConfigured = true, CaptureSuggestedWallets(emptyList()))
            .single { it.id == CaptureRecommendations.CUTOFF_DECIDED }

        assertEquals(MarketCutoffMode.DECIDED.name, cutoff.setting?.value)
        assertEquals(CaptureRecommendations.CUTOFF_USES_AGREED_STRATEGY, cutoff.caveatCode)
        assertEquals(mapOf<String, Any>("dipPercent" to "20", "takeProfitPercent" to "55", "stopLossPercent" to "40",
            "dipWindowSeconds" to 30L, "maxHoldSeconds" to 180L, "hardExitStartSeconds" to 240L, "minimumRecordingSeconds" to 240L), cutoff.facts)
    }

    @Test
    fun `without reference rates no decided cutoff is recommended`() {
        val recommendations = CaptureRecommendations.of(null, null, null, ratesConfigured = false, CaptureSuggestedWallets(emptyList()))

        assertEquals(emptyList(), recommendations.filter { it.id == CaptureRecommendations.CUTOFF_DECIDED })
    }
}
