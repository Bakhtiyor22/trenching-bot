package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.console.automation.AutomationFixtures.SKIPPED_LAMPORTS
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.sell
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.capture.MutableClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Acceptance Criterion 4: phase 0 over two nights, with discovery every 10 minutes and a tick every 5. */
class AutomationPhaseZeroTest : AutomationDatabaseTest() {
    private val clock = MutableClock(local(2, "18:00"))
    private val lookup = FakeWalletActivityLookup(clock)

    @Test
    fun `two nights in discovery-only mode give two idle-closed sessions with one item per mint and no capture`() {
        lookup.actions += listOf(
            buy(1, 1001, local(2, "19:10"), mint = 1),
            buy(2, 1002, local(2, "19:20"), lamports = SKIPPED_LAMPORTS, mint = 2),
            buy(3, 1003, local(2, "19:40"), mint = 3),
            sell(4, 1004, local(2, "19:50"), mint = 1),
            buy(5, 1005, local(2, "20:00"), mint = 1),
            buy(6, 1006, local(2, "20:30"), mint = 4),
            buy(7, 2001, local(3, "21:00"), mint = 5),
            buy(8, 2002, local(3, "21:05"), lamports = SKIPPED_LAMPORTS, mint = 6),
            buy(9, 2003, local(3, "21:10"), lamports = SKIPPED_LAMPORTS, mint = 7),
            buy(10, 2004, local(3, "21:45"), mint = 8),
            buy(11, 2005, local(3, "22:40"), mint = 9),
        )
        val properties = properties(captureEnabled = false)
        val discovery = discovery(lookup, properties, clock)
        val sessionService = sessionService(properties, clock)

        while (clock.now < local(4, "06:00")) {
            if (clock.now.epochSecond % TEN_MINUTES == 0L) discovery.discover()
            sessionService.closeIdleSessions()
            clock.now = clock.now.plus(Duration.ofMinutes(5))
        }

        val byNight = sessions.findAll().sortedBy { it.firstBuyAt }
        assertEquals(listOf(AutomationSessionState.CLOSED, AutomationSessionState.CLOSED), byNight.map { it.state })
        assertEquals(listOf(SessionCloseReason.IDLE, SessionCloseReason.IDLE), byNight.map { it.closeReason })
        assertEquals(listOf(local(2, "19:10"), local(3, "21:00")), byNight.map { it.firstBuyAt })
        assertEquals(listOf(local(2, "20:30"), local(3, "22:40")), byNight.map { it.lastBuyAt })
        assertEquals(listOf(1, 2), byNight.map { it.belowFilterCount })
        val bySession = items.findAll().groupBy { it.sessionId }
        assertEquals(listOf(listOf(1, 3, 4), listOf(5, 8, 9)).map { night -> night.map { mint(it).value } },
            byNight.map { session -> bySession.getValue(session.id).sortedBy { it.slot }.map { it.mint } })
        assertTrue(items.findAll().all { it.state == AutomationItemState.DISCOVERED && it.captureSessionId == null && it.stepAttempt == 0 })
        assertTrue(lookup.requests.isNotEmpty())
        assertTrue(lookup.requests.all { it.second == ApiUsageContext(ApiTask.WALLET_ACTIVITY, null) })
    }

    private fun local(day: Int, time: String): Instant = LocalDateTime.parse("2026-11-0${day}T$time").toInstant(ZoneOffset.ofHours(5))

    private companion object {
        const val TEN_MINUTES = 600L
    }
}
