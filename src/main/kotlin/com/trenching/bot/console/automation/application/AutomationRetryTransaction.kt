package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationItemEvents
import com.trenching.bot.console.automation.domain.AutomationItemTransitions
import com.trenching.bot.console.automation.domain.AutomationRetryRefusal
import com.trenching.bot.console.automation.domain.AutomationRetryRefusedException
import com.trenching.bot.console.automation.domain.AutomationRetryResult
import com.trenching.bot.console.automation.domain.AutomationRetryRule
import com.trenching.bot.console.automation.domain.retried
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One item's retry in one short transaction: it reads the item and its backfill, checks [AutomationRetryRule] and stores the
 * retried progress and its event. It calls no provider; the next pipeline or sweep tick does the work. The write checks the item's version, so
 * a change made meanwhile fails the commit instead of being overwritten. The item's backfill is read with a forced version
 * increment, so a backfill that ends meanwhile (see [AutomationBackfillTransaction.settle]) fails the commit too and the retry is
 * decided again against the backfill's new state. The retry never changes the backfill, ended or not.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationRetryTransaction(
    private val items: AutomationItemRepository,
    private val backfills: AutomationBackfillRepository,
    private val mapper: AutomationRecordMapper,
    private val events: AutomationItemEventWriter,
) {
    /**
     * Retries the item under [requestId] while [captureEnabled]. A repeat of the item's last retry request changes nothing. Throws
     * [AutomationRetryRefusedException] when the rule refuses, [AutomationItemNotFoundException] for an unknown item.
     */
    @Transactional
    fun retry(itemId: String, requestId: String, captureEnabled: Boolean, now: Instant): AutomationRetryResult {
        val item = items.findOneById(itemId) ?: throw AutomationItemNotFoundException(itemId)
        if (item.retryRequestId == requestId) return AutomationRetryResult.Unchanged
        val before = mapper.toStanding(item)
        val current = before.progress
        val backfillState = item.backfillId?.let { backfills.findForRetryById(it)?.state }
        AutomationRetryRule.refusal(current, item.backfillId, backfillState, captureEnabled)
            ?.let { throw AutomationRetryRefusedException(itemId, it, current.failureCode) }
        val next = current.retried(now)
        AutomationItemTransitions.require(itemId, current.state, next.state, retry = true)
        mapper.applyProgress(next, item)
        item.retryRequestId = requestId
        item.updatedAt = now
        events.append(itemId, now, AutomationItemChange(before, AutomationItemEvents.retried(current, next, requestId)))
        return AutomationRetryResult.Retried(itemId, next.step, current.failureCode, next.retries, requestId)
    }

    /**
     * The outcome once [retry] lost to concurrent changes twice, decided from a fresh read: [AutomationRetryResult.Unchanged] when the
     * item's last retry has this request id, otherwise a refusal, `ITEM_NOT_FAILED` unless the rule names another. Writes nothing.
     */
    @Transactional(readOnly = true)
    fun afterConflict(itemId: String, requestId: String, captureEnabled: Boolean): AutomationRetryResult.Unchanged {
        val item = items.findOneById(itemId) ?: throw AutomationItemNotFoundException(itemId)
        if (item.retryRequestId == requestId) return AutomationRetryResult.Unchanged
        throw AutomationRetryRefusedException(itemId, refusal(item, captureEnabled) ?: AutomationRetryRefusal.ITEM_NOT_FAILED, item.failureCode)
    }

    private fun refusal(item: AutomationItemEntity, captureEnabled: Boolean): AutomationRetryRefusal? {
        val backfillState = item.backfillId?.let { backfills.findOneById(it)?.state }
        return AutomationRetryRule.refusal(mapper.toProgress(item), item.backfillId, backfillState, captureEnabled)
    }
}
