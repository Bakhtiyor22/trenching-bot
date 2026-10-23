package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.marketdata.MarketListedSignature
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowRecord
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketWindowScanTest {
    @Test
    fun `holds an unstarted window whose end is past the confirmed tip as horizon not elapsed`() {
        val held = MarketWindowScan.atTip(window(), tip = TO_SLOT - 1)
        assertEquals(window().copy(coverage = MarketWindowCoverage.HORIZON_NOT_ELAPSED), held)
    }

    @Test
    fun `keeps a not elapsed window held while the confirmed tip is still before its end`() {
        val held = window().copy(coverage = MarketWindowCoverage.HORIZON_NOT_ELAPSED)
        assertEquals(held, MarketWindowScan.atTip(held, tip = TO_SLOT - 1))
    }

    @Test
    fun `opens a window once the confirmed tip reaches its end and records that tip`() {
        assertEquals(window().copy(listedAtTip = TO_SLOT), MarketWindowScan.atTip(window(), tip = TO_SLOT))
        assertEquals(window().copy(listedAtTip = TO_SLOT + 1),
            MarketWindowScan.atTip(window().copy(coverage = MarketWindowCoverage.HORIZON_NOT_ELAPSED), tip = TO_SLOT + 1))
    }

    @Test
    fun `leaves a started or decided window to its listing whatever the tip`() {
        val started = window().copy(pagesScanned = 1, scanned = 1, skippedNewer = 1, scanCursor = MarketListedSignature("a", TO_SLOT + 1))
        assertEquals(started, MarketWindowScan.atTip(started, tip = TO_SLOT - 1))
        val complete = started.copy(coverage = MarketWindowCoverage.COMPLETE)
        assertEquals(complete, MarketWindowScan.atTip(complete, tip = TO_SLOT - 1))
    }

    @Test
    fun `asks for the tip only for a window that has not started its listing`() {
        assertTrue(MarketWindowScan.needsTip(window()))
        assertTrue(MarketWindowScan.needsTip(window().copy(coverage = MarketWindowCoverage.HORIZON_NOT_ELAPSED)))
        assertFalse(MarketWindowScan.needsTip(window().copy(pagesScanned = 1)))
        assertFalse(MarketWindowScan.needsTip(window().copy(coverage = MarketWindowCoverage.COMPLETE, pagesScanned = 1)))
    }

    @Test
    fun `estimates the wait until the tip reaches the window end at the measured pace with a tenth to spare`() {
        assertEquals(Duration.ofMillis(2_387), MarketWindowScan.untilEnd(window(), tip = TO_SLOT - 10, Duration.ofMillis(217)))
        assertEquals(Duration.ZERO, MarketWindowScan.untilEnd(window(), tip = TO_SLOT, Duration.ofMillis(217)))
    }

    @Test
    fun `without a measured pace the wait uses the nominal 400 ms per slot`() {
        assertEquals(Duration.ofMillis(4_400), MarketWindowScan.untilEnd(window(), tip = TO_SLOT - 10, null))
    }

    @Test
    fun `a lead window listed without an until completes at the first entry below its lead and keeps the buy and the rest of its slot`() {
        val lead = window().copy(fromSlot = BUY_SLOT - 50, toSlot = BUY_SLOT + 4500, untilSignature = BUY)
        val page = listOf(entry("newer", BUY_SLOT + 4501), entry("after", BUY_SLOT + 1), entry("sameSlotAfter", BUY_SLOT), entry(BUY, BUY_SLOT),
            entry("sameSlotBefore", BUY_SLOT), entry("leadFailed", BUY_SLOT - 10, failed = true), entry("leadStart", BUY_SLOT - 50),
            entry("beforeLead", BUY_SLOT - 51), entry("older", BUY_SLOT - 52))

        val scanned = MarketWindowScan.apply(lead, page, admitted = emptySet(), capacity = 10)

        assertEquals(MarketWindowCoverage.COMPLETE, scanned.coverage)
        assertEquals(listOf("after", "sameSlotAfter", BUY, "sameSlotBefore", "leadStart"), scanned.selection.map { it.signature })
        assertEquals(1, scanned.skippedNewer)
        assertEquals(1, scanned.skippedFailed)
        assertEquals(7, scanned.scanned)
        assertEquals(MarketListedSignature("leadStart", BUY_SLOT - 50), scanned.scanCursor)
    }

    @Test
    fun `a legacy window selects nothing of the buy's slot and completes below it`() {
        val legacy = window().copy(fromSlot = BUY_SLOT + 1, toSlot = BUY_SLOT + 4500, untilSignature = BUY)
        val page = listOf(entry("after", BUY_SLOT + 1), entry("sameSlot", BUY_SLOT))

        val scanned = MarketWindowScan.apply(legacy, page, admitted = emptySet(), capacity = 10)

        assertEquals(MarketWindowCoverage.COMPLETE, scanned.coverage)
        assertEquals(listOf("after"), scanned.selection.map { it.signature })
    }

    @Test
    fun `lead trades count toward the cap and a busy lead makes the window over the cap without trimming it`() {
        val lead = window().copy(fromSlot = BUY_SLOT - 50, toSlot = BUY_SLOT + 4500, untilSignature = BUY)
        val page = listOf(entry(BUY, BUY_SLOT), entry("lead1", BUY_SLOT - 1), entry("lead2", BUY_SLOT - 2), entry("beforeLead", BUY_SLOT - 51))

        val scanned = MarketWindowScan.apply(lead, page, admitted = emptySet(), capacity = 2)

        assertEquals(MarketWindowCoverage.OVER_CAP, scanned.coverage)
        assertEquals(3, scanned.selected)
        assertEquals(emptyList(), scanned.selection)
    }

    private fun entry(signature: String, slot: Long, failed: Boolean = false) = SignatureListingEntry(signature, slot, failed)

    private fun window() = MarketWindowRecord(
        baseMint = "mint", quoteMint = "quote", fromSlot = FROM_SLOT, toSlot = TO_SLOT, untilSignature = "until",
        coverage = MarketWindowCoverage.PENDING, pagesScanned = 0, scanned = 0, skippedFailed = 0, skippedNewer = 0, selected = 0,
        selection = emptyList(), scanCursor = null,
    )

    private companion object {
        const val FROM_SLOT = 458_870_212L
        const val TO_SLOT = 458_874_711L
        const val BUY_SLOT = 459_490_743L
        const val BUY = "buy"
    }
}
