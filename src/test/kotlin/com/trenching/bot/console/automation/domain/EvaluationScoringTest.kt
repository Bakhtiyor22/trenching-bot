package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** Plan Rules 22, 24 and 25: an entry's tally over a window's items, and its status. */
class EvaluationScoringTest {
    private val rule = EvaluationRule(minSignals = 10, minNetPnlSol = BigDecimal.ZERO)

    @Test
    fun `signals count the signal runs and sum their P&L, while the runs of no signal add nothing`() {
        val items = listOf(
            simulated("a", run("a", "TAKE_PROFIT", signal = true, realized = "0.03", worst = "0.03")),
            simulated("b", run("b", "HORIZON_REACHED", signal = true, realized = "0", worst = "-0.10225")),
            simulated("c", run("c", "NO_DIP", signal = true)),
            simulated("d", run("d", "SIGNAL_BELOW_MIN_SIZE", signal = false, realized = "5", worst = "5")),
        )

        val tally = EvaluationScoring.tally(LABEL, items)

        assertEquals(EvaluationTally(3, 4, 0, BigDecimal("0.030000000000"), BigDecimal("-0.072250000000")), tally)
    }

    @Test
    fun `failed and abandoned items and runs refused or without a usable result are unscored, never scored, and skipped first buys count nowhere`() {
        val items = listOf(
            item("failed", AutomationItemState.FAILED),
            item("abandoned", AutomationItemState.ABANDONED),
            item("skipped", AutomationItemState.SKIPPED_NOT_FIRST_BUY),
            simulated("refused", run("refused", "MARKET_COVERAGE_ENDED", signal = false, runId = null)),
            simulated("reference", run("reference", "REFERENCE_UNAVAILABLE", signal = false)),
            simulated("size", run("size", "SIGNAL_SIZE_UNAVAILABLE", signal = false)),
            simulated("none", run("none", SweepOutcomes.NO_CYCLE, signal = false)),
            simulated("below", run("below", "SIGNAL_BELOW_MIN_SIZE", signal = false)),
        )

        val tally = EvaluationScoring.tally(LABEL, items)

        assertEquals(0, tally.signals)
        assertEquals(1, tally.scored, "only the run below the minimum size has a usable result")
        assertEquals(6, tally.unscored)
    }

    @Test
    fun `every simulated item counts as scored or unscored, never both`() {
        val outcomes = listOf("TAKE_PROFIT", "NO_DIP", "SIGNAL_BELOW_MIN_SIZE", "REFERENCE_UNAVAILABLE", "SIGNAL_SIZE_UNAVAILABLE", SweepOutcomes.NO_CYCLE)
        val items = outcomes.mapIndexed { index, outcome -> simulated("i$index", run("i$index", outcome, signal = SweepOutcomes.signal(outcome))) } +
            simulated("refused", run("refused", "MARKET_COVERAGE_ENDED", signal = false, runId = null))

        val tally = EvaluationScoring.tally(LABEL, items)

        assertEquals(items.size, tally.scored + tally.unscored)
        assertEquals(listOf(3, 4), listOf(tally.scored, tally.unscored))
    }

    @Test
    fun `a simulated item without a run for the label is unscored, and another label's run does not count`() {
        val items = listOf(item("a", AutomationItemState.SIMULATED, mapOf("V01" to run("a", "TAKE_PROFIT", signal = true, label = "V01"))))

        assertEquals(EvaluationTally(0, 0, 1, ZERO, ZERO), EvaluationScoring.tally(LABEL, items))
    }

    @Test
    fun `ten signals with a worst case above zero succeed`() {
        assertEquals(EvaluationStatus.SUCCESS, rule.status(tally(signals = 10, worst = "0.000000000001")))
    }

    @Test
    fun `a worst case of exactly zero is no success, however it is scaled`() {
        assertEquals(EvaluationStatus.NO_SUCCESS, rule.status(tally(signals = 10, worst = "0")))
        assertEquals(EvaluationStatus.NO_SUCCESS, rule.status(tally(signals = 10, worst = "0E-12")))
        assertEquals(EvaluationStatus.NO_SUCCESS, rule.status(tally(signals = 25, worst = "-3")))
    }

    @Test
    fun `nine signals are no result, whatever the P&L`() {
        assertEquals(EvaluationStatus.NO_RESULT, rule.status(tally(signals = 9, worst = "100")))
        assertEquals(EvaluationStatus.NO_RESULT, rule.status(tally(signals = 0, worst = "0")))
    }

    @Test
    fun `the threshold is compared by value`() {
        val threshold = EvaluationRule(minSignals = 1, minNetPnlSol = BigDecimal("0.5"))

        assertEquals(EvaluationStatus.NO_SUCCESS, threshold.status(tally(signals = 1, worst = "0.500000000000")))
        assertEquals(EvaluationStatus.SUCCESS, threshold.status(tally(signals = 1, worst = "0.500000000001")))
    }

    private companion object {
        const val LABEL = "V00"
        val ZERO: BigDecimal = BigDecimal("0E-12")

        fun tally(signals: Int, worst: String) = EvaluationTally(signals, signals, 0, BigDecimal(worst), BigDecimal(worst))

        fun item(id: String, state: AutomationItemState, runs: Map<String, SweepRunScore> = emptyMap()) = WindowItem(id, state, runs)

        fun simulated(id: String, run: SweepRunScore) = item(id, AutomationItemState.SIMULATED, mapOf(run.label to run))

        fun run(itemId: String, outcome: String, signal: Boolean, realized: String = "0", worst: String = realized, runId: String? = "run-$itemId", label: String = LABEL) =
            SweepRunScore(itemId, label, runId, outcome, signal, BigDecimal(realized), BigDecimal(worst))
    }
}
