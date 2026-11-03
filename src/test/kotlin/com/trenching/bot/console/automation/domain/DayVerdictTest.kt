package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rules 33 and 34 and Key Decisions 4 and 5: a day's verdict over its window of trading days, from stored rows alone. */
class DayVerdictTest {
    private val plan = SweepPlans.DECU_SWEEP_1
    private val rule = EvaluationRule(minSignals = 10, minNetPnlSol = BigDecimal.ZERO)
    private val scoring = DayScoring(plan, windowDays = 7, rule)
    private val today = TradingDay(LocalDate.parse("2026-11-10"))

    @Test
    fun `seven complete days give the approved verdict per configuration`() {
        val facts = week(signalsPerDay = 2) { label -> if (label == "V00") "0.01" else "-0.01" }

        val all = scoring.verdicts(today, facts)
        val rows = all.associateBy { it.label }

        assertEquals(plan.entries.map { it.label }, all.map { it.label })
        assertEquals(14, rows.getValue("V00").tally.signals)
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), rows.getValue("V00").standing)
        assertEquals(BigDecimal("0.140000000000"), rows.getValue("V00").tally.worstCasePnl)
        assertEquals(DayStanding.Decided(EvaluationStatus.NO_SUCCESS), rows.getValue("V01").standing)
        assertEquals(windowDates(), rows.getValue("V00").windowDays)
        assertEquals(today.date, rows.getValue("V00").day)
        assertEquals(plan.id, rows.getValue("V00").planId)
    }

    @Test
    fun `fewer signals than the minimum are no result, exactly zero net is no success, the smallest step above it a success`() {
        val base = week(signalsPerDay = 0)
        val nine = base + (today to dayWith(today, 9, "0.01"))
        val ten = base + (today to dayWith(today, 10, "0"))
        val tenTiny = base + (today to dayWith(today, 10, "0.000000000001"))

        assertEquals(DayStanding.Decided(EvaluationStatus.NO_RESULT), scoring.verdicts(today, nine).first().standing)
        assertEquals(DayStanding.Decided(EvaluationStatus.NO_SUCCESS), scoring.verdicts(today, ten).first().standing)
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), scoring.verdicts(today, tenTiny).first().standing)
    }

    @Test
    fun `an incomplete day makes every row pending, naming the oldest incomplete day of the window`() {
        val facts = week(signalsPerDay = 2).toMutableMap()
        facts[today.minusDays(2)] = facts.getValue(today.minusDays(2)).copy(covered = false)
        facts[today.minusDays(4)] = facts.getValue(today.minusDays(4)).copy(sessions = listOf(AutomationSessionState.CLOSING))

        val rows = scoring.verdicts(today, facts)

        assertTrue(rows.all { it.standing == DayStanding.Pending(today.minusDays(4)) })
        assertEquals(listOf(LocalDate.parse("2026-11-06")), rows.map { it.pendingDay }.distinct())
        assertEquals(listOf("PENDING"), rows.map { it.status }.distinct())
        assertEquals(plan.entries.size, rows.size)
        assertEquals(14, rows.first().tally.signals, "the tally still counts what is stored")
    }

    @Test
    fun `a day outside the window does not hold a verdict back`() {
        val facts = week(signalsPerDay = 2) + (today.minusDays(7) to DayFacts(today.minusDays(7), false, emptyList(), emptyList()))

        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), scoring.verdicts(today, facts).first().standing)
    }

    @Test
    fun `a day with no buys is complete when covered and counts zero signals`() {
        val gap = DayFacts(today.minusDays(3), covered = true, sessions = emptyList(), items = emptyList())
        val facts = week(signalsPerDay = 2) + (gap.day to gap)

        val row = scoring.verdicts(today, facts).first()

        assertTrue(gap.complete)
        assertEquals(12, row.tally.signals, "six days of two")
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), row.standing)
        val fewer = facts + (today to dayWith(today, 0, "0.01"))
        assertEquals(10, scoring.verdicts(today, fewer).first().tally.signals, "an empty day adds none")
        val nine = fewer + (today.minusDays(1) to dayWith(today.minusDays(1), 1, "0.01"))
        assertEquals(DayStanding.Decided(EvaluationStatus.NO_RESULT), scoring.verdicts(today, nine).first().standing)
    }

    @Test
    fun `a backfilled older day changes a newer day's row on the next read`() {
        val before = week(signalsPerDay = 1) + (today.minusDays(6) to DayFacts(today.minusDays(6), false, emptyList(), emptyList()))
        val after = before + (today.minusDays(6) to dayWith(today.minusDays(6), signals = 4, worst = "0.01"))

        val pending = scoring.verdicts(today, before).first()
        val decided = scoring.verdicts(today, after).first()

        assertEquals(DayStanding.Pending(today.minusDays(6)), pending.standing)
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), decided.standing)
        assertEquals(10, decided.tally.signals)
    }

    @Test
    fun `the window length is the configured number of trading days, the day itself included`() {
        val facts = week(signalsPerDay = 2).toMutableMap()
        facts[today.minusDays(3)] = facts.getValue(today.minusDays(3)).copy(covered = false)

        val three = DayScoring(plan, windowDays = 3, EvaluationRule(minSignals = 4, minNetPnlSol = BigDecimal.ZERO)).verdicts(today, facts).first()

        assertEquals(6, three.tally.signals)
        assertEquals(listOf(today.minusDays(2).date, today.minusDays(1).date, today.date), three.windowDays)
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), three.standing)
    }

    @Test
    fun `a day is complete only when covered, every session is closed and every item is terminal`() {
        val closed = listOf(AutomationSessionState.CLOSED)

        fun facts(covered: Boolean = true, sessions: List<AutomationSessionState> = closed, vararg items: AutomationItemState) =
            DayFacts(today, covered, sessions, items.map { WindowItem(it.name, it, emptyMap()) })

        val terminal = arrayOf(AutomationItemState.SIMULATED, AutomationItemState.FAILED, AutomationItemState.ABANDONED, AutomationItemState.SKIPPED_NOT_FIRST_BUY)
        assertTrue(facts(items = terminal).complete)
        assertTrue(facts(sessions = emptyList()).complete, "covered with no buys")
        assertFalse(facts(covered = false).complete)
        assertFalse(facts(sessions = listOf(AutomationSessionState.CLOSED, AutomationSessionState.OPEN)).complete)
        assertFalse(facts(sessions = listOf(AutomationSessionState.CLOSING)).complete)
        listOf(AutomationItemState.DISCOVERED, AutomationItemState.WAITING, AutomationItemState.BLOCKED_BUDGET, AutomationItemState.CAPTURING,
            AutomationItemState.CAPTURED).forEach { assertFalse(facts(items = arrayOf(it)).complete, it.name) }
    }

    @Test
    fun `the same stored rows always give the same rows`() {
        val facts = week(signalsPerDay = 2)

        assertEquals(scoring.verdicts(today, facts), scoring.verdicts(today, facts))
    }

    @Test
    fun `a day's best standing is its best decided row, or pending`() {
        val facts = week(signalsPerDay = 2) { label -> if (label == "V03") "0.02" else "-0.01" }
        val pending = facts + (today.minusDays(1) to facts.getValue(today.minusDays(1)).copy(covered = false))

        val best = DayStandings.best(scoring.verdicts(today, facts))
        val none = DayStandings.best(scoring.verdicts(today, pending))

        assertEquals("V03", best?.label)
        assertEquals(DayStanding.Decided(EvaluationStatus.SUCCESS), best?.standing)
        assertEquals(DayStanding.Pending(today.minusDays(1)), none?.standing)
        assertEquals(null, DayStandings.best(emptyList()))
    }

    @Test
    fun `equal standings and P&L rank by the first label, and success outranks a higher P&L without success`() {
        val tied = week(signalsPerDay = 2) { "-0.01" }
        val allSucceed = week(signalsPerDay = 2) { label -> if (label == "V09") "0.001" else "9" }

        assertEquals("V00", DayStandings.best(scoring.verdicts(today, tied))?.label)
        assertEquals("V00", DayStandings.best(scoring.verdicts(today, allSucceed))?.label, "all succeed, the highest P&L ties and the first label wins")
        val mixed = week(signalsPerDay = 2) { label -> if (label == "V09") "0.001" else "-9" }
        assertEquals("V09", DayStandings.best(scoring.verdicts(today, mixed))?.label)
    }

    private fun windowDates(): List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()).date }

    /** The seven days ending [today], each complete with [signalsPerDay] signal items, worst case given by [worst] per label. */
    private fun week(signalsPerDay: Int, worst: (String) -> String = { "0.01" }): Map<TradingDay, DayFacts> =
        (6 downTo 0).map { today.minusDays(it.toLong()) }.associateWith { day -> dayWith(day, signalsPerDay, worst) }

    private fun dayWith(day: TradingDay, signals: Int, worst: String) = dayWith(day, signals) { worst }

    private fun dayWith(day: TradingDay, signals: Int, worst: (String) -> String): DayFacts {
        val items = (1..signals).map { n ->
            val id = "${day.date}-$n"
            WindowItem(id, AutomationItemState.SIMULATED, plan.entries.associate { it.label to run(id, it.label, worst(it.label)) })
        }
        return DayFacts(day, covered = true, sessions = if (items.isEmpty()) emptyList() else listOf(AutomationSessionState.CLOSED), items = items)
    }

    private fun run(itemId: String, label: String, worst: String) =
        SweepRunScore(itemId, label, "run-$itemId-$label", "TAKE_PROFIT", true, BigDecimal(worst), BigDecimal(worst))
}
