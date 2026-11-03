package com.trenching.bot.console.automation.domain

import java.math.BigDecimal

/** One sweep entry's evaluation of a session, as far as ranking needs it. */
data class EvaluationScore(val label: String, val status: EvaluationStatus, val worstCasePnl: BigDecimal)

/** A session's best evaluation: SUCCESS › NO_SUCCESS › NO_RESULT, then the highest worst-case P&L, then the first label. */
object EvaluationRanking {
    private val BEST_FIRST: Comparator<EvaluationScore> =
        compareBy<EvaluationScore> { it.status.rank }.thenByDescending { it.worstCasePnl }.thenBy { it.label }

    fun best(scores: List<EvaluationScore>): EvaluationScore? = scores.minWithOrNull(BEST_FIRST)
}
