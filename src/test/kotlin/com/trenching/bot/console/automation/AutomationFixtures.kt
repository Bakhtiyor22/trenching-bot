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
import java.time.Instant

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
    ): AutomationProperties = AutomationProperties(enabled = true, captureEnabled = captureEnabled, wallet = WALLET, maxDiscoveryPages = maxDiscoveryPages)

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
    var pageOrder: (List<WalletAction>) -> List<WalletAction> = { it }

    override fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult {
        requests += request to usage
        unavailable?.let { return WalletActivityResult.Unavailable(it, null) }
        val listing = (actions.filter(::visible).map { Listed(it.signature, it.slot.value, it.blockTime, it) } +
            otherTransactions.map { Listed(it.first, it.second, otherBlockTimes[it.first], null) })
            .sortedWith(compareByDescending<Listed> { it.slot }.thenByDescending { it.signature.value })
        val start = request.before?.let { before -> listing.indexOfFirst { it.signature == before } + 1 } ?: 0
        val page = listing.drop(start).take(request.limit)
        val nextBefore = if (start + page.size < listing.size) page.last().signature else null
        val pageActions = page.mapNotNull { it.action }
        return WalletActivityResult.Found(WalletActivityPage(request.wallet, pageOrder(pageActions), 0, page.size, nextBefore, page.size,
            oldestBlockTime = page.lastOrNull { it.blockTime != null }?.blockTime))
    }

    private data class Listed(val signature: TransactionSignature, val slot: Long, val blockTime: Instant?, val action: WalletAction?)

    private fun visible(action: WalletAction): Boolean {
        val now = visibleAt?.instant() ?: return true
        return action.blockTime?.let { !it.isAfter(now) } ?: true
    }
}
