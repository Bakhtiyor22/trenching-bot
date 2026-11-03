package com.trenching.bot.console.automation.domain

import java.math.BigInteger
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rules 35, 39 and 42: the backfill filter, its request budget, the dry-run tally and the capture estimate. */
class BackfillAccountingTest {
    private val capture = CaptureFilter(BigInteger.valueOf(1_500_000_000), 300)

    @Test
    fun `a minimum signal replaces the capture filter's minimum and keeps its fee margin`() {
        val filter = BackfillFilter(BigInteger.valueOf(3_000_000_000), emptyList()).captureFilter(capture)

        assertEquals(CaptureFilter(BigInteger.valueOf(3_000_000_000), 300), filter)
        assertEquals(capture, BackfillFilter(null, emptyList()).captureFilter(capture))
    }

    @Test
    fun `an empty token list allows every mint and a list only its own`() {
        assertTrue(BackfillFilter(null, emptyList()).allowsMint("anything"))
        assertTrue(BackfillFilter(null, listOf("a", "b")).allowsMint("b"))
        assertFalse(BackfillFilter(null, listOf("a", "b")).allowsMint("c"))
    }

    @Test
    fun `an operation or a page is admitted while the spend plus it stays within the budget`() {
        val budget = BackfillBudget(requestBudget = 3000, discoveryRequests = 1000, itemRequests = 800)

        assertEquals(1800, budget.spent)
        assertTrue(budget.admits(1200))
        assertFalse(budget.admits(1201))
    }

    @Test
    fun `backfill discovery runs only while the rolling total is below the cap less the reserve`() {
        val cap = AutomationRequestCap(maxPerDay = 5000, operationRequests = 1200)

        assertTrue(cap.admitsBackfillDiscovery(3999, reserve = 1000))
        assertFalse(cap.admitsBackfillDiscovery(4000, reserve = 1000))
        assertTrue(cap.admitsBackfillDiscovery(4999, reserve = 0))
    }

    @Test
    fun `a backfill's capture step starts only while the total plus one allowance leaves the reserve free, a live one needs the cap alone`() {
        val cap = AutomationRequestCap(maxPerDay = 5000, operationRequests = 1200)

        assertTrue(cap.admitsBackfill(2400, reserve = 1400))
        assertFalse(cap.admitsBackfill(2401, reserve = 1400))
        assertTrue(cap.admits(3800), "a live step is unchanged")
        assertFalse(cap.admits(3801))
    }

    @Test
    fun `a tally counts buys per trading day and distinct mints, whatever order they arrive in`() {
        val first = LocalDate.parse("2026-11-01")
        val second = LocalDate.parse("2026-11-02")

        val tally = DryRunTally.EMPTY.plus(listOf(first to "m1", first to "m2")).plus(listOf(second to "m1", first to "m1"))

        assertEquals(mapOf(first to 3, second to 1), tally.buysPerDay)
        assertEquals(setOf("m1", "m2"), tally.mints)
        assertEquals(DryRunSummary(2, mapOf(first to 3, second to 1)), tally.summary())
    }

    @Test
    fun `the median of an odd count is the middle value, of an even count the mean rounded up`() {
        val odd = listOf(3L, 5L, 9L)
        val even = listOf(2L, 3L, 6L, 9L)
        val flat = listOf(4L, 4L)

        assertEquals(5, CaptureEstimate.median(odd.size.toLong()) { odd[it.toInt()] })
        assertEquals(5, CaptureEstimate.median(even.size.toLong()) { even[it.toInt()] }, "(3 + 6) / 2 = 4.5 rounds up")
        assertEquals(4, CaptureEstimate.median(flat.size.toLong()) { flat[it.toInt()] })
    }

    @Test
    fun `no captured item gives no median and no estimate`() {
        assertNull(CaptureEstimate.median(0) { error("nothing to read") })
        assertNull(CaptureEstimate.requests(null, 12))
        assertEquals(60, CaptureEstimate.requests(5, 12))
    }
}
