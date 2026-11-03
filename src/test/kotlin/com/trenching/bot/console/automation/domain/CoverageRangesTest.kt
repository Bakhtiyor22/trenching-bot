package com.trenching.bot.console.automation.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 32: a day is covered when its whole range lies within the union of the covered ranges. */
class CoverageRangesTest {
    private fun at(hour: Int) = Instant.parse("2026-11-03T00:00:00Z").plusSeconds(hour * 3600L)

    private fun range(from: Int, to: Int) = TimeRange(at(from), at(to))

    @Test
    fun `a range inside one covered range is covered, up to the covered end itself`() {
        val coverage = CoverageRanges(listOf(range(2, 10)))

        assertTrue(coverage.covers(range(2, 10)))
        assertTrue(coverage.covers(range(3, 5)))
        assertFalse(coverage.covers(range(1, 5)), "starts before the coverage")
        assertFalse(coverage.covers(TimeRange(at(3), at(10).plusMillis(1))), "ends after the coverage")
    }

    @Test
    fun `ranges that touch or overlap cover what spans them, a gap does not`() {
        val coverage = CoverageRanges(listOf(range(10, 14), range(2, 6), range(6, 10), range(20, 24)))

        assertTrue(coverage.covers(range(3, 13)), "three ranges meet end to start")
        assertFalse(coverage.covers(range(12, 22)), "the hours between 14 and 20 are a gap")
        assertEquals(listOf(range(2, 14), range(20, 24)), coverage.ranges)
    }

    @Test
    fun `nothing is covered without ranges, and an empty range is covered only where a range stands`() {
        assertFalse(CoverageRanges.NONE.covers(range(1, 2)))
        assertFalse(CoverageRanges.NONE.covers(range(1, 1)))
        assertTrue(CoverageRanges(listOf(range(0, 5))).covers(range(1, 1)))
    }
}
