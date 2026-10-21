package com.trenching.bot.marketdata

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketWindowChecksTest {
    @Test
    fun `a listed window without a recorded tip that skipped nothing newer may have been listed before its end`() {
        assertTrue(MarketWindowChecks.mayBeListedBeforeEnd(listed()))
        assertTrue(MarketWindowChecks.mayBeListedBeforeEnd(listed().copy(coverage = MarketWindowCoverage.COVERAGE_INCOMPLETE)))
    }

    @Test
    fun `a recorded tip proves the listing started after the end`() {
        assertFalse(MarketWindowChecks.mayBeListedBeforeEnd(listed().copy(listedAtTip = TO_SLOT + 5)))
    }

    @Test
    fun `a listing that skipped newer trades started after the end`() {
        assertFalse(MarketWindowChecks.mayBeListedBeforeEnd(listed().copy(skippedNewer = 3)))
    }

    @Test
    fun `a window not listed yet says nothing`() {
        assertFalse(MarketWindowChecks.mayBeListedBeforeEnd(listed().copy(coverage = MarketWindowCoverage.PENDING, pagesScanned = 0,
            scanned = 0, selected = 0, selection = emptyList(), scanCursor = null)))
    }

    private fun listed() = MarketWindowRecord(
        baseMint = "mint", quoteMint = "quote", fromSlot = FROM_SLOT, toSlot = TO_SLOT, untilSignature = "until",
        coverage = MarketWindowCoverage.COMPLETE, pagesScanned = 1, scanned = 1, skippedFailed = 0, skippedNewer = 0, selected = 1,
        selection = listOf(MarketListedSignature("a", FROM_SLOT + 1)), scanCursor = MarketListedSignature("a", FROM_SLOT + 1),
    )

    private companion object {
        const val FROM_SLOT = 458_891_591L
        const val TO_SLOT = 458_896_090L
    }
}
