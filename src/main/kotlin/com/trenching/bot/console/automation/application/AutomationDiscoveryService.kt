package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.DiscoveryBatch
import com.trenching.bot.console.automation.domain.DiscoveryBound
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.DiscoveryOutcome
import com.trenching.bot.console.automation.domain.DiscoveryRecord
import com.trenching.bot.console.automation.domain.DiscoveryScan
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityLimits
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Finds the wallet's new Pump.fun buys inside the polling window (plan Rules 5–12). It pages the wallet's activity newest
 * first, outside any transaction, until it reaches the watermark (or, on the first scan, the initial lookback) or
 * `max-discovery-pages`; then [AutomationDiscoveryTransaction] stores the items, sessions and watermark at once. A scan the page
 * cap cuts short leaves a cursor the next scan continues from, so a backlog of any size is reached. A provider that cannot be
 * read skips the tick and stores nothing.
 *
 * Outside the window it scans only while an open session whose last buy is before the window's end has had its idle time pass
 * without a complete scan covering it, so the night's last session closes the same morning; once none waits, it reads nothing
 * until the window opens again. A morning scan can still extend such a session with a buy just after the window closed; its last
 * buy is then after the window's end and it waits for the evening, like any session the daytime buys open.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationDiscoveryService(
    private val lookup: WalletActivityLookup,
    private val transaction: AutomationDiscoveryTransaction,
    private val properties: AutomationProperties,
    private val runtime: AutomationRuntime,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationDiscoveryService::class.java)

    fun discover(): DiscoveryOutcome {
        val now = clock.instant()
        val wallet = properties.walletAddress()
        val window = properties.pollWindow()
        if (!window.contains(now) && !transaction.closeAwaitsScan(wallet.value, properties.idleRule(), window, now)) {
            return DiscoveryOutcome.OutsideWindow
        }
        val watermark = transaction.watermark(wallet.value)
        val bound = watermark?.bound() ?: DiscoveryBound.Since(now.minus(properties.initialLookback))
        return when (val scan = scan(wallet, bound, watermark?.resumeBefore?.let(TransactionSignature::of))) {
            is DiscoveryScan.Unavailable -> {
                runtime.discoveryUnavailable(scan.reason)
                log.warn("automation discovery skipped wallet={} reason={}", wallet.value, scan.reason)
                DiscoveryOutcome.Unavailable(scan.reason)
            }
            is DiscoveryScan.Read -> recorded(scan.batch, now)
        }
    }

    private fun recorded(batch: DiscoveryBatch, now: Instant): DiscoveryOutcome {
        val record = transaction.record(batch, properties.captureFilter(), properties.idleRule(), now)
        runtime.discovered(now)
        logRecord(batch, record)
        return DiscoveryOutcome.Recorded(record)
    }

    /** Pages from [resumeBefore], or from the newest transaction when no catch-up is in progress, down to [bound]. */
    private fun scan(wallet: WalletAddress, bound: DiscoveryBound, resumeBefore: TransactionSignature?): DiscoveryScan {
        val read = mutableListOf<WalletAction>()
        var before = resumeBefore
        repeat(properties.maxDiscoveryPages) {
            val page = when (val result = lookup.recent(WalletActivityRequest(wallet, WalletActivityLimits.DEFAULT_LIMIT, before), USAGE)) {
                is WalletActivityResult.Found -> result.page
                is WalletActivityResult.Unavailable -> return DiscoveryScan.Unavailable(result.reason)
            }
            val newestFirst = page.actions.sortedByDescending(DiscoveryCursor::of)
            val unseen = newestFirst.takeWhile { !bound.reached(it) }
            read += unseen
            val next = page.nextBefore
            if (unseen.size < newestFirst.size || next == null || bound.listingReached(page.oldestBlockTime)) {
                return DiscoveryScan.Read(batch(wallet, read, bound, null))
            }
            before = next
        }
        return DiscoveryScan.Read(batch(wallet, read, bound, before?.value))
    }

    private fun batch(wallet: WalletAddress, read: List<WalletAction>, bound: DiscoveryBound, resumeBefore: String?): DiscoveryBatch {
        val oldestFirst = read.distinctBy(DiscoveryCursor::of).sortedBy(DiscoveryCursor::of)
        return DiscoveryBatch(wallet.value, oldestFirst, oldestFirst.lastOrNull()?.let(DiscoveryCursor::of), bound, resumeBefore)
    }

    private fun logRecord(batch: DiscoveryBatch, record: DiscoveryRecord) {
        if (batch.complete) {
            log.info("automation discovery wallet={} actions={} items={} belowFilter={} sessionsOpened={} watermarkAdvanced={}",
                batch.wallet, batch.actions.size, record.itemsCreated, record.belowFilter, record.sessionsOpened, record.watermarkAdvanced)
        } else {
            log.warn("automation discovery reached max pages wallet={} actions={} items={} maxPages={} resumeBefore={}",
                batch.wallet, batch.actions.size, record.itemsCreated, properties.maxDiscoveryPages, batch.resumeBefore)
        }
    }

    private companion object {
        val USAGE = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    }
}
