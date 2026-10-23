package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketWindowRulesTest {
    @Test
    fun `windows with leads still never overlap or touch for one mint`() {
        val first = lead(MINT, 1_000)
        val overlapping = lead(MINT, 1_000 + HORIZON + LEAD.leadSlots)
        val touching = lead(MINT, 1_000 + HORIZON + LEAD.leadSlots + 1)
        val apart = lead(MINT, 1_000 + HORIZON + LEAD.leadSlots + 2)

        assertFalse(MarketWindowRules.isDisjoint(MarketWindowRules.canonical(listOf(overlapping, first))))
        assertFalse(MarketWindowRules.isDisjoint(MarketWindowRules.canonical(listOf(touching, first))))
        assertTrue(MarketWindowRules.isDisjoint(MarketWindowRules.canonical(listOf(apart, first))))
    }

    @Test
    fun `lead windows of different mints may share slots`() {
        assertTrue(MarketWindowRules.isDisjoint(MarketWindowRules.canonical(listOf(lead(MINT, 1_000), lead(OTHER_MINT, 1_000)))))
    }

    @Test
    fun `accepts the legacy lead and a lead of up to 1000 slots`() {
        listOf(MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS, 0L, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS, 1_000L).forEach { lead ->
            assertTrue(MarketWindowRules.isValid(LEAD.copy(leadSlots = lead)), "lead $lead")
        }
        listOf(-2L, 1_001L).forEach { lead -> assertFalse(MarketWindowRules.isValid(LEAD.copy(leadSlots = lead)), "lead $lead") }
    }

    private fun lead(mint: MintAddress, buySlot: Long) =
        MarketCaptureWindow.forFirstBuy(mint, Slot.of(buySlot), TransactionSignature.of(Base58.encode(ByteArray(64) { buySlot.toByte() })), LEAD)

    private companion object {
        const val HORIZON = 4500L
        val LEAD = MarketCaptureWindowParameters(HORIZON, 2, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val MINT: MintAddress = MintAddress.of("5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs")
        val OTHER_MINT: MintAddress = MintAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    }
}
