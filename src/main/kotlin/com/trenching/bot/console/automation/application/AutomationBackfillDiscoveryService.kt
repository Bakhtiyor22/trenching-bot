package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.BackfillBatch
import com.trenching.bot.console.automation.domain.BackfillScanEnding
import com.trenching.bot.console.automation.domain.BackfillScanOutcome
import com.trenching.bot.console.automation.domain.BackfillScanTarget
import com.trenching.bot.console.automation.domain.DiscoveryBound
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityLimits
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

/**
 * Reads the wallet's history for its backfill, newest first from the range end down to the range start (plan Rule 37), at most
 * `max-discovery-pages` pages a tick, and hands each tick to [AutomationBackfillRecorder]. Provider calls happen outside any
 * transaction.
 *
 * The listing can only be entered after a signature, never at a time. A backfill's range lies behind what live discovery has
 * read, so entering at the newest transaction would spend pages on days already covered. The first tick instead enters after the
 * wallet's earliest stored buy at or past the range end ([BackfillScanTarget.seed]): that buy is in the listing, and everything
 * between it and the range end is read but dropped by its block time, which costs the pages that stretch holds and none of the
 * live days beyond it. With no such buy it starts at the newest transaction, and the budget bounds the cost. A tick later continues
 * from the cursor the earlier one stored.
 *
 * A page is read only while the backfill's spend plus the most a page may cost stays within its budget; otherwise the scan stops
 * there. The scan pauses while the rolling request total is within `backfill-reserve-requests` of the cap. A provider that cannot
 * be read on a tick's first page skips the tick: nothing is stored and the next tick reads the same page. One that fails after
 * pages were read keeps those pages, which the requests already paid for: they are stored as a paused tick whose cursor is the
 * failing page, which the next tick reads again. Either way the failure is logged with how many ticks in a row have failed.
 * Actions without a block time cannot be placed in the range; they are left out and counted on the backfill.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationBackfillDiscoveryService(
    private val lookup: WalletActivityLookup,
    private val transaction: AutomationBackfillTransaction,
    private val recorder: AutomationBackfillRecorder,
    private val properties: AutomationProperties,
    private val usage: ApiUsageReports,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationBackfillDiscoveryService::class.java)
    private val consecutiveFailures = AtomicInteger()

    fun scan(): BackfillScanOutcome {
        val wallet = properties.walletAddress()
        val target = transaction.scanTarget(wallet.value) ?: return BackfillScanOutcome.NothingToScan
        val requestsLastDay = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests
        if (!properties.requestCap().admitsBackfillDiscovery(requestsLastDay, properties.backfillReserveRequests)) {
            log.warn("automation backfill discovery paused backfillId={} requestsLast24h={} maxRequestsPer24h={} reserveRequests={}",
                target.id, requestsLastDay, properties.maxRequestsPer24h, properties.backfillReserveRequests)
            return BackfillScanOutcome.ReserveReached
        }
        val now = clock.instant()
        val batch = when (val read = read(wallet, target)) {
            is BackfillRead.Unavailable -> {
                log.warn("automation backfill discovery skipped backfillId={} reason={} consecutiveFailures={}",
                    target.id, read.reason, consecutiveFailures.incrementAndGet())
                return BackfillScanOutcome.Unavailable(read.reason)
            }
            is BackfillRead.Batch -> read.batch.also { failedMidTick(target, read) }
        }
        return try {
            recorder.record(batch, properties.captureFilter(), properties.idleRule(), now)?.let(BackfillScanOutcome::Recorded) ?: BackfillScanOutcome.NothingToScan
        } catch (e: OptimisticLockingFailureException) {
            log.warn("automation backfill changed while its scan was stored, the tick is dropped backfillId={}", target.id, e)
            BackfillScanOutcome.NothingToScan
        }
    }

    /** A tick that read to its end resets the count of failures in a row; one the provider cut short adds to it and is logged. */
    private fun failedMidTick(target: BackfillScanTarget, read: BackfillRead.Batch) {
        val reason = read.interrupted
        if (reason == null) {
            consecutiveFailures.set(0)
            return
        }
        log.warn("automation backfill discovery stopped mid-tick, the pages read are kept backfillId={} reason={} requests={} resumeBefore={} consecutiveFailures={}",
            target.id, reason, read.batch.requests, (read.batch.ending as? BackfillScanEnding.Paused)?.resumeBefore, consecutiveFailures.incrementAndGet())
    }

    private sealed interface BackfillRead {
        /** [interrupted] is why the provider could not be read after the first page, null when the tick read what it set out to. */
        data class Batch(val batch: BackfillBatch, val interrupted: WalletActivityUnavailable? = null) : BackfillRead

        data class Unavailable(val reason: WalletActivityUnavailable) : BackfillRead
    }

    private fun read(wallet: WalletAddress, target: BackfillScanTarget): BackfillRead {
        val bound = DiscoveryBound.Since(target.range.from)
        val actions = mutableListOf<WalletAction>()
        var before = target.startBefore?.let(TransactionSignature::of)
        var requests = 0L
        var pages = 0
        var untimed = 0
        var scannedTo: Instant? = null
        fun batch(ending: BackfillScanEnding, interrupted: WalletActivityUnavailable? = null) =
            batch(target, actions, requests, ending, scannedTo, untimed, interrupted)
        repeat(properties.maxDiscoveryPages) {
            if (!target.budget.admits(requests + AutomationLimits.DISCOVERY_PAGE_REQUESTS)) {
                return batch(BackfillScanEnding.OutOfBudget(cursor(before)))
            }
            val page = when (val result = lookup.recent(WalletActivityRequest(wallet, WalletActivityLimits.DEFAULT_LIMIT, before), USAGE)) {
                is WalletActivityResult.Found -> result.page
                is WalletActivityResult.Unavailable ->
                    return if (pages == 0) BackfillRead.Unavailable(result.reason) else batch(BackfillScanEnding.Paused(cursor(before)), result.reason)
            }
            pages += 1
            requests += page.requestsUsed
            val newestFirst = page.actions.sortedByDescending(DiscoveryCursor::of)
            val unseen = newestFirst.takeWhile { !bound.reached(it) }
            actions += unseen.filter { it.blockTime?.isBefore(target.range.to) == true }
            untimed += unseen.count { it.blockTime == null }
            scannedTo = page.oldestBlockTime?.let(::coveredAfter) ?: scannedTo
            val next = page.nextBefore
            if (unseen.size < newestFirst.size || next == null || bound.listingReached(page.oldestBlockTime)) {
                warnUntimed(target, untimed)
                return batch(BackfillScanEnding.ReachedStart)
            }
            before = next
        }
        warnUntimed(target, untimed)
        return batch(BackfillScanEnding.Paused(cursor(before)))
    }

    private fun batch(
        target: BackfillScanTarget,
        read: List<WalletAction>,
        requests: Long,
        ending: BackfillScanEnding,
        scannedTo: Instant?,
        untimed: Int,
        interrupted: WalletActivityUnavailable?,
    ): BackfillRead.Batch {
        val oldestFirst = read.distinctBy(DiscoveryCursor::of).sortedBy(DiscoveryCursor::of)
        return BackfillRead.Batch(BackfillBatch(target.id, target.range, oldestFirst, requests, ending, scannedTo, untimed), interrupted)
    }

    /**
     * The instant from which every transaction is known to have been read once a page's oldest listed one, at [oldestListed], was:
     * the next whole second. Block times have a resolution of one second, so an unread transaction of the same slot may share it.
     */
    private fun coveredAfter(oldestListed: Instant): Instant = oldestListed.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)

    /** Where the next tick continues: the page cap and the budget stop only after a page was read, so a cursor exists. */
    private fun cursor(before: TransactionSignature?): String = checkNotNull(before) { "A backfill scan stops after reading a page" }.value

    private fun warnUntimed(target: BackfillScanTarget, untimed: Int) {
        if (untimed > 0) log.warn("automation backfill skipped actions without a block time backfillId={} actions={}", target.id, untimed)
    }

    private companion object {
        val USAGE = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    }
}
