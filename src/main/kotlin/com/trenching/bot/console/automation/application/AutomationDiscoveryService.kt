package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.LogLine
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.DiscoveryBatch
import com.trenching.bot.console.automation.domain.DiscoveryBound
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.DiscoveryMode
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
 * In window mode, outside the window it scans only while an open session whose last buy is before the window's end has had its
 * idle time pass without a complete scan covering it, so the night's last session closes the same morning; once none waits, it reads nothing
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
    private val activity: AutomationItemLog,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationDiscoveryService::class.java)

    /** One scan; with [ignoreWindow], as a manual pass has it, whatever the mode and the time of day. */
    fun discover(ignoreWindow: Boolean = false): DiscoveryOutcome {
        val now = clock.instant()
        val wallet = properties.walletAddress()
        if (!ignoreWindow && !scansNow(wallet, now)) return DiscoveryOutcome.OutsideWindow
        activity.discoveryScanRan()
        val watermark = transaction.watermark(wallet.value)
        val bound = watermark?.bound() ?: DiscoveryBound.Since(now.minus(properties.initialLookback))
        return when (val scan = scan(wallet, bound, watermark?.resumeBefore?.let(TransactionSignature::of))) {
            is DiscoveryScan.Unavailable -> {
                runtime.discoveryUnavailable(scan.reason)
                LogLine("Discovery skipped: the activity provider could not be read, so nothing was stored and the next scan reads the same range.")
                    .field("wallet", wallet.value).field("reason", scan.reason).warn(log)
                DiscoveryOutcome.Unavailable(scan.reason)
            }
            is DiscoveryScan.Read -> recorded(scan, now)
        }
    }

    /** Always-on scans every interval. The window scans inside it, and outside it only while a session's close awaits a scan. */
    private fun scansNow(wallet: WalletAddress, now: Instant): Boolean = when (val mode = properties.discoveryMode()) {
        DiscoveryMode.Always -> true
        is DiscoveryMode.Window ->
            mode.scansAt(now) || transaction.closeAwaitsScan(wallet.value, properties.idleRule(), mode.window, now)
    }

    private fun recorded(scan: DiscoveryScan.Read, now: Instant): DiscoveryOutcome {
        val record = transaction.record(scan.batch, properties.captureFilter(), properties.idleRule(), properties.initialLookback, now)
        runtime.discovered(now)
        logRecord(scan, record)
        return DiscoveryOutcome.Recorded(record)
    }

    /** Pages from [resumeBefore], or from the newest transaction when no catch-up is in progress, down to [bound]. */
    private fun scan(wallet: WalletAddress, bound: DiscoveryBound, resumeBefore: TransactionSignature?): DiscoveryScan {
        val read = mutableListOf<WalletAction>()
        var before = resumeBefore
        var pages = 0
        var requests = 0
        repeat(properties.maxDiscoveryPages) {
            val page = when (val result = lookup.recent(WalletActivityRequest(wallet, WalletActivityLimits.DEFAULT_LIMIT, before), USAGE)) {
                is WalletActivityResult.Found -> result.page
                is WalletActivityResult.Unavailable -> return DiscoveryScan.Unavailable(result.reason)
            }
            val newestFirst = page.actions.sortedByDescending(DiscoveryCursor::of)
            val unseen = newestFirst.takeWhile { !bound.reached(it) }
            read += unseen
            pages++
            requests += page.requestsUsed
            val next = page.nextBefore
            if (unseen.size < newestFirst.size || next == null || bound.listingReached(page.oldestBlockTime)) {
                return DiscoveryScan.Read(batch(wallet, read, bound, null), pages, requests)
            }
            before = next
        }
        return DiscoveryScan.Read(batch(wallet, read, bound, before?.value), pages, requests)
    }

    private fun batch(wallet: WalletAddress, read: List<WalletAction>, bound: DiscoveryBound, resumeBefore: String?): DiscoveryBatch {
        val oldestFirst = read.distinctBy(DiscoveryCursor::of).sortedBy(DiscoveryCursor::of)
        return DiscoveryBatch(wallet.value, oldestFirst, oldestFirst.lastOrNull()?.let(DiscoveryCursor::of), bound, resumeBefore)
    }

    /** A scan that found buys is an event (the buys say which); one that found none, or that the page limit cut short, is routine catch-up. */
    private fun logRecord(scan: DiscoveryScan.Read, record: DiscoveryRecord) {
        val found = record.itemsCreated
        val buys = if (found == 1) "buy" else "buys"
        val line = when {
            scan.batch.complete && found == 0 -> LogLine("Discovery found no new buys.")
            scan.batch.complete -> LogLine("Discovery found {} new {}.", found, buys)
            found == 0 -> LogLine("Discovery found no new buys yet; it stopped at the page limit and the catch-up continues on the next scan.")
            else -> LogLine("Discovery found {} new {}; it stopped at the page limit and the catch-up continues on the next scan.", found, buys)
        }
        line.field("read", scan.batch.actions.size).field("belowFilter", record.belowFilter).field("pages", scan.pages).field("requests", scan.requests)
            .field("wallet", scan.batch.wallet)
        if (found > 0) line.info(log) else line.debug(log)
    }

    private companion object {
        val USAGE = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    }
}
