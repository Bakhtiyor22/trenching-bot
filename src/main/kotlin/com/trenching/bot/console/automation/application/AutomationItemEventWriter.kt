package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemEventDraft
import com.trenching.bot.console.automation.domain.AutomationItemEvents
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemStanding
import com.trenching.bot.console.automation.domain.AutomationItemSubject
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEventEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEventRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.mapper.AutomationEventCodec
import java.sql.SQLException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Appends events to items' histories (plan Domain Rules H1-H4). It runs inside the transaction that changes the item, which it
 * requires but does not start, so a rolled-back change leaves no event.
 *
 * An event's `seq` is the item's last one plus one, so the item's row is locked by this transaction before the last one is read. A
 * change the transaction made to the item is flushed first, and its version-checked update takes the lock: a writer that read the
 * item before another one committed fails there with the optimistic conflict every caller already handles. An item the transaction
 * did not change (a sweep entry, a history start) is locked with `select for update` instead of a forced version bump: a pessimistic
 * lock is taken as it is asked for, so the second writer waits and then reads the first one's events, where a forced bump only
 * takes effect at commit, after the order was read, and would make an unrelated pipeline change fail its own version check.
 *
 * Should two writers still reach the same `(item, seq)`, the unique key refuses the second, and that is reported as the same
 * optimistic conflict, never as a data error.
 *
 * An item that has no event yet gets a `HISTORY_STARTED` with its standing before the change ahead of the change's events (H4), under
 * the same lock, so the pipeline and the startup pass can never both start it.
 *
 * Every change that is stored here is also handed to [AutomationItemLog], with the item as the change left it, which says it once the
 * transaction has committed. Saying it never decides the change: an exception while the lines are built is logged and swallowed, so a
 * log line can never roll a stored change back.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(propagation = Propagation.MANDATORY)
class AutomationItemEventWriter(
    private val events: AutomationItemEventRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val itemLog: AutomationItemLog,
) {
    private val log = LoggerFactory.getLogger(AutomationItemEventWriter::class.java)

    fun append(itemId: String, at: Instant, change: AutomationItemChange) = appendAll(at, mapOf(itemId to change))

    /** Appends each item's events in order after the item's last event; one read finds every item's last order. */
    fun appendAll(at: Instant, changes: Map<String, AutomationItemChange>) {
        val pending = changes.filterValues { it.events.isNotEmpty() }
        if (pending.isEmpty()) return
        val last = lockedLastSeqs(pending.keys)
        store(pending.flatMap { (itemId, change) ->
            val lastSeq = last[itemId] ?: 0
            val history = change.before?.takeIf { lastSeq == 0 }?.let(::historyStarted).orEmpty()
            numbered(itemId, lastSeq, at, history + change.events)
        })
        announce(pending)
    }

    /**
     * Gives the item the first event of its history, [before], unless it already has an event once the item is locked; true when it
     * did. An event another writer added first makes this a no-op, so it is safe to run beside the pipeline.
     */
    fun startHistory(itemId: String, at: Instant, before: AutomationItemStanding): Boolean {
        if (lockedLastSeqs(setOf(itemId)).containsKey(itemId)) return false
        val drafts = historyStarted(before)
        store(numbered(itemId, 0, at, drafts))
        announce(mapOf(itemId to AutomationItemChange(before, drafts)))
        return true
    }

    /** Hands the changes to the item log. The items are read first, and a failure of that read is the transaction's own; only building the lines is swallowed. */
    private fun announce(changes: Map<String, AutomationItemChange>) {
        val subjects = subjects(changes)
        try {
            itemLog.announce(subjects, changes)
        } catch (exception: Exception) {
            LogLine("The log lines of stored buy changes could not be built; the changes are stored without them.")
                .field("itemIds", changes.keys.sorted().joinToString(",")).field("cause", exception.javaClass.simpleName).warn(log)
        }
    }

    /** The items as the changes left them, read once by a query: entities the transaction already loaded come back as it changed them, after the flush [lockedLastSeqs] makes. */
    private fun subjects(changes: Map<String, AutomationItemChange>): Map<String, AutomationItemSubject> =
        items.findAllById(changes.keys).associate { it.id to subject(it, changes.getValue(it.id)) }

    private fun subject(item: AutomationItemEntity, change: AutomationItemChange): AutomationItemSubject {
        val simulated = change.events.any { it.kind == AutomationItemEventKind.STATE_CHANGED && it.toState == AutomationItemState.SIMULATED }
        val sweep = if (simulated) sweepRuns.tally(item.id) else null
        return AutomationItemSubject(item.id, item.mint, item.wallet, item.backfillId, item.blockTime, item.sessionId, item.datasetId, item.classification,
            item.requestsUsed, sweep?.let { Math.toIntExact(it.entries) }, sweep?.let { Math.toIntExact(it.entries - it.ran) })
    }

    private fun lockedLastSeqs(itemIds: Set<String>): Map<String, Int> {
        items.flush()
        items.lockByIdIn(itemIds.sorted())
        return events.findLastSeqs(itemIds).associate { it.itemId to it.seq }
    }

    private fun historyStarted(before: AutomationItemStanding) = AutomationItemEvents.historyStarted(before.progress, before.requestsUsed)

    private fun numbered(itemId: String, lastSeq: Int, at: Instant, drafts: List<AutomationItemEventDraft>) =
        drafts.mapIndexed { index, draft -> entity(itemId, lastSeq + 1 + index, at, draft) }

    /** An event's id derives from its item and order, so a repeated order breaks the primary key as well as the unique key: any unique violation here is that clash. */
    private fun store(entities: List<AutomationItemEventEntity>) {
        try {
            events.saveAllAndFlush(entities)
        } catch (exception: DataIntegrityViolationException) {
            if (!isUniqueViolation(exception)) throw exception
            throw ObjectOptimisticLockingFailureException(AutomationItemEventEntity::class.java, entities.map { it.itemId }.distinct().joinToString(), exception)
        }
    }

    private fun isUniqueViolation(exception: Throwable): Boolean =
        generateSequence(exception) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION }

    private fun entity(itemId: String, seq: Int, at: Instant, draft: AutomationItemEventDraft) = AutomationItemEventEntity(
        id = AutomationIds.itemEvent(itemId, seq), itemId = itemId, seq = seq, at = at, kind = draft.kind,
        fromState = draft.fromState, toState = draft.toState, step = draft.step, attempt = draft.attempt,
        captureSessionId = draft.captureSessionId, operationId = draft.operationId, requestsUsed = draft.requestsUsed,
        failureCode = draft.failureCode, failureMessage = AutomationFailures.message(draft.failureMessage),
        actor = null, requestId = draft.requestId, note = null, detail = AutomationEventCodec.detailJson(draft.detail),
    )

    private companion object {
        /** SQLSTATE of a unique or primary key violation. */
        const val UNIQUE_VIOLATION = "23505"
    }
}
