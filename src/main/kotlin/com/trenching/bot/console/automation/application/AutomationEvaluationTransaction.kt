package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.EvaluationRule
import com.trenching.bot.console.automation.domain.EvaluationScoring
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.WindowItem
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.console.automation.mapper.EvaluationWindowMapper
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Scores one closed session per transaction (plan Rules 24–26). A session is scored under a plan only once its items have sweep
 * rows under that plan and every earlier session of its wallet is closed. The window is the session and up to
 * `windowSessions − 1` earlier closed sessions of its wallet that are already evaluated under the plan, by first buy time. Every
 * entry of the plan gets one row at once, inserted and flushed in the transaction; a session evaluated before returns its stored
 * rows, never replaced, and the unique key refuses a concurrent second set with a
 * [org.springframework.dao.DataIntegrityViolationException], after which [stored] reads the winner's rows.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationEvaluationTransaction(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val evaluations: AutomationEvaluationRepository,
    private val mapper: AutomationRecordMapper,
    private val window: EvaluationWindowMapper,
) {
    private val log = LoggerFactory.getLogger(AutomationEvaluationTransaction::class.java)

    /** Evaluable sessions not yet scored under [planId], oldest first buy first. */
    @Transactional(readOnly = true)
    fun pending(planId: String, limit: Int): List<String> = sessions.findIdsToEvaluate(planId, PageRequest.of(0, limit))

    /** The session's stored rows under [planId], one per entry, or none. */
    @Transactional(readOnly = true)
    fun stored(sessionId: String, planId: String): List<AutomationEvaluationView> =
        evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc(sessionId, planId).map(mapper::toEvaluation)

    /** The session's rows under [plan], scored now if it has none; empty for a session not evaluable under [plan] yet. */
    @Transactional
    fun evaluate(sessionId: String, plan: SweepPlan, windowSessions: Int, rule: EvaluationRule, now: Instant): List<AutomationEvaluationView> {
        val stored = evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc(sessionId, plan.id)
        if (stored.isNotEmpty()) return stored.map(mapper::toEvaluation)
        if (sessions.countEvaluable(sessionId, plan.id) == 0L) return emptyList()
        val session = sessions.findOneById(sessionId) ?: throw AutomationSessionNotFoundException(sessionId)
        val windowIds = earlierSessions(session, plan.id, windowSessions - 1) + session.id
        val windowItems = windowItems(windowIds, plan.id)
        val storedWindow = window.toStored(windowIds)
        val rows = plan.entries.map { entry ->
            val tally = EvaluationScoring.tally(entry.label, windowItems)
            AutomationEvaluationEntity(
                id = AutomationIds.evaluation(session.id, plan.id, entry.label), sessionId = session.id, planId = plan.id,
                label = entry.label, windowSessionIds = storedWindow, windowSessions = windowIds.size, signals = tally.signals,
                scored = tally.scored, unscored = tally.unscored, realizedPnl = tally.realizedPnl, worstCasePnl = tally.worstCasePnl,
                status = rule.status(tally), evaluatedAt = now,
            )
        }
        evaluations.saveAllAndFlush(rows)
        log.info("automation session evaluated sessionId={} planId={} windowSessions={} items={} statuses={}",
            session.id, plan.id, windowIds.size, windowItems.size, rows.groupingBy { it.status }.eachCount())
        return rows.map(mapper::toEvaluation)
    }

    /** Up to [count] earlier evaluated sessions of the wallet, oldest first. */
    private fun earlierSessions(session: AutomationSessionEntity, planId: String, count: Int): List<String> {
        if (count == 0) return emptyList()
        return sessions.findEvaluatedBefore(session.wallet, planId, session.firstBuyAt, session.id, PageRequest.of(0, count)).map { it.id }.reversed()
    }

    private fun windowItems(sessionIds: List<String>, planId: String): List<WindowItem> {
        val runsByItem = sweepRuns.findAllBySessionIdInAndPlanId(sessionIds, planId).map(mapper::toSweepScore).groupBy { it.itemId }
        return items.findStatesBySessionIdIn(sessionIds, PageRequest.of(0, sessionIds.size * AutomationLimits.MAX_ITEMS_PER_SESSION))
            .map { WindowItem(it.id, it.state, runsByItem[it.id].orEmpty().associateBy { run -> run.label }) }
    }
}
