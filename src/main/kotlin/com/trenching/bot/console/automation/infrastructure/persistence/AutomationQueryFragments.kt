package com.trenching.bot.console.automation.infrastructure.persistence

private const val EVALUATION_STATUS = "com.trenching.bot.console.automation.domain.EvaluationStatus"

/** [com.trenching.bot.console.automation.domain.EvaluationStatus.rank] of evaluation `e` in JPQL; kept in step with the enum by the query tests. */
internal const val RANK_OF_E = "case e.status when $EVALUATION_STATUS.SUCCESS then 0 when $EVALUATION_STATUS.NO_SUCCESS then 1 else 2 end"

/** The same rank for evaluation `b`, where a query compares two evaluations of one session. */
internal const val RANK_OF_B = "case b.status when $EVALUATION_STATUS.SUCCESS then 0 when $EVALUATION_STATUS.NO_SUCCESS then 1 else 2 end"

/** Ranks after every status: a session with no evaluation sorts last. */
internal const val UNRANKED = 3
