package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemEvents
import com.trenching.bot.console.automation.domain.AutomationItemStanding
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.SweepEntryResult
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The sweep's reads and writes, one short transaction each, so none is held across a run. A sweep row is written once per
 * (item, plan, label) as a real insert; a repeat leaves the stored row as it is, and the unique key refuses a concurrent second
 * one.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationSweepTransaction(
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val mapper: AutomationRecordMapper,
    private val events: AutomationItemEventWriter,
) {
    /** Captured items whose sweep is due at [now], oldest buy first; an item waiting after a failed sweep is not. */
    @Transactional(readOnly = true)
    fun due(now: Instant, limit: Int): List<AutomationDueItem> =
        items.findDueInState(AutomationItemState.CAPTURED, now, PageRequest.of(0, limit)).map(mapper::toDueItem)

    @Transactional(readOnly = true)
    fun sweptLabels(itemId: String, planId: String): Set<String> = sweepRuns.findLabels(itemId, planId).toSet()

    /** Stores [result] as the item's row for the entry, with its event; returns false, changing nothing, when the row already exists. */
    @Transactional
    fun record(item: AutomationDueItem, planId: String, label: String, result: SweepEntryResult, now: Instant): Boolean {
        val id = AutomationIds.sweepRun(item.id, planId, label)
        if (sweepRuns.existsById(id)) return false
        sweepRuns.saveAndFlush(AutomationSweepRunEntity(
            id = id, itemId = item.id, sessionId = item.sessionId, planId = planId, label = label, runId = result.runId,
            configurationHash = result.configurationHash, engineVersion = result.engineVersion, outcome = result.outcome,
            positionState = result.positionState, isSignal = result.signal, realizedPnl = result.realizedPnl,
            worstCasePnl = result.worstCasePnl, createdAt = now,
        ))
        val standing = items.findOneById(item.id)?.let(mapper::toStanding) ?: AutomationItemStanding(item.progress, null)
        events.append(item.id, now, AutomationItemChange(standing, AutomationItemEvents.sweepEntry(item.progress, planId, label, result.runId, result.outcome)))
        return true
    }
}
