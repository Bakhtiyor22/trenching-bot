package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.SweepPlan
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Scores closed sessions under the active sweep plan (plan Rules 24–26), only while `capture-enabled` is on. On every tick it
 * evaluates up to [AutomationLimits.EVALUATIONS_PER_TICK] evaluable sessions with no evaluation yet, oldest first buy first,
 * each in its own transaction; one that fails is logged and tried again on the next tick. A session closed by hand is evaluated
 * at once when it is evaluable, otherwise by a later tick. A session is evaluable under the active plan only when its items
 * have sweep rows under it, so phase-0 sessions and sessions swept under an earlier plan are never scored under it.
 *
 * When the tick and a manual close score the same session at once, the second to commit hits the evaluation's unique key and
 * returns the first one's rows (Rule 26: nothing is replaced).
 */
@Service
@ConditionalOnAutomationConsole
class AutomationEvaluationService(
    private val transaction: AutomationEvaluationTransaction,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationEvaluationService::class.java)

    fun evaluateClosedSessions() {
        if (!properties.captureEnabled) return
        val plan = properties.activePlan()
        transaction.pending(plan.id, AutomationLimits.EVALUATIONS_PER_TICK).forEach { sessionId ->
            try {
                evaluate(sessionId, plan)
            } catch (e: Exception) {
                log.error("automation session evaluation failed sessionId={}", sessionId, e)
            }
        }
    }

    /** The session's evaluations under the active plan, scored now if it has none; empty with capture off or for an unevaluable session. */
    fun evaluate(sessionId: String): List<AutomationEvaluationView> =
        if (properties.captureEnabled) evaluate(sessionId, properties.activePlan()) else emptyList()

    private fun evaluate(sessionId: String, plan: SweepPlan): List<AutomationEvaluationView> = try {
        transaction.evaluate(sessionId, plan, properties.evaluationWindowSessions, properties.evaluationRule(), clock.instant())
    } catch (e: DataIntegrityViolationException) {
        val stored = transaction.stored(sessionId, plan.id)
        if (stored.isEmpty()) throw e
        log.info("automation session evaluated concurrently, kept the stored rows sessionId={} planId={}", sessionId, plan.id)
        stored
    }
}
