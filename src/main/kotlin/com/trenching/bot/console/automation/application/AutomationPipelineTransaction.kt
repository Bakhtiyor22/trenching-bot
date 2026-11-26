package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationItemEvents
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemTrace
import com.trenching.bot.console.automation.domain.AutomationItemTransitions
import com.trenching.bot.console.automation.domain.BackfillBudget
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
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
 * it is and the capture result is ignored. A buy that becomes `SIMULATED` has its result message published in the same transaction.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationPipelineTransaction(
    private val items: AutomationItemRepository,
    private val backfills: AutomationBackfillRepository,
    private val mapper: AutomationRecordMapper,
    private val events: AutomationItemEventWriter,
    private val buyNotifier: AutomationBuyNotifier,
) {
    private val log = LoggerFactory.getLogger(AutomationPipelineTransaction::class.java)

    /**
     * Items in capture whose next action has come, in any session, in priority order (plan Rule 40, amended 2026-11-03): live items of sessions
     * still open or closing, then the live phase 0 backlog of closed sessions (items discovered while capture was off), then those
     * of a backfill whose scan is over (and a person's retried items of an ended one), each oldest buy first.
     */
    @Transactional(readOnly = true)
    fun due(now: Instant): List<AutomationDueItem> =
        items.findDue(AutomationItemState.IN_CAPTURE, BackfillState.CAPTURING_STATES, BackfillState.ENDED_STATES, now, BATCH).map(mapper::toDueItem)

    /** Whether an item of [backfillId] already ended `FAILED` because the backfill's budget could not cover it. */
    @Transactional(readOnly = true)
    fun stoppedByBudget(backfillId: String): Boolean = items.existsByBackfillIdAndFailureCode(backfillId, AutomationFailures.BACKFILL_BUDGET)

    /** The spend of [backfillId] against its request budget (plan Rule 39): its discovery requests plus its items' captures so far. */
    @Transactional(readOnly = true)
    fun budget(backfillId: String): BackfillBudget {
        val backfill = checkNotNull(backfills.findOneById(backfillId)) { "Item of an unknown backfill backfillId=$backfillId" }
        return BackfillBudget(backfill.requestBudget, backfill.discoveryRequests, items.sumRequestsByBackfillId(backfillId))
    }

    /**
     * Stores [next] on the item if it still stands at [expected], with its capture's [requestsUsed] when given, and the events of
     * the change that [trace] completes, in the same transaction. Returns false, changing nothing, when the item moved on meanwhile;
     * throws [com.trenching.bot.console.automation.domain.IllegalItemTransitionException] for a state change the pipeline does not
     * allow.
     */
    @Transactional
    fun record(itemId: String, expected: AutomationItemProgress, next: AutomationItemProgress, requestsUsed: Int?, now: Instant,
        trace: AutomationItemTrace = AutomationItemTrace.NONE): Boolean {
        val item = items.findOneById(itemId) ?: throw AutomationItemNotFoundException(itemId)
        val before = mapper.toStanding(item)
        val current = before.progress
        if (current != expected) {
            LogLine("Buy change ignored: the buy moved on while its capture result was being stored.").field("itemId", itemId).field("mint", item.mint)
                .field("wallet", item.wallet).field("state", current.state).field("ignoredState", next.state).debug(log)
            return false
        }
        AutomationItemTransitions.require(itemId, current.state, next.state)
        val operationRequests = requestsUsed?.takeIf { trace.endedOperationState != null }?.let { (it - (item.requestsUsed ?: 0)).coerceAtLeast(0) }
        mapper.applyProgress(next, item)
        requestsUsed?.let { item.requestsUsed = it }
        item.updatedAt = now
        events.append(itemId, now, AutomationItemChange(before, AutomationItemEvents.changed(current, next, trace, operationRequests)))
        if (next.state == AutomationItemState.SIMULATED) buyNotifier.publishResult(item)
        return true
    }

    private companion object {
        const val MAX_ITEMS_PER_TICK = 100
        val BATCH: PageRequest = PageRequest.of(0, MAX_ITEMS_PER_TICK)
    }
}
