package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketCaptureWindowTest {
    @Test
    fun `a first buy at S with a lead of 50 gives the window from S minus 50 through S plus the horizon anchored at the buy`() {
        val window = MarketCaptureWindow.forFirstBuy(MINT, Slot.of(BUY_SLOT), BUY, LEAD)

        assertEquals(MarketCaptureWindow.of(MINT, Slot.of(BUY_SLOT - 50), Slot.of(BUY_SLOT + 4500), BUY), window)
        assertEquals(BUY_SLOT, window.firstBuySlot(LEAD))
    }

    @Test
    fun `legacy parameters give the window from S plus 1 as before`() {
        val window = MarketCaptureWindow.forFirstBuy(MINT, Slot.of(BUY_SLOT), BUY, LEGACY)

        assertEquals(MarketCaptureWindow.of(MINT, Slot.of(BUY_SLOT + 1), Slot.of(BUY_SLOT + 4500), BUY), window)
        assertEquals(BUY_SLOT, window.firstBuySlot(LEGACY))
    }

    @Test
    fun `parameters built with two arguments mean the legacy window and the default lead is 50 slots`() {
        assertEquals(MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS, MarketCaptureWindowParameters(4500, 2).leadSlots)
        assertEquals(-1L, MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)
        assertEquals(50L, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
    }

    @Test
    fun `a first buy too early for its lead is refused as an invalid market window`() {
        assertEquals(Slot.of(0), MarketCaptureWindow.forFirstBuy(MINT, Slot.of(50), BUY, LEAD).fromSlot)
        assertTrue(MarketCaptureWindow.leadFits(Slot.of(50), LEAD))
        assertFalse(MarketCaptureWindow.leadFits(Slot.of(49), LEAD))
        assertTrue(MarketCaptureWindow.leadFits(Slot.of(0), LEGACY))

        val failure = assertFailsWith<CaptureValidationException> { MarketCaptureWindow.forFirstBuy(MINT, Slot.of(49), BUY, LEAD) }
        assertTrue(failure.message.orEmpty().endsWith("category=MARKET_WINDOW_INVALID"), failure.message)
    }

    private companion object {
        const val BUY_SLOT = 459_490_743L
        val MINT: MintAddress = MintAddress.of("5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs")
        val BUY: TransactionSignature = TransactionSignature.of(Base58.encode(ByteArray(64) { 7 }))
        val LEAD = MarketCaptureWindowParameters(4500, 2, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val LEGACY = MarketCaptureWindowParameters(4500, 2)
    }
}
