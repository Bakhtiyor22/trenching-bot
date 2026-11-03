package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.DayStanding
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.capture.MutableClock
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rules 32–34 and Acceptance Criteria 3 and 4: days, their completeness and their verdicts, read from the stored rows. */
class AutomationDayServiceTest : AutomationDatabaseTest() {
    private val clock = MutableClock(local("2026-11-12", "13:00"))
    private val plan = SweepPlans.DECU_SWEEP_1
    private val labels = plan.entries.map { it.label }
    private val seed by lazy { AutomationDaySeed(sessions, items, sweepRuns) }

    @Test
    fun `a day lists its sessions, buys, counts, requests and completeness, newest day first`() {
        seed.closedDay("2026-11-09", signals = 1)
        val open = session("open-10", AutomationSessionState.OPEN, local("2026-11-10", "21:00"))
        sessions.save(open.apply { belowFilterCount = 3 })
        item(seed.nextNumber(), "open-10", AutomationItemState.CAPTURED, blockTime = local("2026-11-10", "21:00")).also { items.save(it.apply { requestsUsed = 120 }) }
        item(seed.nextNumber(), "open-10", AutomationItemState.FAILED, blockTime = local("2026-11-10", "21:05")).also { items.save(it.apply { requestsUsed = 30 }) }
        item(seed.nextNumber(), "open-10", AutomationItemState.ABANDONED, blockTime = local("2026-11-10", "21:10")).also { items.save(it) }
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))

        val list = dayService().days(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-11"))

        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"), list.days.map { it.day.toString() })
        val tenth = list.days[1]
        assertEquals(1, tenth.sessions)
        assertEquals(listOf(3, 1, 1, 0, 1), listOf(tenth.buys, tenth.captured, tenth.failed, tenth.simulated, tenth.abandoned))
        assertEquals(3, tenth.belowFilter)
        assertEquals(150L, tenth.requestsUsed)
        assertTrue(tenth.covered)
        assertEquals(false, tenth.complete)
        assertEquals("PENDING", tenth.bestStatus)
        assertEquals(local("2026-11-10", "12:00"), tenth.from)
        assertEquals(local("2026-11-11", "12:00"), tenth.to)
        val eleventh = list.days[0]
        assertTrue(eleventh.complete, "covered, no buys")
        assertEquals(0, eleventh.sessions)
        assertEquals(1, list.days[2].simulated)
    }

    @Test
    fun `a day discovery has not covered all of is incomplete however closed its sessions are`() {
        seed.closedDay("2026-11-11", signals = 1)
        seedCoverage(coveredFrom = local("2026-11-11", "20:30"))

        val day = dayService().days(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12")).days

        assertEquals(false, day.single { it.day.toString() == "2026-11-11" }.complete)
        assertEquals(false, day.single { it.day.toString() == "2026-11-11" }.covered)
        assertEquals(false, day.single { it.day.toString() == "2026-11-12" }.covered, "today is not over")
    }

    @Test
    fun `after seven complete days the last day's ten rows are ordered by status, then worst-case P&L`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))

        val page = dayService().evaluations(DAY, DAY, null, null, "status", 0, 20)

        assertEquals(10, page.total)
        assertEquals(listOf("V07", "V06", "V05", "V04", "V03", "V02", "V01", "V00", "V08", "V09"), page.items.map { it.label })
        assertEquals(List(3) { "SUCCESS" } + List(5) { "NO_SUCCESS" } + List(2) { "NO_RESULT" }, page.items.map { it.status })
        val top = page.items.first()
        assertEquals(BigDecimal("0.420000000000"), top.tally.worstCasePnl, "14 signals at 0.03")
        assertEquals(listOf(14, 14, 0), listOf(top.tally.signals, top.tally.scored, top.tally.unscored))
        assertEquals((5..11).map { LocalDate.parse("2026-11-" + it.toString().padStart(2, '0')) }, top.windowDays)
        assertEquals(listOf(0, 14, 0), page.items.last().let { listOf(it.tally.signals, it.tally.scored, it.tally.unscored) })
        assertEquals(DAY, top.day)
    }

    @Test
    fun `an incomplete day in the window makes all ten rows pending on the oldest incomplete day`() {
        seed.week()
        sessions.findOneById("session-2026-11-08")?.let { sessions.save(it.apply { state = AutomationSessionState.CLOSING }) }
        sessions.findOneById("session-2026-11-10")?.let { sessions.save(it.apply { state = AutomationSessionState.OPEN }) }
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))

        val page = dayService().evaluations(DAY, DAY, null, null, "status", 0, 20)

        assertEquals(10, page.total)
        assertEquals(listOf("PENDING"), page.items.map { it.status }.distinct())
        assertEquals(listOf(LocalDate.parse("2026-11-08")), page.items.map { it.pendingDay }.distinct())
    }

    @Test
    fun `a backfilled older day changes a newer day's rows on the next read`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-05", "20:00"))
        val service = dayService()

        val before = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items
        watermarks.findById(WALLET).orElseThrow().also { watermarks.save(it.apply { coveredFrom = local("2026-11-01", "12:00") }) }
        val after = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items

        assertEquals(listOf(LocalDate.parse("2026-11-05")), before.map { it.pendingDay }.distinct())
        assertTrue(after.all { it.standing is DayStanding.Decided })
        assertEquals(14, after.first().tally.signals)
    }

    @Test
    fun `an older day a finished capturing backfill read is covered, so the verdicts that were waiting for it appear`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-05", "20:00"))
        val service = dayService()
        val before = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items

        backfills.save(AutomationBackfillEntity("done", "req-done", WALLET, null, LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05"),
            local("2026-11-04", "12:00"), local("2026-11-05", "20:00"), false, null, null, 1000, BackfillState.DONE, clock.instant(), clock.instant()))
        val after = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items

        assertEquals(listOf(LocalDate.parse("2026-11-05")), before.map { it.pendingDay }.distinct())
        assertTrue(after.all { it.standing is DayStanding.Decided })
        assertEquals(listOf(local("2026-11-04", "12:00")), service.day(LocalDate.parse("2026-11-05")).coverage.ranges.map { it.from })
    }

    @Test
    fun `rows filter by label and status, and a page holds at most one hundred`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))
        val service = dayService()

        assertEquals(listOf("V07"), service.evaluations(DAY, DAY, "V07", null, "status", 0, 20).items.map { it.label })
        assertEquals(listOf("V07", "V06", "V05"), service.evaluations(DAY, DAY, null, "SUCCESS", "status", 0, 20).items.map { it.label })
        assertEquals(0, service.evaluations(DAY, DAY, null, "PENDING", "status", 0, 20).total)
        assertEquals(30, service.evaluations(LocalDate.parse("2026-11-09"), DAY, null, null, "status", 0, 500).items.size, "three days of ten")
        assertEquals(100, service.evaluations(LocalDate.parse("2026-09-12"), DAY, null, null, "status", 0, 500).items.size)
        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"),
            service.evaluations(LocalDate.parse("2026-11-09"), DAY, "V00", null, "day,desc", 0, 20).items.map { it.day.toString() })
    }

    @Test
    fun `a span of days is bounded, ordered and defaults to the last fourteen`() {
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))
        val service = dayService()

        assertEquals(14, service.days(null, null).days.size)
        assertEquals(LocalDate.parse("2026-11-12"), service.days(null, null).to, "today's trading day")
        assertEquals(62, service.days(LocalDate.parse("2026-09-11"), LocalDate.parse("2026-11-11")).days.size)
        assertFailsWith<InvalidAutomationRequestException> { service.days(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-11-11")) }
        assertFailsWith<InvalidAutomationRequestException> { service.days(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-10")) }
        assertFailsWith<InvalidAutomationRequestException> { service.evaluations(null, null, null, "GREAT", "status", 0, 20) }
        assertFailsWith<InvalidAutomationRequestException> { service.evaluations(null, null, null, null, "label", 0, 20) }
    }

    @Test
    fun `a day's detail has its sessions, items, coverage and one row per entry`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))

        val detail = dayService().day(DAY)

        assertEquals(LocalDate.parse("2026-11-11"), detail.summary.day)
        assertEquals(listOf("session-2026-11-11"), detail.sessions.map { it.session.id })
        assertEquals(DAY, detail.sessions.single().tradingDay)
        assertEquals(2, detail.items.size)
        assertEquals(true, detail.coverage.covered)
        assertEquals(1, detail.coverage.ranges.size)
        assertEquals(local("2026-11-01", "12:00"), detail.coverage.ranges.single().from)
        assertEquals(labels, detail.evaluations.map { it.label })
    }

    @Test
    fun `a day's session belongs to the day its first buy falls on, a buy at noon to the new day`() {
        sessions.save(session("before", AutomationSessionState.CLOSED, local("2026-11-10", "11:59:59")))
        sessions.save(session("at", AutomationSessionState.CLOSED, local("2026-11-10", "12:00")))
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))

        val service = dayService()

        assertEquals(listOf("before"), service.day(LocalDate.parse("2026-11-09")).sessions.map { it.session.id })
        assertEquals(listOf("at"), service.day(LocalDate.parse("2026-11-10")).sessions.map { it.session.id })
    }

    @Test
    fun `an older day only a filtered backfill read is not covered, so the verdicts that wait for it stay pending`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-05", "20:00"))
        backfills.save(AutomationBackfillEntity("filtered", "req-filtered", WALLET, null, LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05"),
            local("2026-11-04", "12:00"), local("2026-11-05", "20:00"), false, null, null, 1000, BackfillState.DONE, clock.instant(), clock.instant())
            .also { it.filtered = true })

        val rows = dayService().evaluations(DAY, DAY, null, null, "status", 0, 20).items

        assertEquals(listOf(LocalDate.parse("2026-11-05")), rows.map { it.pendingDay }.distinct())
    }

    @Test
    fun `a day with an item that ended failed for its backfill's budget stays pending, one that failed for another reason does not`() {
        seed.week()
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))
        val failed = item(seed.nextNumber(), "session-2026-11-09", AutomationItemState.FAILED, blockTime = local("2026-11-09", "21:30"))
        items.save(failed.apply { failureCode = "WALLET_FAILED" })
        val service = dayService()

        val otherFailure = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items
        items.save(failed.apply { failureCode = AutomationFailures.BACKFILL_BUDGET })
        val budgetFailure = service.evaluations(DAY, DAY, null, null, "status", 0, 20).items
        val day = service.days(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-09")).days.single()

        assertTrue(otherFailure.all { it.standing is DayStanding.Decided })
        assertEquals(listOf(LocalDate.parse("2026-11-09")), budgetFailure.map { it.pendingDay }.distinct())
        assertEquals(false, day.complete)
        assertEquals("PENDING", day.bestStatus)
    }

    @Test
    fun `a phase 0 day in a closed session completes once its backlog items are terminal, without the session reopening`() {
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))
        sessions.save(session("phase-0", AutomationSessionState.CLOSED, local("2026-11-10", "21:00")))
        val backlog = items.save(item(seed.nextNumber(), "phase-0", AutomationItemState.DISCOVERED, blockTime = local("2026-11-10", "21:00")))
        val service = dayService()

        val before = service.days(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10")).days.single()
        items.save(backlog.apply { state = AutomationItemState.SIMULATED })
        val after = service.days(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10")).days.single()

        assertEquals(false, before.complete)
        assertTrue(after.complete)
        assertEquals(AutomationSessionState.CLOSED, sessions.findById("phase-0").orElseThrow().state)
    }

    @Test
    fun `a day with more than a hundred sessions is read whole, so no verdict rests on a truncated day`() {
        seedCoverage(coveredFrom = local("2026-11-01", "12:00"))
        val first = local("2026-11-10", "12:30")
        sessions.saveAll((1..701).map { n -> session("busy-$n", AutomationSessionState.CLOSED, first.plusSeconds(n.toLong())) })

        val day = dayService().days(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10")).days.single()

        assertEquals(701, day.sessions)
    }

    private fun local(date: String, time: String): Instant = AutomationDaySeed.local(date, time)

    private fun dayService() = dayService(properties(captureEnabled = true), clock)

    /** Live discovery has scanned the wallet from [coveredFrom] up to a scan that started now. */
    private fun seedCoverage(coveredFrom: Instant) {
        watermarks.save(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, clock.instant()).apply { this.coveredFrom = coveredFrom })
    }

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-11-11")
    }
}
