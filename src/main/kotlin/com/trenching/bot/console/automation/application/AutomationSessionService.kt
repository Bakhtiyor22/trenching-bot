package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationRequests
import com.trenching.bot.console.automation.domain.AutomationSessionDetail
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * Ends sessions (plan Rule 10, amended 2026-11-03): on each tick an open session starts closing once a complete discovery scan
 * covers more than `session-idle` after its last buy ([AutomationDiscoveryTransaction.discoveredThrough], the scan's start less
 * the indexing margin), so a buy discovery has not reached yet can still join it; the clock alone never closes one. A closing session closes once its items
 * are settled. With capture off, a `DISCOVERED` item counts as settled, so phase 0 sessions close. Each session changes in its
 * own transaction; one that fails is logged and the tick goes on.
 *
 * A session closed by hand has the operations of its abandoned items cancelled, then is evaluated at once, or on a later tick
 * while an earlier session of its wallet is still open.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationSessionService(
    private val transaction: AutomationSessionTransaction,
    private val discovery: AutomationDiscoveryTransaction,
    private val sessions: AutomationSessionRepository,
    private val cancellation: AutomationCaptureCancellation,
    private val query: AutomationQueryService,
    private val evaluation: AutomationEvaluationService,
    private val properties: AutomationProperties,
    private val runtime: AutomationRuntime,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationSessionService::class.java)

    fun closeIdleSessions() {
        val now = clock.instant()
        runtime.ticked(now)
        val idle = properties.idleRule()
        discovery.discoveredThrough().forEach { (wallet, through) ->
            sessions.findIdsByWalletAndStateAndLastBuyAtBefore(wallet, AutomationSessionState.OPEN, idle.idleBefore(through), BATCH).forEach { id ->
                isolated(id) { transaction.markClosing(id) }
            }
        }
        val settled = AutomationItemState.settled(properties.captureEnabled)
        sessions.findIdsByState(AutomationSessionState.CLOSING, BATCH).forEach { id ->
            isolated(id) { transaction.closeIfSettled(id, settled, now) }
        }
    }

    /**
     * Closes a session by hand now, abandoning its pending items, captured ones included, and cancelling the capture operations
     * they started; then evaluates it. Closing a closed session again returns it unchanged.
     */
    fun close(sessionId: String, requestId: String?): AutomationSessionDetail {
        val validRequestId = AutomationRequests.requireRequestId(requestId)
        log.info("automation session close requested sessionId={} requestId={}", sessionId, validRequestId)
        transaction.closeManually(sessionId, clock.instant()).forEach(cancellation::cancel)
        evaluation.evaluate(sessionId)
        return query.session(sessionId)
    }

    private fun isolated(sessionId: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            log.error("automation session step failed sessionId={}", sessionId, e)
        }
    }

    private companion object {
        const val MAX_SESSIONS_PER_TICK = 100
        val BATCH: PageRequest = PageRequest.of(0, MAX_SESSIONS_PER_TICK)
    }
}
