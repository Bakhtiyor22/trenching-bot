package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationIds
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
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Duration
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
 * The wallet's first row also records `covered_from`, the start of its first scan's lookback (Rule 32); a row stored without one gets
 * an estimate on its next scan ([ensureCoveredFrom]); nothing else changes it here (a finished backfill moves it back, in
 * [AutomationBackfillTransaction]). Only the wallet's live sessions take part: a backfill's
 * buys are placed among its own sessions, and these buys never join them.
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
    private val mapper: AutomationRecordMapper,
    private val writer: AutomationScanWriter,
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
    fun record(batch: DiscoveryBatch, filter: CaptureFilter, idle: SessionIdleRule, initialLookback: Duration, now: Instant): DiscoveryRecord {
        ensureCoveredFrom(batch.wallet, initialLookback)
        val open = OpenSessions(batch.wallet,
            sessions.findAllByWalletAndBackfillIdIsNullAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(batch.wallet, AutomationSessionState.OPEN), idle, now)
        val byMint = storedItems(batch, filter)
        writer.replaceSuperseded(capturedBuys(batch, filter), byMint, open, idle, now, replaceable = { known, earliest -> replaceable(known, open, earliest) })
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
            writer.newItem(batch.wallet, null, session.id, itemId, cursor, action, now).also {
                created += it
                byMint[it.mint] = it
            }
        }
        val opened = open.opened
        sessions.saveAll(opened)
        writer.mergeAbsorbed(open)
        created.forEach { it.sessionId = open.current(it.sessionId) }
        items.saveAll(created)
        val advanced = moveWatermark(batch, now)
        opened.forEach { log.info("automation session opened sessionId={} wallet={} firstItemId={}", it.id, it.wallet, it.firstItemId) }
        created.forEach { log.info("automation item discovered itemId={} sessionId={} mint={} state={}", it.id, it.sessionId, it.mint, it.state) }
        return DiscoveryRecord(created.size, belowFilter, opened.size, advanced)
    }

    /**
     * A watermark stored before coverage was tracked has no coverage start, which would leave every day pending and refuse every
     * capturing backfill for good. The nearest value its rows give is the one the reference SQL fills in: the wallet's first
     * session was created after its first scan, which reached back [initialLookback]; with no session, the watermark's own update
     * time stands in for the creation. Runs before the scan moves the watermark.
     */
    private fun ensureCoveredFrom(wallet: String, initialLookback: Duration) {
        val current = watermarks.findOneByWallet(wallet)?.takeIf { it.coveredFrom == null } ?: return
        val discoveryBegan = sessions.findEarliestCreatedAt(wallet) ?: current.updatedAt
        current.coveredFrom = discoveryBegan.minus(initialLookback)
        log.info("automation coverage start set, the watermark had none wallet={} coveredFrom={}", wallet, current.coveredFrom)
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
     * Whether an earlier buy may take [known]'s place: no capture step has touched it and its session still takes buys. An item
     * without a block time never sets a span or takes part in evaluation, so it may also be replaced in a `CLOSING` session.
     * Logs why not.
     */
    private fun replaceable(known: AutomationItemEntity, open: OpenSessions, earliest: DiscoveryCursor): Boolean {
        if (!writer.isUntouched(known, earliest)) return false
        if (open.isOpen(known.sessionId) || writer.isUntimed(known) && isClosing(known.sessionId)) return true
        log.warn("automation earlier buy found after its item's session stopped taking buys, item kept itemId={} sessionId={} mint={} earlierSignature={}",
            known.id, known.sessionId, known.mint, earliest.signature)
        return false
    }

    private fun isClosing(sessionId: String): Boolean = sessions.findOneById(sessionId)?.state == AutomationSessionState.CLOSING

    /** Returns whether the watermark advanced, which only a complete scan does. */
    private fun moveWatermark(batch: DiscoveryBatch, now: Instant): Boolean {
        val current = watermarks.findOneByWallet(batch.wallet)
        val resumeBefore = batch.resumeBefore
        if (resumeBefore != null) {
            holdCatchUp(batch, current, resumeBefore, now)
            return false
        }
        if (current == null) {
            return batch.newest?.let { watermarks.save(newWatermark(batch.wallet, it, now, batch.bound)) } != null
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
            watermarks.save(newWatermark(batch.wallet, batch.newest ?: DiscoveryCursor.ORIGIN, now, batch.bound).also {
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

    /** The wallet's first row: its first scan's bound is the lookback start, which is where live coverage begins (Rule 32). */
    private fun newWatermark(wallet: String, cursor: DiscoveryCursor, now: Instant, firstScanBound: DiscoveryBound) =
        AutomationWatermarkEntity(wallet, cursor.slot, cursor.signature, cursor.instructionIndex, cursor.innerIndex, now).also {
            it.coveredFrom = lookbackOf(firstScanBound)
        }

    private fun moveTo(current: AutomationWatermarkEntity, newest: DiscoveryCursor) {
        current.slot = newest.slot
        current.signature = newest.signature
        current.instructionIndex = newest.instructionIndex
        current.innerIndex = newest.innerIndex
    }
}
