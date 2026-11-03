package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A backfill as asked for (plan Key Decisions 9 and 10), before [BackfillRules] has checked it; every field is nullable so a
 * missing one is a field error instead of a failed parse. [fromDay] and [toDay] are trading dates, both included.
 */
data class BackfillRequest(
    val requestId: String?,
    val fromDay: LocalDate?,
    val toDay: LocalDate?,
    val dryRun: Boolean?,
    val requestBudget: Long?,
    val minSignalSol: BigDecimal?,
    val mints: List<String>?,
)
