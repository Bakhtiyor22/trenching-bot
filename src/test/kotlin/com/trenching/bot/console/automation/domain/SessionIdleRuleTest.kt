package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 10 with the 2026-11-03 fixes: where a buy fits a session by time, and when discovery shows a session idle. */
class SessionIdleRuleTest {
    private val rule = SessionIdleRule(Duration.ofMinutes(60))
    private val first = Instant.parse("2026-11-02T15:00:00Z")
    private val last = first.plus(Duration.ofMinutes(20))

    @Test
    fun `a buy fits a session from the idle time before its first buy to the idle time after its last, both included`() {
        assertTrue(rule.fits(first, last, first.minus(Duration.ofMinutes(60))))
        assertTrue(rule.fits(first, last, first.plusSeconds(1)))
        assertTrue(rule.fits(first, last, last.plus(Duration.ofMinutes(60))))
    }

    @Test
    fun `a buy one millisecond further away fits on neither side`() {
        assertFalse(rule.fits(first, last, first.minus(Duration.ofMinutes(60)).minusMillis(1)))
        assertFalse(rule.fits(first, last, last.plus(Duration.ofMinutes(60)).plusMillis(1)))
    }

    @Test
    fun `buys in time order split into runs wherever two are more than the idle time apart`() {
        val buys = listOf(first, first.plus(Duration.ofMinutes(60)), first.plus(Duration.ofMinutes(120)).plusMillis(1), first.plus(Duration.ofMinutes(130)))

        assertEquals(listOf(buys.take(2), buys.drop(2)), rule.runs(buys) { it })
        assertEquals(emptyList(), rule.runs(emptyList<Instant>()) { it })
    }

    @Test
    fun `a session is idle once discovery is complete through more than the idle time after its last buy`() {
        val through = last.plus(Duration.ofMinutes(60))

        assertEquals(last, rule.idleBefore(through))
    }

    @Test
    fun `discovery is complete through the last complete scan's start less the indexing margin, and through nothing while the first catch-up runs`() {
        val scanned = DiscoveryWatermark(DiscoveryCursor(10, "sig", 2, -1), null, null, null, last)
        val catchingUp = scanned.copy(resumeBefore = "older", resumeSince = first.minus(Duration.ofHours(12)))

        assertEquals(last.minusSeconds(60), scanned.discoveredThrough)
        assertNull(catchingUp.discoveredThrough)
    }

    @Test
    fun `a scan covers up to sixty seconds before its start`() {
        assertEquals(last.minusSeconds(60), DiscoveryWatermark.coveredBy(last))
    }

    @Test
    fun `a wallet with no discovered action yet keeps its lookback as the bound, never its placeholder cursor`() {
        val since = first.minus(Duration.ofHours(12))
        val origin = DiscoveryWatermark(DiscoveryCursor.ORIGIN, null, since, null, first)

        assertEquals(DiscoveryBound.Since(since), origin.bound())
        assertTrue(DiscoveryCursor.ORIGIN < DiscoveryCursor(0, "", 0, -1))
    }
}
