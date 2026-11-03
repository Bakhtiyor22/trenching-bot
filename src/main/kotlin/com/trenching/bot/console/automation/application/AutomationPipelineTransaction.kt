package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemTransitions
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The pipeline's reads and writes, one short transaction each, so no transaction is ever held across a capture call. A write
 * applies only to the progress the tick read: an item changed meanwhile, for example abandoned by a manual close, is left as
 * it is and the capture result is ignored.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationPipelineTransaction(
    private val items: AutomationItemRepository,
    private val mapper: AutomationRecordMapper,
) {
    private val log = LoggerFactory.getLogger(AutomationPipelineTransaction::class.java)

    /** Items in capture whose next action has come, in sessions still open or closing, oldest buy first. */
    @Transactional(readOnly = true)
    fun due(now: Instant): List<AutomationDueItem> =
        items.findDue(AutomationItemState.IN_CAPTURE, ACTIVE_SESSIONS, now, BATCH).map(mapper::toDueItem)

    /**
     * Stores [next] on the item if it still stands at [expected], with its capture's [requestsUsed] when given. Returns false,
     * changing nothing, when the item moved on meanwhile; throws [com.trenching.bot.console.automation.domain.IllegalItemTransitionException]
     * for a state change the pipeline does not allow.
     */
    @Transactional
    fun record(itemId: String, expected: AutomationItemProgress, next: AutomationItemProgress, requestsUsed: Int?, now: Instant): Boolean {
        val item = items.findOneById(itemId) ?: throw AutomationItemNotFoundException(itemId)
        val current = mapper.toProgress(item)
        if (current != expected) {
            log.warn("automation item changed meanwhile itemId={} state={} ignoredState={}", itemId, current.state, next.state)
            return false
        }
        AutomationItemTransitions.require(itemId, current.state, next.state)
        mapper.applyProgress(next, item)
        requestsUsed?.let { item.requestsUsed = it }
        item.updatedAt = now
        log.info("automation item advanced itemId={} step={} state={}", itemId, item.step, item.state)
        return true
    }

    private companion object {
        const val MAX_ITEMS_PER_TICK = 100
        val BATCH: PageRequest = PageRequest.of(0, MAX_ITEMS_PER_TICK)
        val ACTIVE_SESSIONS: Set<AutomationSessionState> = setOf(AutomationSessionState.OPEN, AutomationSessionState.CLOSING)
    }
}
