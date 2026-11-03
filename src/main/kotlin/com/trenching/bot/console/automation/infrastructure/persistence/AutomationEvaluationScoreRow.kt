package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.EvaluationStatus
import java.math.BigDecimal

/** Closed projection of `automation_evaluations` with what ranking a session needs. */
interface AutomationEvaluationScoreRow {
    val sessionId: String
    val label: String
    val status: EvaluationStatus
    val worstCasePnl: BigDecimal
}
