package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.domain.TradeSide
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger

object AutomationFixtures {
    val WALLET: String = Base58.encode(ByteArray(32) { 9 })

    /** 20:00 on 2 November 2026 in UTC+5, inside the default polling window. */
    val EVENING: Instant = Instant.parse("2026-11-02T15:00:00Z")

    /** A curve payment the default filter captures (2 SOL) and one it skips (1 SOL). */
    const val CAPTURED_LAMPORTS = 2_000_000_000L
    const val SKIPPED_LAMPORTS = 1_000_000_000L

    fun properties(
        captureEnabled: Boolean = false,
        maxDiscoveryPages: Int = 5,
        pollAlways: Boolean = false,
        evaluationWindowSessions: Int = 7,
        minSignals: Int = 10,
    ): AutomationProperties = AutomationProperties(
        enabled = true, captureEnabled = captureEnabled, wallet = WALLET, maxDiscoveryPages = maxDiscoveryPages, pollAlways = pollAlways,
        evaluationWindowSessions = evaluationWindowSessions, minSignals = minSignals,
    )

    fun mint(n: Int): MintAddress = MintAddress.of(Base58.encode(ByteArray(32) { (n + 20).toByte() }))

    fun signature(n: Int): TransactionSignature =
        TransactionSignature.of(Base58.encode(ByteArray(64).also { it[0] = 5; it[62] = (n shr 8).toByte(); it[63] = n.toByte() }))

    /** Transaction [n] buys mint [mint] at slot [slot]; its instruction 2 is a top-level event unless [inner] is set. */
    fun buy(n: Int, slot: Long, blockTime: Instant?, lamports: Long = CAPTURED_LAMPORTS, mint: Int = n, inner: Int? = null): WalletAction =
        WalletAction(signature(n), 2, inner, Slot.of(slot), blockTime, TradeSide.BUY, mint(mint), BigInteger.valueOf(lamports), BigInteger.valueOf(1_000_000))

    fun sell(n: Int, slot: Long, blockTime: Instant, mint: Int): WalletAction =
        WalletAction(signature(n), 2, null, Slot.of(slot), blockTime, TradeSide.SELL, mint(mint), BigInteger.valueOf(CAPTURED_LAMPORTS), BigInteger.valueOf(1_000_000))
}

/**
 * A wallet's listing, newest first by slot then signature, served [request.limit] transactions per page with `nextBefore` set
 * while older ones remain. Each action is one transaction; [otherTransactions] (signature to slot) hold no Pump.fun action, and
 * have the block time in [otherBlockTimes] in the listing, if any. A page reports the oldest block time it listed.
 * With [visibleAt] set, an action appears only once its block time has passed.
 */
class FakeWalletActivityLookup(private val visibleAt: Clock? = null) : WalletActivityLookup {
    val actions = mutableListOf<WalletAction>()
    val otherTransactions = mutableListOf<Pair<TransactionSignature, Long>>()
    val otherBlockTimes = mutableMapOf<TransactionSignature, Instant>()
    val requests = mutableListOf<Pair<WalletActivityRequest, ApiUsageContext>>()
    var unavailable: WalletActivityUnavailable? = null

    /** From the n-th request on (1 is the first), the provider cannot be read, as a rate limit that starts mid-scan. */
    var unavailableFromRequest: Int? = null

    /** What each page reports as its provider requests, instead of one per transaction listed. */
    var pageRequests: Int? = null
    var failure: RuntimeException? = null
    var pageOrder: (List<WalletAction>) -> List<WalletAction> = { it }

    override fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult {
        requests += request to usage
        failure?.let { throw it }
        unavailable?.let { return WalletActivityResult.Unavailable(it, null) }
        unavailableFromRequest?.takeIf { requests.size >= it }?.let { return WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, null) }
        val listing = (actions.filter(::visible).map { Listed(it.signature, it.slot.value, it.blockTime, it) } +
            otherTransactions.map { Listed(it.first, it.second, otherBlockTimes[it.first], null) })
            .sortedWith(compareByDescending<Listed> { it.slot }.thenByDescending { it.signature.value })
        val start = request.before?.let { before -> listing.indexOfFirst { it.signature == before } + 1 } ?: 0
        val page = listing.drop(start).take(request.limit)
        val nextBefore = if (start + page.size < listing.size) page.last().signature else null
        val pageActions = page.mapNotNull { it.action }
        return WalletActivityResult.Found(WalletActivityPage(request.wallet, pageOrder(pageActions), 0, page.size, nextBefore, pageRequests ?: page.size,
            oldestBlockTime = page.lastOrNull { it.blockTime != null }?.blockTime))
    }

    private data class Listed(val signature: TransactionSignature, val slot: Long, val blockTime: Instant?, val action: WalletAction?)

    private fun visible(action: WalletAction): Boolean {
        val now = visibleAt?.instant() ?: return true
        return action.blockTime?.let { !it.isAfter(now) } ?: true
    }
}

/** A scheduler that keeps what it is given, so a test runs it as the scheduling thread would. */
class RecordingTaskScheduler : TaskScheduler {
    val tasks = ArrayDeque<Runnable>()
    val starts = mutableListOf<Instant>()

    fun runNext() = tasks.removeFirst().run()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        tasks += task
        starts += startTime
        return QueuedFuture
    }

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*>? = unused()

    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> = unused()

    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> = unused()

    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> = unused()

    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> = unused()

    private fun unused(): Nothing = error("only a single start is used")
}

/** The handle [RecordingTaskScheduler] returns: the task runs only when the test says so, so nothing waits on it. */
private object QueuedFuture : ScheduledFuture<Unit> {
    override fun getDelay(unit: TimeUnit): Long = 0

    override fun compareTo(other: Delayed): Int = 0

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = false

    override fun get() = error("a queued task has no result")

    override fun get(timeout: Long, unit: TimeUnit) = error("a queued task has no result")
}
