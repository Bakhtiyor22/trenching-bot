package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationOperationRef
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.BackfillActiveException
import com.trenching.bot.console.automation.domain.BackfillBudget
import com.trenching.bot.console.automation.domain.BackfillNotFoundException
import com.trenching.bot.console.automation.domain.BackfillOverlapsCoverageException
import com.trenching.bot.console.automation.domain.BackfillScanTarget
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.NewBackfill
import com.trenching.bot.console.automation.domain.TimeRange
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationBackfillCodec
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A backfill's life outside its scan's batches, one short transaction per change (plan Rules 35, 36, 39 and 41): creating it
 * against live coverage and the other backfills, choosing what its next scan tick reads, ending it when its items are
 * terminal, and cancelling it. The unique keys on `request_id` and `active_wallet` back the checks made here; a duplicate that
 * slips between check and insert fails the insert, which the caller resolves.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationBackfillTransaction(
    private val backfills: AutomationBackfillRepository,
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val watermarks: AutomationWatermarkRepository,
    private val sessionTransaction: AutomationSessionTransaction,
    private val coverage: AutomationCoverage,
) {
    private val log = LoggerFactory.getLogger(AutomationBackfillTransaction::class.java)

    @Transactional(readOnly = true)
    fun exists(id: String): Boolean = backfills.existsById(id)

    /** The wallet's backfill that holds its active slot, if any. */
    @Transactional(readOnly = true)
    fun activeId(wallet: String): String? = backfills.findOneByActiveWallet(wallet)?.id

    /**
     * Stores [backfill] as `QUEUED`, holding the wallet's active slot, and returns its id. Refused first when the wallet already
     * has an active backfill. A capturing backfill's range is clipped to live coverage and refused when nothing is left or it
     * meets another capturing backfill (Key Decision 6); a dry run keeps its range.
     */
    @Transactional
    fun create(wallet: String, backfill: NewBackfill, now: Instant): String {
        backfills.findOneByActiveWallet(wallet)?.let { throw BackfillActiveException(wallet, it.id) }
        val range = if (backfill.dryRun) backfill.range else clippedToCoverage(wallet, backfill.range)
        val id = AutomationIds.backfill(wallet, backfill.requestId)
        backfills.saveAndFlush(AutomationBackfillEntity(
            id, backfill.requestId, wallet, wallet, backfill.fromDay, backfill.toDay, range.from, range.to, backfill.dryRun,
            backfill.filter.minSignalLamports, AutomationBackfillCodec.mintsJson(backfill.filter.mints), backfill.requestBudget,
            BackfillState.QUEUED, now, now,
        ).also { it.filtered = backfill.filtered })
        log.info("automation backfill queued backfillId={} wallet={} from={} to={} dryRun={} requestBudget={}",
            id, wallet, range.from, range.to, backfill.dryRun, backfill.requestBudget)
        return id
    }

    /** The next scan of the wallet's backfill, if one is waiting to be scanned. */
    @Transactional(readOnly = true)
    fun scanTarget(wallet: String): BackfillScanTarget? {
        val backfill = backfills.findOneByActiveWallet(wallet)?.takeIf { it.state in BackfillState.SCANNING_STATES } ?: return null
        val range = TimeRange(backfill.rangeStart, backfill.rangeEnd)
        val seed = if (backfill.resumeBefore == null) items.findFirstByWalletAndBlockTimeGreaterThanEqualOrderBySlotAscSignatureAsc(wallet, range.to)?.signature else null
        val budget = BackfillBudget(backfill.requestBudget, backfill.discoveryRequests, items.sumRequestsByBackfillId(backfill.id))
        return BackfillScanTarget(backfill.id, wallet, range, backfill.resumeBefore, seed, budget)
    }

    /**
     * Ends each capturing backfill whose items are all terminal: `DONE`, or `STOPPED_BUDGET` when its budget stopped an item
     * (Rule 39). Its scan read the whole range; a done, unfiltered one also moves live coverage back to its start when its end
     * meets it (Key Decision 6, amended 2026-11-03).
     * Returns how many ended. A backfill whose scan the budget stopped ended then, in [AutomationBackfillRecorder], and is not settled here.
     */
    @Transactional
    fun settle(now: Instant): Int {
        val ready = backfills.findAllByStateInOrderByCreatedAtAscIdAsc(setOf(BackfillState.CAPTURING))
            .filter { items.countByBackfillIdAndStateNotIn(it.id, AutomationItemState.TERMINAL) == 0L }
        ready.forEach { backfill ->
            val stopped = items.existsByBackfillIdAndFailureCode(backfill.id, AutomationFailures.BACKFILL_BUDGET)
            end(backfill, if (stopped) BackfillState.STOPPED_BUDGET else BackfillState.DONE, now)
            coverage.extend(backfill)
        }
        return ready.size
    }

    /**
     * Cancels a backfill that is active, or that its budget stopped while items of it still wait: it ends `CANCELLED`, frees the
     * wallet's slot, and its sessions close as a manual close does, so their pending items are abandoned, in a session that
     * already closed too. What its scan read stays covered, and live coverage does not move. Returns the capture operations those items had started, which the caller cancels once this has committed.
     * Any other backfill that already ended is left as it is.
     */
    @Transactional
    fun cancel(id: String, now: Instant): List<AutomationOperationRef> {
        val backfill = backfills.findOneById(id) ?: throw BackfillNotFoundException(id)
        if (!cancellable(backfill)) return emptyList()
        val started = mutableListOf<AutomationOperationRef>()
        do {
            val batch = sessions.findIdsToCancel(id, AutomationSessionState.CLOSED, AutomationItemState.TERMINAL, PageRequest.of(0, AutomationLimits.CANCEL_SESSIONS_PER_READ))
            batch.forEach { started += sessionTransaction.closeManually(it, now) }
        } while (batch.isNotEmpty())
        end(backfill, BackfillState.CANCELLED, now)
        return started
    }

    private fun cancellable(backfill: AutomationBackfillEntity): Boolean =
        backfill.state.active || backfill.state == BackfillState.STOPPED_BUDGET && items.countByBackfillIdAndStateNotIn(backfill.id, AutomationItemState.TERMINAL) > 0

    private fun end(backfill: AutomationBackfillEntity, state: BackfillState, now: Instant) {
        backfill.end(state, now)
        log.info("automation backfill ended backfillId={} state={} itemsCreated={}", backfill.id, state, backfill.itemsCreated)
    }

    private fun clippedToCoverage(wallet: String, requested: TimeRange): TimeRange {
        val coveredFrom = watermarks.findOneByWallet(wallet)?.coveredFrom
        val end = coveredFrom?.let { minOf(requested.to, it) }
        if (coveredFrom == null || end == null || !requested.from.isBefore(end)) {
            throw BackfillOverlapsCoverageException(requested.from, requested.to, coveredFrom)
        }
        val range = TimeRange(requested.from, end)
        backfills.findCapturingOverlapping(wallet, range.from, range.to, PageRequest.of(0, 1)).firstOrNull()?.let {
            throw BackfillOverlapsCoverageException(range.from, range.to, coveredFrom, it.id)
        }
        return range
    }
}
