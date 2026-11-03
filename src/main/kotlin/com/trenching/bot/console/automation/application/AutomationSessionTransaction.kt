package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemTransitions
import com.trenching.bot.console.automation.domain.AutomationOperationRef
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One session's state change per transaction (plan Rule 10); each re-reads the session, so a repeat changes nothing. */
@Service
@ConditionalOnAutomationConsole
class AutomationSessionTransaction(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
) {
    private val log = LoggerFactory.getLogger(AutomationSessionTransaction::class.java)

    /** An open session whose idle time discovery has passed starts closing. */
    @Transactional
    fun markClosing(sessionId: String): Boolean {
        val session = sessions.findOneById(sessionId) ?: return false
        if (session.state != AutomationSessionState.OPEN) return false
        session.state = AutomationSessionState.CLOSING
        log.info("automation session closing sessionId={} lastBuyAt={}", session.id, session.lastBuyAt)
        return true
    }

    /** A closing session closes `IDLE` once every item is in a [settled] state. */
    @Transactional
    fun closeIfSettled(sessionId: String, settled: Set<AutomationItemState>, now: Instant): Boolean {
        val session = sessions.findOneById(sessionId) ?: return false
        if (session.state != AutomationSessionState.CLOSING || items.countBySessionIdAndStateNotIn(sessionId, settled) > 0) return false
        close(session, SessionCloseReason.IDLE, now)
        return true
    }

    /**
     * The manual end: every item not yet terminal is abandoned and the session closes `MANUAL`; a closed session is left as it
     * is. Returns the capture operations the abandoned items had started, which the caller cancels once this has committed.
     */
    @Transactional
    fun closeManually(sessionId: String, now: Instant): List<AutomationOperationRef> {
        val session = sessions.findOneById(sessionId) ?: throw AutomationSessionNotFoundException(sessionId)
        if (session.state == AutomationSessionState.CLOSED) return emptyList()
        val started = items.findAllBySessionIdAndStateNotIn(sessionId, AutomationItemState.TERMINAL).mapNotNull { item ->
            AutomationItemTransitions.require(item.id, item.state, AutomationItemState.ABANDONED)
            item.state = AutomationItemState.ABANDONED
            item.nextActionAt = null
            item.updatedAt = now
            log.info("automation item advanced itemId={} step={} state={}", item.id, item.step, item.state)
            startedOperation(item)
        }
        close(session, SessionCloseReason.MANUAL, now)
        return started
    }

    private fun startedOperation(item: AutomationItemEntity): AutomationOperationRef? {
        val captureSessionId = item.captureSessionId ?: return null
        val operationId = item.operationId ?: return null
        val step = item.step ?: return null
        return AutomationOperationRef(item.id, captureSessionId, operationId, step, item.stepAttempt)
    }

    private fun close(session: AutomationSessionEntity, reason: SessionCloseReason, now: Instant) {
        session.state = AutomationSessionState.CLOSED
        session.closeReason = reason
        session.closedAt = now
        log.info("automation session closed sessionId={} reason={} items={}", session.id, reason, items.countBySessionId(session.id))
    }
}
