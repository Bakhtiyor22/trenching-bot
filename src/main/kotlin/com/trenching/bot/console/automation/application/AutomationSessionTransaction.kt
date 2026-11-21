package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemEvents
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
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
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
    private val mapper: AutomationRecordMapper,
    private val events: AutomationItemEventWriter,
) {
    private val log = LoggerFactory.getLogger(AutomationSessionTransaction::class.java)

    /** An open session whose idle time discovery has passed starts closing. */
    @Transactional
    fun markClosing(sessionId: String): Boolean {
        val session = sessions.findOneById(sessionId) ?: return false
        if (session.state != AutomationSessionState.OPEN) return false
        session.state = AutomationSessionState.CLOSING
        LogLine("Session is closing: its idle time has passed.").field("sessionId", session.id).field("wallet", session.wallet)
            .field("lastBuyAt", session.lastBuyAt).field("backfillId", session.backfillId).debug(log)
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
     * The manual end: every item not yet terminal is abandoned and the session closes `MANUAL`. A session that is already closed
     * keeps its state and reason, but its non-terminal items (the phase 0 backlog) are abandoned all the same, each with an event
     * under the close's [requestId]. Returns the capture operations the abandoned items had started, which the caller cancels once
     * this has committed.
     */
    @Transactional
    fun closeManually(sessionId: String, requestId: String, now: Instant): List<AutomationOperationRef> {
        val session = sessions.findOneById(sessionId) ?: throw AutomationSessionNotFoundException(sessionId)
        val abandoned = mutableMapOf<String, AutomationItemChange>()
        val started = items.findAllBySessionIdAndStateNotIn(sessionId, AutomationItemState.TERMINAL).mapNotNull { item ->
            AutomationItemTransitions.require(item.id, item.state, AutomationItemState.ABANDONED)
            val before = mapper.toStanding(item)
            item.state = AutomationItemState.ABANDONED
            item.nextActionAt = null
            item.updatedAt = now
            abandoned[item.id] = AutomationItemChange(before, AutomationItemEvents.closed(before.progress, mapper.toProgress(item), requestId))
            startedOperation(item)
        }
        events.appendAll(now, abandoned)
        if (session.state != AutomationSessionState.CLOSED) close(session, SessionCloseReason.MANUAL, now)
        return started
    }

    private fun startedOperation(item: AutomationItemEntity): AutomationOperationRef? {
        val captureSessionId = item.captureSessionId ?: return null
        val operationId = item.operationId ?: return null
        val step = item.step ?: return null
        return AutomationOperationRef(item.id, captureSessionId, operationId, step, item.stepAttempt, item.retries)
    }

    private fun close(session: AutomationSessionEntity, reason: SessionCloseReason, now: Instant) {
        session.state = AutomationSessionState.CLOSED
        session.closeReason = reason
        session.closedAt = now
        val closed = LogLine("Session closed: {}.", reason.meaning).field("sessionId", session.id).field("wallet", session.wallet)
            .field("items", items.countBySessionId(session.id)).field("firstBuyAt", session.firstBuyAt).field("lastBuyAt", session.lastBuyAt)
            .field("backfillId", session.backfillId)
        AfterCommit.run { if (session.backfillId == null) closed.info(log) else closed.debug(log) }
    }
}
