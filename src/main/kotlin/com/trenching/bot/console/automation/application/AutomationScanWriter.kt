package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.SessionIdleRule
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.walletactivity.WalletAction
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The writes live discovery ([AutomationDiscoveryTransaction]) and a backfill's scan ([AutomationBackfillRecorder]) share when
 * they store a batch of buys (plan Rules 7, 10 and 38): making an item, replacing the item an earlier buy of its mint takes over,
 * and storing the session changes [OpenSessions] worked out. Both run inside their caller's transaction, which this class does not
 * start but requires.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(propagation = Propagation.MANDATORY)
class AutomationScanWriter(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val mapper: AutomationRecordMapper,
) {
    private val log = LoggerFactory.getLogger(AutomationScanWriter::class.java)

    /** The item for a buy, tagged with the [backfillId] that found it; a buy without a block time starts failed and never enters capture. */
    fun newItem(wallet: String, backfillId: String?, sessionId: String, itemId: String, cursor: DiscoveryCursor, action: WalletAction, now: Instant): AutomationItemEntity {
        val placed = action.blockTime != null
        return AutomationItemEntity(
            id = itemId, sessionId = sessionId, wallet = wallet, mint = action.mint.value, signature = cursor.signature,
            instructionIndex = cursor.instructionIndex, innerIndex = cursor.innerIndex, slot = cursor.slot, blockTime = action.blockTime,
            curvePaymentLamports = action.solLamports,
            state = if (placed) AutomationItemState.DISCOVERED else AutomationItemState.FAILED,
            step = null, stepAttempt = 0, captureSessionId = null, operationId = null, datasetId = null, classification = null,
            failureCode = if (placed) null else AutomationFailures.MISSING_BLOCK_TIME, failureMessage = null,
            nextActionAt = if (placed) now else null, createdAt = now, updatedAt = now,
        ).also { it.backfillId = backfillId }
    }

    /** Whether the item is a buy without a block time, which never sets a span or takes part in evaluation. */
    fun isUntimed(item: AutomationItemEntity): Boolean =
        item.state == AutomationItemState.FAILED && item.failureCode == AutomationFailures.MISSING_BLOCK_TIME

    /** Whether no capture step has touched [known], so an earlier buy may take its place; logs why not. */
    fun isUntouched(known: AutomationItemEntity, earliest: DiscoveryCursor): Boolean {
        if (known.step == null && (isUntimed(known) || known.state == AutomationItemState.DISCOVERED)) return true
        log.warn("automation earlier buy found after capture started, item kept itemId={} mint={} state={} step={} earlierSignature={}",
            known.id, known.mint, known.state, known.step, earliest.signature)
        return false
    }

    /**
     * Whether a backfill other than the one that found [known] may take its place when it finds the same buy again, or an earlier
     * buy of its mint (plan Key Decision 6, amended 2026-11-03): [known] came from a backfill and produced nothing, because no
     * capture step touched it, because its backfill's request budget ran out before its capture could finish, or because it was
     * abandoned before a dataset was registered. Anything that captured, or was swept, stays.
     */
    fun isRetakeable(known: AutomationItemEntity, backfillId: String): Boolean {
        if (known.backfillId == null || known.backfillId == backfillId) return false
        return when (known.state) {
            AutomationItemState.DISCOVERED -> known.step == null
            AutomationItemState.FAILED -> known.failureCode == AutomationFailures.BACKFILL_BUDGET
            AutomationItemState.ABANDONED -> known.datasetId == null
            AutomationItemState.WAITING, AutomationItemState.BLOCKED_BUDGET, AutomationItemState.CAPTURING, AutomationItemState.CAPTURED,
            AutomationItemState.SIMULATED, AutomationItemState.SKIPPED_NOT_FIRST_BUY -> false
        }
    }

    /**
     * Deletes each stored item in [known] that a buy among [buys] (oldest first) takes over, so that buy is placed as the mint's
     * item: an earlier buy of the mint, as far as [replaceable] allows, or the same or an earlier buy when [retaken] holds for the
     * item. Then it rebuilds every session those items left (see [rebuild]). Runs before any buy is placed, so the sessions are
     * rebuilt from stored items only.
     */
    fun replaceSuperseded(
        buys: List<WalletAction>,
        known: MutableMap<String, AutomationItemEntity>,
        open: OpenSessions,
        idle: SessionIdleRule,
        now: Instant,
        replaceable: (AutomationItemEntity, DiscoveryCursor) -> Boolean,
        retaken: (AutomationItemEntity) -> Boolean = { false },
    ) {
        val left = linkedSetOf<String>()
        buys.groupBy { it.mint.value }.forEach { (mint, mintBuys) ->
            val stored = known[mint] ?: return@forEach
            val earliest = DiscoveryCursor.of(mintBuys.first())
            val storedAt = mapper.toCursor(stored)
            val takesOver = when {
                earliest > storedAt -> false
                retaken(stored) -> true
                else -> earliest < storedAt && replaceable(stored, earliest)
            }
            if (!takesOver) return@forEach
            items.delete(stored)
            known.remove(mint)
            left += stored.sessionId
            log.info("automation item replaced by a buy of its mint itemId={} mint={} state={} backfillId={} buySignature={}",
                stored.id, mint, stored.state, stored.backfillId, earliest.signature)
        }
        if (left.isEmpty()) return
        items.flush()
        left.forEach { rebuild(it, open, idle, now) }
    }

    /** Moves the items and sweep rows of every stored session another one absorbed, in bulk, then deletes it. */
    fun mergeAbsorbed(open: OpenSessions) {
        open.absorbed.forEach { session ->
            val into = open.current(session.id)
            items.moveSession(session.id, into)
            sweepRuns.moveSession(session.id, into)
            sessions.delete(session)
            log.info("automation sessions merged sessionId={} absorbedSessionId={}", into, session.id)
        }
    }

    /**
     * Brings the session [sessionId] in line with the items it has left after some were replaced, whichever source found it. A
     * session without an item is deleted. One that still takes buys is regrouped (split where a gap grew past the idle time, or
     * where a trading day ends): through [open] when that holds it, else through a regrouping of its own. A session that takes no
     * more buys keeps its state, `CLOSING` or `CLOSED` is never reopened, and only has its span and first item recomputed.
     */
    private fun rebuild(sessionId: String, open: OpenSessions, idle: SessionIdleRule, now: Instant) {
        if (open.isOpen(sessionId)) return regroup(open, sessionId)
        val session = sessions.findOneById(sessionId) ?: return
        if (session.state == AutomationSessionState.OPEN) return regroup(OpenSessions(session.wallet, listOf(session), idle, now, session.backfillId), sessionId)
        val left = items.findBuysBySessionId(sessionId)
        if (left.isEmpty()) {
            sessions.delete(session)
            log.info("automation session removed, every item of it was replaced sessionId={} state={}", session.id, session.state)
            return
        }
        val timed = left.mapNotNull { it.blockTime }
        if (timed.isNotEmpty()) {
            session.firstBuyAt = timed.min()
            session.lastBuyAt = timed.max()
        }
        if (left.none { it.id == session.firstItemId }) session.firstItemId = left.first().id
        log.info("automation session rebuilt after items were replaced sessionId={} state={} firstItemId={} firstBuyAt={} lastBuyAt={}",
            session.id, session.state, session.firstItemId, session.firstBuyAt, session.lastBuyAt)
    }

    /** Stores what [OpenSessions.regroup] changed in the session [sessionId]: the sessions split off it, its removal or its closing. */
    private fun regroup(open: OpenSessions, sessionId: String) {
        val regrouping = open.regroup(sessionId, items.findBuysBySessionId(sessionId))
        regrouping.split.forEach { (part, itemIds) ->
            sessions.save(part)
            items.moveItems(itemIds, part.id)
            sweepRuns.moveItems(itemIds, part.id)
            log.info("automation session split sessionId={} splitSessionId={} items={}", sessionId, part.id, itemIds.size)
        }
        regrouping.emptied?.let {
            sessions.delete(it)
            log.info("automation session removed, every item of it was replaced sessionId={}", it.id)
        }
        regrouping.closing?.let {
            log.info("automation session state changed, only buys without a block time are left sessionId={} wallet={} fromState={} toState={} firstItemId={}",
                it.id, it.wallet, AutomationSessionState.OPEN, it.state, it.firstItemId)
        }
    }
}
