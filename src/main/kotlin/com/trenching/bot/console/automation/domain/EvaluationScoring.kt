package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.DecimalPolicy
import java.math.BigDecimal

/** One stored sweep run of an item, as far as scoring reads it. P&L is SOL at scale 12. */
data class SweepRunScore(
    val itemId: String,
    val label: String,
    val runId: String?,
    val outcome: String,
    val signal: Boolean,
    val realizedPnl: BigDecimal,
    val worstCasePnl: BigDecimal,
)

/** One item of an evaluation window: its state and its sweep runs under the evaluated plan, by label. */
data class WindowItem(val id: String, val state: AutomationItemState, val runs: Map<String, SweepRunScore>)

/** One entry's counts over a window (plan Rule 24); the P&L sums run over its signals only. */
data class EvaluationTally(val signals: Int, val scored: Int, val unscored: Int, val realizedPnl: BigDecimal, val worstCasePnl: BigDecimal)

/** Plan Rule 25: at least [minSignals] signals and a worst-case P&L strictly above [minNetPnlSol] succeed. */
data class EvaluationRule(val minSignals: Int, val minNetPnlSol: BigDecimal) {
    fun status(tally: EvaluationTally): EvaluationStatus = when {
        tally.signals < minSignals -> EvaluationStatus.NO_RESULT
        tally.worstCasePnl > minNetPnlSol -> EvaluationStatus.SUCCESS
        else -> EvaluationStatus.NO_SUCCESS
    }
}

/**
 * Plan Rules 22 and 24 (clarified 2026-11-03) for one entry: a simulated item counts by its row for the label. `unscored` is
 * every failed or abandoned item, every simulated item without a row for the label, and every row refused or without a usable
 * result ([SweepOutcomes.unscored]); `scored` is every other row, so the two never overlap. A buy below the minimum size is a
 * scored non-signal, and an item whose first buy is unproven counts nowhere.
 */
object EvaluationScoring {
    fun tally(label: String, items: List<WindowItem>): EvaluationTally {
        val runs = items.filter { it.state == AutomationItemState.SIMULATED }.mapNotNull { it.runs[label] }
        val signals = runs.filter { it.signal }
        val (unusable, usable) = runs.partition { SweepOutcomes.unscored(it.outcome) }
        return EvaluationTally(signals.size, usable.size, items.count { unscored(it, label) } + unusable.size,
            sum(signals.map { it.realizedPnl }), sum(signals.map { it.worstCasePnl }))
    }

    /** An item that has no run for the label to count at all. */
    private fun unscored(item: WindowItem, label: String): Boolean = when (item.state) {
        AutomationItemState.SIMULATED -> label !in item.runs
        AutomationItemState.SKIPPED_NOT_FIRST_BUY -> false
        AutomationItemState.FAILED, AutomationItemState.ABANDONED, AutomationItemState.DISCOVERED, AutomationItemState.WAITING,
        AutomationItemState.BLOCKED_BUDGET, AutomationItemState.CAPTURING, AutomationItemState.CAPTURED -> true
    }

    private fun sum(values: List<BigDecimal>): BigDecimal = DecimalPolicy.scale(values.fold(BigDecimal.ZERO, BigDecimal::add))
}
