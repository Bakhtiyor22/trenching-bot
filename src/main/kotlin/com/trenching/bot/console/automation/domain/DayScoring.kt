package com.trenching.bot.console.automation.domain

import java.time.LocalDate

/**
 * What the stored rows say about one trading day (plan Rule 33): whether discovery [covered] all of it, the states of its
 * [sessions] and its [items] with their sweep rows under the scored plan. A day is [complete] only when covered, every session
 * is closed and every item is terminal, none of them stopped by its backfill's request budget (a later backfill can fill it,
 * so the day waits for it); a covered day with no buys is complete with no signals.
 */
data class DayFacts(val day: TradingDay, val covered: Boolean, val sessions: List<AutomationSessionState>, val items: List<WindowItem>) {
    val complete: Boolean
        get() = covered && sessions.all { it == AutomationSessionState.CLOSED } && items.all { it.state.terminal && !it.stoppedByBudget }
}

/** A day's standing under one configuration: a verdict, or still pending on the oldest incomplete day of its window. */
sealed interface DayStanding {
    /** Lower is better; a pending standing ranks after every verdict. */
    val rank: Int

    val label: String

    data class Decided(val status: EvaluationStatus) : DayStanding {
        override val rank: Int get() = status.rank
        override val label: String get() = status.name
    }

    /** [pendingDay] is the oldest day of the window that is not complete (plan Rule 34). */
    data class Pending(val pendingDay: TradingDay) : DayStanding {
        override val rank: Int get() = EvaluationStatus.entries.size
        override val label: String get() = PENDING
    }

    companion object {
        const val PENDING = "PENDING"

        val LABELS: List<String> = EvaluationStatus.entries.map { it.name } + PENDING
    }
}

/**
 * One configuration's row for [day]: its tally over [windowDays] (oldest first, ending with the day) and its [standing]. P&L is
 * SOL at scale 12, summed over the signals, as in [EvaluationTally].
 */
data class DayVerdict(
    val day: LocalDate,
    val planId: String,
    val label: String,
    val windowDays: List<LocalDate>,
    val tally: EvaluationTally,
    val standing: DayStanding,
) {
    val status: String get() = standing.label

    val pendingDay: LocalDate? get() = (standing as? DayStanding.Pending)?.pendingDay?.date
}

/**
 * Plan Rule 34: day D's row for each entry of [plan] tallies the items of the [windowDays] trading days ending with D under
 * Rules 22–24 and judges the tally with [rule], unless a day of the window is not complete, when the row is pending on the
 * oldest such day. A pure function of the stored rows: the same [DayFacts] always give the same rows.
 */
class DayScoring(private val plan: SweepPlan, private val windowDays: Int, private val rule: EvaluationRule) {
    init {
        require(windowDays >= 1) { "A verdict window has at least one day windowDays=$windowDays" }
    }

    /** The first day of a window ending with [day]; [facts] must hold every day from it through [day]. */
    fun windowStart(day: TradingDay): TradingDay = day.minusDays(windowDays - 1L)

    fun verdicts(day: TradingDay, facts: Map<TradingDay, DayFacts>): List<DayVerdict> {
        val window = windowStart(day).through(day).map { checkNotNull(facts[it]) { "No facts for a day of the verdict window day=${it.date}" } }
        val items = window.flatMap { it.items }
        val pending = window.firstOrNull { !it.complete }?.day
        return plan.entries.map { entry ->
            val tally = EvaluationScoring.tally(entry.label, items)
            val standing = pending?.let(DayStanding::Pending) ?: DayStanding.Decided(rule.status(tally))
            DayVerdict(day.date, plan.id, entry.label, window.map { it.day.date }, tally, standing)
        }
    }
}

/** The ranking of a day's rows. */
object DayStandings {
    private val BEST_FIRST: Comparator<DayVerdict> =
        compareBy<DayVerdict> { it.standing.rank }.thenByDescending { it.tally.worstCasePnl }.thenBy { it.label }

    /** The row that stands for the day: the best verdict (status, then highest worst-case P&L, then label), or any row while pending. */
    fun best(verdicts: List<DayVerdict>): DayVerdict? = verdicts.minWithOrNull(BEST_FIRST)
}
