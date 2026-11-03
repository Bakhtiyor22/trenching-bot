package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.CaptureFilter
import com.trenching.bot.console.automation.domain.DiscoveryBatch
import com.trenching.bot.console.automation.domain.DiscoveryBound
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.DiscoveryRecord
import com.trenching.bot.console.automation.domain.DiscoveryWatermark
import com.trenching.bot.console.automation.domain.PollWindow
import com.trenching.bot.console.automation.domain.SessionIdleRule
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Stores one discovery scan in a single transaction: new items, the sessions they open, extend or merge ([OpenSessions]), the
 * below-filter counts and the wallet's discovery position (plan Rules 5, 7–11, with the fixes of 2026-11-03).
 *
 * Only the first buy of a mint becomes an item (Rule 7). A catch-up can find a mint's earlier buy after its later one: before
 * any buy is placed, the earlier buy then takes the mint's item from the later one while no capture step has touched it
 * (`DISCOVERED` with no step, or `FAILED` for its missing block time) and its session is still open, and that session is
 * regrouped from the items left ([OpenSessions.regroup]). A `FAILED` item for its missing block time never sets a span or takes
 * part in evaluation, so it is also replaced while its session is `CLOSING`, and that session is deleted if it is left empty.
 * Otherwise the item stays as it is and the earlier buy is only logged,
 * since the wallet + token capture proves the first buy.
 *
 * A complete scan advances the watermark to the newest action of the scan, or of the whole catch-up it completes, and clears
 * the catch-up. A scan the page cap cut short keeps the watermark and stores where the next scan resumes. A wallet's first scan
 * has no watermark to keep: cut short, it stores its newest action ([DiscoveryCursor.ORIGIN] when it read none) as a provisional
 * watermark at once, with its initial lookback as the bound the catch-up still has to reach.
 *
 * The row's `updated_at` is the start of the last complete scan, which [DiscoveryWatermark.discoveredThrough] turns into how far
 * discovery is complete: a scan that starts at the newest transaction and reaches its bound sets it to its own start; one cut
 * short stores its start in `catch_up_started_at`, and the scan that completes that catch-up sets `updated_at` to it.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationDiscoveryTransaction(
    private val watermarks: AutomationWatermarkRepository,
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val mapper: AutomationRecordMapper,
) {
    private val log = LoggerFactory.getLogger(AutomationDiscoveryTransaction::class.java)

    @Transactional(readOnly = true)
    fun watermark(wallet: String): DiscoveryWatermark? = watermarks.findOneByWallet(wallet)?.let(mapper::toDiscoveryWatermark)

    /** Each wallet with the instant up to which every buy of it has been discovered; a wallet still in its first catch-up has none. */
    @Transactional(readOnly = true)
    fun discoveredThrough(): Map<String, Instant> = watermarks.findAll()
        .mapNotNull { row -> mapper.toDiscoveryWatermark(row).discoveredThrough?.let { row.wallet to it } }.toMap()

    /** The wallets whose discovery has a catch-up in progress, so an earlier buy of a mint may still be found. */
    @Transactional(readOnly = true)
    fun catchingUp(): Set<String> = watermarks.findWalletsCatchingUp().toSet()

    /**
     * Whether a scan started at [now], outside [window], would let an open session of [wallet] close that no complete scan lets
     * close yet: its last buy plus the idle time is covered by a scan now, but not by the last complete one (plan Rule 10, amended
     * 2026-11-03). Only a session whose last buy is before the window's most recent end counts, so the buys a scan finds after
     * the window closed do not keep scans going: the sessions they open or extend wait for the window to open again.
     */
    @Transactional(readOnly = true)
    fun closeAwaitsScan(wallet: String, idle: SessionIdleRule, window: PollWindow, now: Instant): Boolean {
        val coveredBefore = watermark(wallet)?.discoveredThrough?.let(idle::idleBefore)
        val scannableBefore = idle.idleBefore(DiscoveryWatermark.coveredBy(now))
        val lastBuyBefore = minOf(scannableBefore, window.lastEndAtOrBefore(now))
        return sessions.countWithLastBuyIn(wallet, AutomationSessionState.OPEN, coveredBefore, lastBuyBefore) > 0
    }

    @Transactional
    fun record(batch: DiscoveryBatch, filter: CaptureFilter, idle: SessionIdleRule, now: Instant): DiscoveryRecord {
        val open = OpenSessions(batch.wallet,
            sessions.findAllByWalletAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(batch.wallet, AutomationSessionState.OPEN), idle, now)
        val byMint = storedItems(batch, filter)
        replaceSuperseded(batch, filter, byMint, open)
        val created = mutableListOf<AutomationItemEntity>()
        var belowFilter = 0
        for (action in batch.actions) {
            when (action.side) {
                TradeSide.SELL -> continue
                TradeSide.BUY -> Unit
            }
            if (!filter.passes(action.solLamports)) {
                open.covering(action.blockTime)?.let {
                    it.belowFilterCount += 1
                    belowFilter += 1
                }
                continue
            }
            if (action.mint.value in byMint) continue
            val cursor = DiscoveryCursor.of(action)
            val itemId = AutomationIds.item(batch.wallet, cursor)
            val session = open.place(itemId, action.blockTime)
            newItem(batch.wallet, session.id, itemId, cursor, action, now).also {
                created += it
                byMint[it.mint] = it
            }
        }
        val opened = open.opened
        sessions.saveAll(opened)
        mergeAbsorbed(open)
        created.forEach { it.sessionId = open.current(it.sessionId) }
        items.saveAll(created)
        val advanced = moveWatermark(batch, now)
        opened.forEach { log.info("automation session opened sessionId={} wallet={} firstItemId={}", it.id, it.wallet, it.firstItemId) }
        created.forEach { log.info("automation item discovered itemId={} sessionId={} mint={} state={}", it.id, it.sessionId, it.mint, it.state) }
        return DiscoveryRecord(created.size, belowFilter, opened.size, advanced)
    }

    /** The scan's buys the capture filter passes, oldest first. */
    private fun capturedBuys(batch: DiscoveryBatch, filter: CaptureFilter): List<WalletAction> =
        batch.actions.filter { it.side == TradeSide.BUY && filter.passes(it.solLamports) }

    /** The wallet's stored items for the mints of the scan's captured buys, by mint; bounded by the scan's actions. */
    private fun storedItems(batch: DiscoveryBatch, filter: CaptureFilter): MutableMap<String, AutomationItemEntity> {
        val candidates = capturedBuys(batch, filter).map { it.mint.value }.distinct()
        if (candidates.isEmpty()) return mutableMapOf()
        return items.findAllByWalletAndMintIn(batch.wallet, candidates).associateBy { it.mint }.toMutableMap()
    }

    /**
     * Deletes each stored item an earlier buy of its mint in the scan takes over, so that buy is placed as the mint's item, and
     * regroups the sessions those items leave. Runs before any buy is placed, so the sessions are regrouped from stored items only.
     */
    private fun replaceSuperseded(batch: DiscoveryBatch, filter: CaptureFilter, byMint: MutableMap<String, AutomationItemEntity>, open: OpenSessions) {
        val left = linkedSetOf<String>()
        capturedBuys(batch, filter).groupBy { it.mint.value }.forEach { (mint, buys) ->
            val known = byMint[mint] ?: return@forEach
            val earliest = DiscoveryCursor.of(buys.first())
            if (earliest >= mapper.toCursor(known) || !replaceable(known, open, earliest)) return@forEach
            items.delete(known)
            byMint.remove(mint)
            left += known.sessionId
            log.info("automation item replaced by its mint's earlier buy itemId={} mint={} earlierSignature={}", known.id, mint, earliest.signature)
        }
        if (left.isEmpty()) return
        items.flush()
        left.forEach { if (open.isOpen(it)) regroup(open, it) else removeIfEmpty(it) }
    }

    /**
     * Whether an earlier buy may take [known]'s place: no capture step has touched it and its session still takes buys. An item
     * without a block time never sets a span or takes part in evaluation, so it may also be replaced in a `CLOSING` session.
     * Logs why not.
     */
    private fun replaceable(known: AutomationItemEntity, open: OpenSessions, earliest: DiscoveryCursor): Boolean {
        val untimed = known.state == AutomationItemState.FAILED && known.failureCode == AutomationFailures.MISSING_BLOCK_TIME
        if (known.step != null || !(untimed || known.state == AutomationItemState.DISCOVERED)) {
            log.warn("automation earlier buy found after capture started, item kept itemId={} mint={} state={} step={} earlierSignature={}",
                known.id, known.mint, known.state, known.step, earliest.signature)
            return false
        }
        if (open.isOpen(known.sessionId) || untimed && isClosing(known.sessionId)) return true
        log.warn("automation earlier buy found after its item's session stopped taking buys, item kept itemId={} sessionId={} mint={} earlierSignature={}",
            known.id, known.sessionId, known.mint, earliest.signature)
        return false
    }

    private fun isClosing(sessionId: String): Boolean = sessions.findOneById(sessionId)?.state == AutomationSessionState.CLOSING

    /** Deletes the session [sessionId], which does not take buys, once its last item was replaced. */
    private fun removeIfEmpty(sessionId: String) {
        if (items.countBySessionId(sessionId) > 0) return
        sessions.findOneById(sessionId)?.let {
            sessions.delete(it)
            log.info("automation session removed, every item of it was replaced sessionId={}", it.id)
        }
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

    /** Moves the items and sweep rows of every stored session another one absorbed, in bulk, then deletes it. */
    private fun mergeAbsorbed(open: OpenSessions) {
        open.absorbed.forEach { session ->
            val into = open.current(session.id)
            items.moveSession(session.id, into)
            sweepRuns.moveSession(session.id, into)
            sessions.delete(session)
            log.info("automation sessions merged sessionId={} absorbedSessionId={}", into, session.id)
        }
    }

    private fun newItem(wallet: String, sessionId: String, itemId: String, cursor: DiscoveryCursor, action: WalletAction, now: Instant): AutomationItemEntity {
        val placed = action.blockTime != null
        return AutomationItemEntity(
            id = itemId, sessionId = sessionId, wallet = wallet, mint = action.mint.value, signature = cursor.signature,
            instructionIndex = cursor.instructionIndex, innerIndex = cursor.innerIndex, slot = cursor.slot, blockTime = action.blockTime,
            curvePaymentLamports = action.solLamports,
            state = if (placed) AutomationItemState.DISCOVERED else AutomationItemState.FAILED,
            step = null, stepAttempt = 0, captureSessionId = null, operationId = null, datasetId = null, classification = null,
            failureCode = if (placed) null else AutomationFailures.MISSING_BLOCK_TIME, failureMessage = null,
            nextActionAt = if (placed) now else null, createdAt = now, updatedAt = now,
        )
    }

    /** Returns whether the watermark advanced, which only a complete scan does. */
    private fun moveWatermark(batch: DiscoveryBatch, now: Instant): Boolean {
        val current = watermarks.findOneByWallet(batch.wallet)
        val resumeBefore = batch.resumeBefore
        if (resumeBefore != null) {
            holdCatchUp(batch, current, resumeBefore, now)
            return false
        }
        if (current == null) {
            return batch.newest?.let { watermarks.save(newWatermark(batch.wallet, it, now)) } != null
        }
        return completeScan(batch, current, now)
    }

    /**
     * A scan from the newest transaction is complete from its own start; a scan that completes a catch-up, from the start of
     * the scan that began it. A catch-up stored before that start was kept leaves [AutomationWatermarkEntity.updatedAt] as it is.
     */
    private fun completeScan(batch: DiscoveryBatch, current: AutomationWatermarkEntity, now: Instant): Boolean {
        val newest = listOfNotNull(batch.newest, current.pending?.let(mapper::toCursor)).maxOrNull()
        current.updatedAt = if (current.resumeBefore == null) now else current.catchUpStartedAt ?: current.updatedAt
        current.resumeBefore = null
        current.pending = null
        current.catchUpStartedAt = null
        val advanced = newest?.takeIf { it > mapper.toCursor(current) }?.also { moveTo(current, it) } != null
        current.resumeSince = current.updatedAt.takeIf { mapper.toCursor(current) == DiscoveryCursor.ORIGIN }
        return advanced
    }

    private fun holdCatchUp(batch: DiscoveryBatch, current: AutomationWatermarkEntity?, resumeBefore: String, now: Instant) {
        if (current == null) {
            watermarks.save(newWatermark(batch.wallet, batch.newest ?: DiscoveryCursor.ORIGIN, now).also {
                it.resumeBefore = resumeBefore
                it.resumeSince = lookbackOf(batch.bound)
                it.catchUpStartedAt = now
            })
            return
        }
        if (current.resumeBefore == null) current.catchUpStartedAt = now
        current.pending = listOfNotNull(batch.newest, current.pending?.let(mapper::toCursor)).maxOrNull()?.let(mapper::toColumns)
        current.resumeBefore = resumeBefore
    }

    private fun lookbackOf(bound: DiscoveryBound): Instant? = when (bound) {
        is DiscoveryBound.Since -> bound.from
        is DiscoveryBound.After -> null
    }

    private fun newWatermark(wallet: String, cursor: DiscoveryCursor, now: Instant) =
        AutomationWatermarkEntity(wallet, cursor.slot, cursor.signature, cursor.instructionIndex, cursor.innerIndex, now)

    private fun moveTo(current: AutomationWatermarkEntity, newest: DiscoveryCursor) {
        current.slot = newest.slot
        current.signature = newest.signature
        current.instructionIndex = newest.instructionIndex
        current.innerIndex = newest.innerIndex
    }
}
