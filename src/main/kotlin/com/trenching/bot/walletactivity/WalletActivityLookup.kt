package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.domain.TradeSide
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

/** A wallet's recent Pump.fun curve trades, read live from its user volume accumulator listing, for picking a token. */
fun interface WalletActivityLookup {
    fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult
}

/** [limit] is the number of successful listed transactions to read, 1 to [WalletActivityLimits.MAX_LIMIT]; [before] continues an earlier page. */
data class WalletActivityRequest(val wallet: WalletAddress, val limit: Int, val before: TransactionSignature?)

sealed interface WalletActivityResult {
    data class Found(val page: WalletActivityPage) : WalletActivityResult

    /** [retryAfter] is the provider's `Retry-After`, when it sent one with a rate-limited reply. */
    data class Unavailable(val reason: WalletActivityUnavailable, val retryAfter: Duration?) : WalletActivityResult
}

enum class WalletActivityUnavailable { RPC_NOT_CONFIGURED, PROVIDER_RATE_LIMITED, PROVIDER_UNAVAILABLE, NOT_MAINNET }

/**
 * [scanned] counts the listing entries this page consumed, failed ones included; [unreadable] the fetched transactions with no
 * decodable trade by the wallet. [nextBefore] continues with older entries, null once the listing has none left.
 */
data class WalletActivityPage(
    val wallet: WalletAddress,
    val actions: List<WalletAction>,
    val unreadable: Int,
    val scanned: Int,
    val nextBefore: TransactionSignature?,
    val requestsUsed: Int,
    val source: String = SOURCE,
) {
    companion object {
        const val SOURCE = "pumpfun-user-volume-accumulator"
    }
}

/**
 * One Pump.fun `TradeEvent` whose user is the wallet, identified by ([signature], [instructionIndex], [innerIndex]).
 * [solLamports] is the curve payment excluding fees and [tokenRaw] the raw token amount, both from the event.
 */
data class WalletAction(
    val signature: TransactionSignature,
    val instructionIndex: Int,
    val innerIndex: Int?,
    val slot: Slot,
    val blockTime: Instant?,
    val side: TradeSide,
    val mint: MintAddress,
    val solLamports: BigInteger,
    val tokenRaw: BigInteger,
    val tokenDecimals: Int = PUMPFUN_TOKEN_DECIMALS,
    val venue: String = PUMPFUN_CURVE,
) {
    companion object {
        const val PUMPFUN_TOKEN_DECIMALS = 6
        const val PUMPFUN_CURVE = "PUMPFUN_CURVE"
    }
}

/**
 * What one lookup may cost. A page reads at most [MAX_LIMIT] transactions from one listing page and checks the genesis hash
 * once per process. [maxRequests] leaves room for each request's retries, so 152 requests at the maximum limit.
 */
object WalletActivityLimits {
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50
    const val MIN_LISTING_ENTRIES = 100
    const val LISTING_ENTRIES_PER_TRANSACTION = 3
    const val ATTEMPTS_PER_REQUEST = 3
    const val MAX_BYTES = 64L * 1024 * 1024
    val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(30)

    fun isValidLimit(limit: Int): Boolean = limit in 1..MAX_LIMIT

    fun listingEntries(limit: Int): Int = maxOf(limit * LISTING_ENTRIES_PER_TRANSACTION, MIN_LISTING_ENTRIES)

    fun maxRequests(limit: Int): Int = ATTEMPTS_PER_REQUEST * limit + 2
}
