package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.domain.AutomationSweepRunRow
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.domain.RunStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutomationSweepRunStatusTest : AutomationDatabaseTest() {
    private val service get() = query(AutomationFixtures.properties(), Clock.fixed(EVENING, ZoneOffset.UTC))

    private data class Seeded(val id: String, val position: PositionState?, val realized: String, val worst: String, val outcome: String)

    private val seeded = listOf(
        Seeded("r-profit", PositionState.CLOSED, "0.5", "0.5", "TAKE_PROFIT"),
        Seeded("r-loss-a", PositionState.CLOSED, "-0.25", "-0.25", "STOP_LOSS"),
        Seeded("r-loss-b", PositionState.CLOSED, "-0.000000000001", "-0.000000000001", "TIME_EXIT"),
        Seeded("r-flat", PositionState.CLOSED, "0", "0", "TIME_EXIT"),
        Seeded("r-open", PositionState.OPEN, "0", "-0.1", "NO_FILL_AFTER_TRIGGER"),
        Seeded("r-none", null, "0", "0", "NO_DIP"),
    )

    @BeforeEach
    fun seedRows() {
        seeded.forEachIndexed { index, row ->
            items.save(item(index + 1, "s1", blockTime = EVENING.minusSeconds(index.toLong())))
            sweepRuns.save(sweepRun(row.id, "item-${index + 1}", "V00", outcome = row.outcome, runId = row.id.takeIf { row.position != null },
                positionState = row.position, realizedPnl = row.realized, worstCasePnl = row.worst))
        }
    }

    private fun ids(page: List<AutomationSweepRunRow>) = page.map { "${it.run.itemId}" }

    private fun found(vararg statuses: String, outcome: String? = null) =
        service.sweepRuns(null, null, null, outcome, null, statuses.toList(), null, null, 0, 50)

    @Test
    fun `the status of a row is the run list's status for a one-token run, for every kind of row`() {
        RunStatus.entries.forEach { status ->
            val expected = seeded.filter {
                RunStatus.of(if (it.position == null) 0 else 1, if (it.position == PositionState.OPEN) 1 else 0, BigDecimal(it.realized)) == status
            }.map { "item-${seeded.indexOf(it) + 1}" }

            assertEquals(expected.sorted(), ids(found(status.name).page.items).sorted(), status.name)
            assertEquals(expected.size.toLong(), found(status.name).page.total, status.name)
        }
    }

    @Test
    fun `no status leaves every row, several statuses are a union`() {
        assertEquals(6L, found().page.total)
        assertEquals(3L, found("LOSS", "FLAT").page.total)
    }

    @Test
    fun `status counts have all five statuses, zero included, under every filter but the status`() {
        val all = found("LOSS")

        assertEquals(
            mapOf(RunStatus.PROFIT to 1L, RunStatus.LOSS to 2L, RunStatus.FLAT to 1L, RunStatus.NO_ENTRY to 1L, RunStatus.OPEN to 1L),
            all.statusCounts,
        )
        assertEquals(RunStatus.entries.toList(), all.statusCounts.keys.toList())
        assertEquals(1L, found(outcome = "STOP_LOSS").statusCounts.getValue(RunStatus.LOSS))
        assertEquals(0L, found(outcome = "STOP_LOSS").statusCounts.getValue(RunStatus.PROFIT))
    }

    @Test
    fun `totals sum the realized and worst case pnl of the selected statuses exactly at scale 12, and their rows equal the page total`() {
        val losses = found("LOSS")

        assertEquals(2L, losses.totals.rows)
        assertEquals(BigDecimal("-0.250000000001"), losses.totals.realizedPnl)
        assertEquals(BigDecimal("-0.250000000001"), losses.totals.worstCasePnl)
        assertEquals(12, losses.totals.realizedPnl.scale())
        assertEquals(losses.page.total, losses.totals.rows)

        val everything = found()
        assertEquals(BigDecimal("0.249999999999"), everything.totals.realizedPnl)
        assertEquals(BigDecimal("0.149999999999"), everything.totals.worstCasePnl)
        assertEquals(everything.page.total, everything.totals.rows)

        val held = found("OPEN")
        assertEquals(BigDecimal("0.000000000000"), held.totals.realizedPnl)
        assertEquals(BigDecimal("-0.100000000000"), held.totals.worstCasePnl)
    }

    @Test
    fun `status and outcome combine with and, and nothing matching gives zero totals at scale 12`() {
        val none = found("LOSS", outcome = "TAKE_PROFIT")

        assertEquals(0L, none.page.total)
        assertEquals(0L, none.totals.rows)
        assertEquals("0.000000000000", none.totals.realizedPnl.toPlainString())
        assertEquals("0.000000000000", none.totals.worstCasePnl.toPlainString())
        assertEquals(1L, found("LOSS", outcome = "STOP_LOSS").page.total)
    }

    @Test
    fun `status combines with session, mint and block time, and the total, the totals and the counts agree under every combination`() {
        items.save(item(7, "s2", blockTime = EVENING.plusSeconds(100)))
        sweepRuns.save(sweepRun("r-s2-loss", "item-7", "V00", sessionId = "s2", outcome = "STOP_LOSS", runId = "run-s2", positionState = PositionState.CLOSED,
            realizedPnl = "-1", worstCasePnl = "-1"))

        fun page(statuses: List<String>, sessionId: String? = null, mint: String? = null, from: Instant? = null, to: Instant? = null) =
            service.sweepRuns(sessionId, mint, null, null, null, statuses, from, to, 0, 50)

        assertEquals(1L, page(listOf("LOSS"), sessionId = "s2").page.total)
        assertEquals(2L, page(listOf("LOSS"), sessionId = "session-1").page.total)
        assertEquals(BigDecimal("-1.000000000000"), page(listOf("LOSS"), sessionId = "s2").totals.realizedPnl)
        assertEquals(1L, page(listOf("LOSS", "PROFIT"), mint = AutomationFixtures.mint(2).value).page.total)
        assertEquals(1L, page(listOf("LOSS"), from = EVENING.plusSeconds(50)).page.total)
        assertEquals(1L, page(listOf("PROFIT"), to = EVENING.plusSeconds(1)).page.total)
        listOf(
            page(emptyList()) to RunStatus.entries.toSet(),
            page(listOf("LOSS")) to setOf(RunStatus.LOSS),
            page(listOf("LOSS", "OPEN"), sessionId = "session-1") to setOf(RunStatus.LOSS, RunStatus.OPEN),
            page(listOf("NO_ENTRY"), to = EVENING) to setOf(RunStatus.NO_ENTRY),
            page(listOf("PROFIT", "FLAT"), mint = AutomationFixtures.mint(1).value) to setOf(RunStatus.PROFIT, RunStatus.FLAT),
        ).forEach { (result, selected) ->
            assertEquals(result.page.total, result.totals.rows)
            assertEquals(result.page.total, selected.sumOf { result.statusCounts.getValue(it) })
            assertEquals(result.page.total, result.page.items.size.toLong())
        }
    }

    @Test
    fun `an unknown status is an invalid request naming the status field`() {
        val failure = assertFailsWith<InvalidAutomationRequestException> { found("WON") }

        assertEquals("status", failure.field)
    }
}
