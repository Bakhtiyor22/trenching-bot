package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.walletactivity.WalletAction
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class AutomationSessionServiceTest : AutomationDatabaseTest() {
    private val clock = MutableClock(EVENING)
    private val lookup = FakeWalletActivityLookup()

    @Test
    fun `an open session stays open after a complete scan at exactly its idle time plus the indexing margin and starts closing after one a millisecond later`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))

        scanAt(properties, EVENING.plus(IDLE_AND_MARGIN))
        sessionService(properties, clock).closeIdleSessions()
        val atIdle = session().state
        scanAt(properties, EVENING.plus(IDLE_AND_MARGIN).plusMillis(1))
        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.OPEN, atIdle)
        assertEquals(AutomationSessionState.CLOSING, session().state)
    }

    @Test
    fun `a buy the listing has not shown yet when a scan starts seconds after the idle time still joins its session`() {
        val properties = properties(captureEnabled = false)
        seed(properties, buy(1, 100, EVENING))

        scanAt(properties, EVENING.plus(Duration.ofMinutes(60)).plusSeconds(30))
        sessionService(properties, clock).closeIdleSessions()
        val afterScan = session().state
        lookup.actions += buy(2, 200, EVENING.plus(Duration.ofMinutes(60)))
        scanAt(properties, EVENING.plus(Duration.ofMinutes(70)))

        assertEquals(AutomationSessionState.OPEN, afterScan)
        assertEquals(EVENING.plus(Duration.ofMinutes(60)), session().lastBuyAt)
        assertEquals(2, items.findAll().count { it.sessionId == session().id })
    }

    @Test
    fun `after the polling window discovery scans only while a session's idle time has passed uncovered, so it closes the same morning`() {
        val properties = properties(captureEnabled = false)
        clock.now = morning("04:30")
        seed(properties, buy(1, 100, morning("04:20")))
        val discovery = discovery(lookup, properties, clock)
        val sessionService = sessionService(properties, clock)
        val requests = mutableListOf<Int>()

        listOf("05:10", "05:30", "05:40", "06:30").forEach { time ->
            clock.now = morning(time)
            lookup.requests.clear()
            discovery.discover()
            sessionService.closeIdleSessions()
            requests += lookup.requests.size
        }

        assertEquals(listOf(0, 1, 0, 0), requests, "only the 05:30 scan, after 04:20 plus the idle time, is read")
        assertEquals(AutomationSessionState.CLOSED, session().state)
        assertEquals(morning("05:30"), session().closedAt)
    }

    @Test
    fun `a wallet that keeps buying after the window closes does not keep the morning scans going, its session waits for the evening`() {
        val properties = properties(captureEnabled = false)
        val visible = FakeWalletActivityLookup(clock)
        clock.now = morning("04:30")
        visible.actions += buy(1, 100, morning("04:20"))
        val discovery = discovery(visible, properties, clock)
        discovery.discover()
        visible.actions += listOf(buy(2, 200, morning("05:20")), buy(3, 300, morning("06:10")), buy(4, 400, morning("07:30")))
        val sessionService = sessionService(properties, clock)
        val requests = mutableListOf<Int>()

        listOf("05:30", "06:30", "07:40", "08:45", "12:00").forEach { time ->
            clock.now = morning(time)
            visible.requests.clear()
            discovery.discover()
            sessionService.closeIdleSessions()
            requests += visible.requests.size
        }

        assertEquals(listOf(1, 0, 0, 0, 0), requests, "only the 05:30 scan runs: the buys after 05:00 wait for the polling window")
        assertEquals(AutomationSessionState.OPEN, session().state)
        assertEquals(morning("05:20"), session().lastBuyAt)
        assertEquals(2, items.count())
    }

    @Test
    fun `a session past its idle time stays open until a complete scan has started after it`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))

        clock.now = EVENING.plus(Duration.ofHours(2))
        sessionService(properties, clock).closeIdleSessions()
        val unscanned = session().state
        scanAt(properties, EVENING.plus(Duration.ofHours(2)))
        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.OPEN, unscanned)
        assertEquals(AutomationSessionState.CLOSING, session().state)
    }

    @Test
    fun `a scan the page cap cuts short leaves the session open, and a later buy it had not reached yet still joins it`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))
        lookup.actions += buy(2, 200, EVENING.plus(Duration.ofMinutes(50)))
        lookup.otherTransactions += (500..519).map { signature(it) to 1000L + it }
        clock.now = EVENING.plus(Duration.ofHours(2))
        val capped = discovery(lookup, properties(captureEnabled = true, maxDiscoveryPages = 1), clock)

        capped.discover()
        sessionService(properties, clock).closeIdleSessions()
        val afterCappedScan = session().state
        capped.discover()

        assertEquals(AutomationSessionState.OPEN, afterCappedScan)
        assertEquals(EVENING.plus(Duration.ofMinutes(50)), session().lastBuyAt)
        assertEquals(2, items.findAll().count { it.sessionId == session().id })
    }

    @Test
    fun `with capture enabled a closing session waits for its discovered items`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))
        scanAt(properties, EVENING.plus(Duration.ofHours(2)))

        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.CLOSING, session().state)
        assertNull(session().closedAt)
    }

    @Test
    fun `a closing session closes idle once every item is terminal`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))
        scanAt(properties, EVENING.plus(Duration.ofHours(2)))
        sessionService(properties, clock).closeIdleSessions()
        items.findAll().forEach { it.state = AutomationItemState.SIMULATED; items.save(it) }

        clock.now = EVENING.plus(Duration.ofHours(3))
        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.CLOSED, session().state)
        assertEquals(SessionCloseReason.IDLE, session().closeReason)
        assertEquals(EVENING.plus(Duration.ofHours(3)), session().closedAt)
    }

    @Test
    fun `in discovery-only mode a discovered item counts as terminal, so an idle session closes in one tick`() {
        val properties = properties(captureEnabled = false)
        seed(properties, buy(1, 100, EVENING))
        scanAt(properties, EVENING.plus(Duration.ofHours(2)))

        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.CLOSED, session().state)
        assertEquals(SessionCloseReason.IDLE, session().closeReason)
        assertEquals(AutomationItemState.DISCOVERED, items.findAll().single().state)
    }

    @Test
    fun `a manual close abandons the pending items, keeps the terminal ones and closes the session now`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING), buy(2, 101, null))
        clock.now = EVENING.plus(Duration.ofMinutes(5))

        val detail = sessionService(properties, clock).close(session().id, "close-1")

        assertEquals(AutomationSessionState.CLOSED, detail.session.session.state)
        assertEquals(SessionCloseReason.MANUAL, session().closeReason)
        assertEquals(EVENING.plus(Duration.ofMinutes(5)), session().closedAt)
        assertEquals(AutomationItemState.ABANDONED, items.findAll().single { it.signature == signature(1).value }.state)
        val failed = items.findAll().single { it.signature == signature(2).value }
        assertEquals(AutomationItemState.FAILED, failed.state)
        assertEquals(AutomationFailures.MISSING_BLOCK_TIME, failed.failureCode)
    }

    @Test
    fun `a repeated manual close changes nothing`() {
        val properties = properties(captureEnabled = true)
        seed(properties, buy(1, 100, EVENING))
        clock.now = EVENING.plus(Duration.ofMinutes(5))
        val service = sessionService(properties, clock)
        service.close(session().id, "close-1")

        clock.now = EVENING.plus(Duration.ofMinutes(9))
        val again = service.close(session().id, "close-2")

        assertEquals(EVENING.plus(Duration.ofMinutes(5)), again.session.session.closedAt)
        assertEquals(SessionCloseReason.MANUAL, again.session.session.closeReason)
        assertEquals(mapOf(AutomationItemState.ABANDONED to 1L), again.session.itemCounts.filterValues { it > 0 })
    }

    @Test
    fun `closing an unknown session is refused`() {
        assertFailsWith<AutomationSessionNotFoundException> { sessionService(properties(), clock).close("missing", "close-1") }
    }

    @Test
    fun `a close without a valid request id is refused before anything changes`() {
        val properties = properties()
        seed(properties, buy(1, 100, EVENING))

        assertFailsWith<InvalidAutomationRequestException> { sessionService(properties, clock).close(session().id, " bad id") }
        assertFailsWith<InvalidAutomationRequestException> { sessionService(properties, clock).close(session().id, null) }
        assertEquals(AutomationSessionState.OPEN, session().state)
    }

    @Test
    fun `a live closing session closes though more than a hundred older closing sessions of a backfill still wait for their items`() {
        val properties = properties(captureEnabled = true)
        val waiting = (1..CLOSING_BACKFILL_SESSIONS).map { n ->
            AutomationRows.session("bf-$n", AutomationSessionState.CLOSING, EVENING.minus(Duration.ofDays(5)).plusSeconds(n.toLong())).also { it.backfillId = "bf" }
        }
        sessions.saveAll(waiting + AutomationRows.session("live", AutomationSessionState.CLOSING, EVENING.minus(Duration.ofHours(2))))
        items.saveAll(waiting.mapIndexed { index, session ->
            AutomationRows.item(index + 1, session.id, AutomationItemState.DISCOVERED, blockTime = session.firstBuyAt).also { it.backfillId = "bf" }
        })
        items.save(AutomationRows.item(1000, "live", AutomationItemState.SIMULATED, blockTime = EVENING.minus(Duration.ofHours(2))))

        sessionService(properties, clock).closeIdleSessions()

        assertEquals(AutomationSessionState.CLOSED, sessions.findById("live").orElseThrow().state)
        assertEquals(CLOSING_BACKFILL_SESSIONS, sessions.findAll().count { it.state == AutomationSessionState.CLOSING })
    }

    @Test
    fun `a manual close abandons the non-terminal items of a session that is already closed, and leaves it closed as it was`() {
        val properties = properties(captureEnabled = true)
        sessions.save(AutomationRows.session("phase-0", AutomationSessionState.CLOSED, EVENING).also { it.closeReason = SessionCloseReason.IDLE; it.closedAt = EVENING })
        items.save(AutomationRows.item(1, "phase-0", AutomationItemState.DISCOVERED, blockTime = EVENING))
        items.save(AutomationRows.item(2, "phase-0", AutomationItemState.SIMULATED, blockTime = EVENING))

        val closed = sessionService(properties, clock).close("phase-0", "close-1")
        val again = sessionService(properties, clock).close("phase-0", "close-2")

        assertEquals(AutomationItemState.ABANDONED, items.findById("item-1").orElseThrow().state)
        assertEquals(AutomationItemState.SIMULATED, items.findById("item-2").orElseThrow().state)
        assertEquals(SessionCloseReason.IDLE, closed.session.session.closeReason)
        assertEquals(closed, again)
    }

    private fun seed(properties: AutomationProperties, vararg buys: WalletAction) {
        lookup.actions += buys
        discovery(lookup, properties, clock).discover()
    }

    /** A complete discovery scan starting at [at]. */
    private fun scanAt(properties: AutomationProperties, at: Instant) {
        clock.now = at
        discovery(lookup, properties, clock).discover()
    }

    private fun session(): AutomationSessionEntity = sessions.findAll().single()

    /** [time] on the morning of 3 November 2026 in UTC+5, the default zone; the polling window ends at 05:00. */
    private fun morning(time: String): Instant = LocalDateTime.parse("2026-11-03T$time").toInstant(ZoneOffset.ofHours(5))

    private companion object {
        val IDLE_AND_MARGIN: Duration = Duration.ofMinutes(61)
        const val CLOSING_BACKFILL_SESSIONS = 105
    }
}
