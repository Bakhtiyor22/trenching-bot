package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.BackfillBatch
import com.trenching.bot.console.automation.domain.BackfillFilter
import com.trenching.bot.console.automation.domain.BackfillRecord
import com.trenching.bot.console.automation.domain.BackfillScanEnding
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.CaptureEstimate
import com.trenching.bot.console.automation.domain.CaptureFilter
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.SessionIdleRule
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.mapper.AutomationBackfillCodec
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Stores one backfill scan tick in a single transaction (plan Rules 38, 39 and 42): the buys it passes become items in the
 * backfill's own sessions, the counts and spend, and where the next tick continues. A dry run stores no item or session, only
 * its tally. The tick's actions are all inside the range and oldest first.
 *
 * Ticks arrive newest range first, so a later tick can find an earlier buy of a mint than the one an earlier tick stored. Nothing
 * of a backfill is captured until its scan ends, so that earlier buy takes the mint's item whenever no capture step has touched
 * it ([AutomationScanWriter.replaceSuperseded]): also from a live item, whichever state its session is in; the live session is
 * rebuilt from the items it keeps. A buy of an earlier backfill's that produced nothing (untouched, stopped by its budget, or
 * abandoned before a dataset) is taken over by a backfill that finds it again, whether the same buy or an earlier one. When the
 * scan reaches the range start, or the budget stops it, the backfill's open sessions start closing, because the scan will find
 * nothing more for them; a capturing backfill then waits for its items to be captured and a dry run is finished. A stopped scan
 * keeps how far it reached ([AutomationBackfillEntity.scannedTo]), which counts as covered through [AutomationCoverage]; the items
 * it already found still run. A stopped backfill never moves live coverage, so a new backfill may read its range again.
 *
 * A backfill that is no longer being scanned, because it was cancelled meanwhile, is left as it is; a concurrent change to its
 * row fails the commit on its version, and the tick is read again.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationBackfillRecorder(
    private val backfills: AutomationBackfillRepository,
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val writer: AutomationScanWriter,
) {
    private val log = LoggerFactory.getLogger(AutomationBackfillRecorder::class.java)

    /** Stores [batch]; null, storing nothing, when its backfill is not being scanned any more. */
    @Transactional
    fun record(batch: BackfillBatch, capture: CaptureFilter, idle: SessionIdleRule, now: Instant): BackfillRecord? {
        val backfill = backfills.findOneById(batch.backfillId)?.takeIf { it.state in BackfillState.SCANNING_STATES } ?: return null
        val filter = BackfillFilter(backfill.minSignalLamports, AutomationBackfillCodec.mints(backfill.mints))
        val sizeFilter = filter.captureFilter(capture)
        val buys = batch.actions.filter { it.side == TradeSide.BUY }
        val sized = buys.filter { sizeFilter.passes(it.solLamports) }
        val passing = sized.filter { filter.allowsMint(it.mint.value) }
        val skipped = sized.size - passing.size

        backfill.actionsScanned += batch.actions.size
        backfill.buysFound += buys.size
        backfill.buysPassing += passing.size
        backfill.skippedByFilter += skipped
        backfill.discoveryRequests += batch.requests
        backfill.skippedNoBlockTime += batch.untimed
        backfill.scannedTo = scannedTo(backfill, batch)
        backfill.updatedAt = now
        val created = if (backfill.dryRun) tally(backfill, passing, idle) else place(backfill, batch, sizeFilter, filter, passing, idle, now)
        backfill.itemsCreated += created.size
        when (val ending = batch.ending) {
            is BackfillScanEnding.Paused -> {
                backfill.state = BackfillState.SCANNING
                backfill.resumeBefore = ending.resumeBefore
            }
            BackfillScanEnding.ReachedStart -> {
                backfill.resumeBefore = null
                closeSessions(backfill)
                if (backfill.dryRun) finishDryRun(backfill, BackfillState.DONE, now) else backfill.state = BackfillState.CAPTURING
            }
            is BackfillScanEnding.OutOfBudget -> {
                backfill.resumeBefore = ending.resumeBefore
                closeSessions(backfill)
                if (backfill.dryRun) finishDryRun(backfill, BackfillState.STOPPED_BUDGET, now) else backfill.end(BackfillState.STOPPED_BUDGET, now)
            }
        }
        log.info("automation backfill scanned backfillId={} actions={} buys={} passing={} items={} requests={} state={}",
            backfill.id, batch.actions.size, buys.size, passing.size, created.size, batch.requests, backfill.state)
        return BackfillRecord(created.size, skipped, backfill.state)
    }

    /** How far back the scan has read: the range start once it reached it, else the furthest any tick got, never later than before. */
    private fun scannedTo(backfill: AutomationBackfillEntity, batch: BackfillBatch): Instant? = when (batch.ending) {
        BackfillScanEnding.ReachedStart -> backfill.rangeStart
        is BackfillScanEnding.Paused, is BackfillScanEnding.OutOfBudget ->
            listOfNotNull(backfill.scannedTo, batch.scannedTo?.coerceIn(backfill.rangeStart, backfill.rangeEnd)).minOrNull()
    }

    /** A dry run adds the buys passing the filter to its tally and creates nothing. */
    private fun tally(backfill: AutomationBackfillEntity, passing: List<WalletAction>, idle: SessionIdleRule): List<AutomationItemEntity> {
        val buys = passing.map { idle.calendar.dayOf(blockTime(it)).date to it.mint.value }
        backfill.summary = AutomationBackfillCodec.tallyJson(AutomationBackfillCodec.tally(backfill.summary).plus(buys))
        return emptyList()
    }

    /** Places the passing buys in the backfill's own open sessions, as live discovery does in the wallet's. */
    private fun place(
        backfill: AutomationBackfillEntity,
        batch: BackfillBatch,
        sizeFilter: CaptureFilter,
        filter: BackfillFilter,
        passing: List<WalletAction>,
        idle: SessionIdleRule,
        now: Instant,
    ): List<AutomationItemEntity> {
        val open = OpenSessions(backfill.wallet, sessions.findAllByBackfillIdAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(backfill.id, AutomationSessionState.OPEN),
            idle, now, backfill.id)
        val byMint = storedItems(backfill.wallet, passing)
        writer.replaceSuperseded(passing, byMint, open, idle, now, ::replaceable) { writer.isRetakeable(it, backfill.id) }
        val created = mutableListOf<AutomationItemEntity>()
        for (action in batch.actions) {
            if (action.side == TradeSide.SELL) continue
            if (!sizeFilter.passes(action.solLamports)) {
                open.covering(action.blockTime)?.let { it.belowFilterCount += 1 }
                continue
            }
            if (!filter.allowsMint(action.mint.value) || action.mint.value in byMint) continue
            val cursor = DiscoveryCursor.of(action)
            val itemId = AutomationIds.item(backfill.wallet, cursor)
            val session = open.place(itemId, action.blockTime)
            writer.newItem(backfill.wallet, backfill.id, session.id, itemId, cursor, action, now).also {
                created += it
                byMint[it.mint] = it
            }
        }
        val opened = open.opened
        sessions.saveAll(opened)
        writer.mergeAbsorbed(open)
        created.forEach { it.sessionId = open.current(it.sessionId) }
        items.saveAll(created)
        opened.forEach { log.info("automation session opened sessionId={} wallet={} backfillId={} firstItemId={}", it.id, it.wallet, backfill.id, it.firstItemId) }
        created.forEach { log.info("automation item discovered itemId={} sessionId={} backfillId={} mint={} state={}", it.id, it.sessionId, backfill.id, it.mint, it.state) }
        return created
    }

    /** The wallet's stored items for the mints of [buys], from any source; bounded by the tick's actions. */
    private fun storedItems(wallet: String, buys: List<WalletAction>): MutableMap<String, AutomationItemEntity> {
        val mints = buys.map { it.mint.value }.distinct()
        if (mints.isEmpty()) return mutableMapOf()
        return items.findAllByWalletAndMintIn(wallet, mints).associateBy { it.mint }.toMutableMap()
    }

    /** An earlier buy takes a stored item's place while no capture step has touched it, in whatever session it is (plan Rule 38). */
    private fun replaceable(known: AutomationItemEntity, earliest: DiscoveryCursor): Boolean = writer.isUntouched(known, earliest)

    /** The scan will find nothing more for the backfill's open sessions, so they start closing. */
    private fun closeSessions(backfill: AutomationBackfillEntity) {
        sessions.findAllByBackfillIdAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(backfill.id, AutomationSessionState.OPEN).forEach {
            it.state = AutomationSessionState.CLOSING
            log.info("automation session closing sessionId={} backfillId={} lastBuyAt={}", it.id, backfill.id, it.lastBuyAt)
        }
    }

    /**
     * A dry run ends with its estimate: the median requests of the wallet's captured items times the distinct mints it found that
     * a capturing backfill would capture (plan Rule 42, amended 2026-11-03). A mint that already has an item costs nothing more,
     * since a mint has one item, unless that item is one a backfill would take over ([AutomationScanWriter.isRetakeable]).
     */
    private fun finishDryRun(backfill: AutomationBackfillEntity, state: BackfillState, now: Instant) {
        val wallet = backfill.wallet
        val median = CaptureEstimate.median(items.countReportingRequests(wallet, AutomationItemState.CAPTURE_FINISHED)) { index ->
            items.findRequestsAscending(wallet, AutomationItemState.CAPTURE_FINISHED, PageRequest.of(Math.toIntExact(index), 1)).single().toLong()
        }
        val mints = AutomationBackfillCodec.tally(backfill.summary).mints
        val held = mints.chunked(MINTS_PER_READ).flatMap { items.findAllByWalletAndMintIn(wallet, it) }
            .filterNot { writer.isRetakeable(it, backfill.id) }.map { it.mint }.toSet()
        val toCapture = mints.count { it !in held }
        backfill.estimatedRequests = CaptureEstimate.requests(median, toCapture.toLong())
        log.info("automation backfill dry run estimated backfillId={} mints={} mintsToCapture={} medianRequests={} estimatedRequests={}",
            backfill.id, mints.size, toCapture, median, backfill.estimatedRequests)
        backfill.end(state, now)
    }

    private fun blockTime(action: WalletAction): Instant = checkNotNull(action.blockTime) { "A backfill scan reads buys with a block time only" }

    private companion object {
        /** Mints looked up per query when a dry run counts the ones that already have an item. */
        const val MINTS_PER_READ = 500
    }
}
