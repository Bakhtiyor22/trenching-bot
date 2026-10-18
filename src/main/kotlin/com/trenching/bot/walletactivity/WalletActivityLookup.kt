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

/** A wallet's recent Pump.fun curve trades, read live from its Pump.fun user volume accumulator listing. */
fun interface WalletActivityLookup {
    /** [usage] attributes every request the lookup sends in API usage. */
    fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult
}

/** One page of at most [limit] listed transactions older than [before], or the newest when [before] is null. */
data class WalletActivityRequest(val wallet: WalletAddress, val limit: Int, val before: TransactionSignature?) {
    init {
        require(limit in WalletActivityLimits.MIN_LIMIT..WalletActivityLimits.MAX_LIMIT) {
            "limit must be between ${WalletActivityLimits.MIN_LIMIT} and ${WalletActivityLimits.MAX_LIMIT} limit=$limit"
        }
    }
}

sealed interface WalletActivityResult {
    data class Found(val page: WalletActivityPage) : WalletActivityResult

    /** [retryAfter] is the provider's `Retry-After`, present only when a rate-limited reply carried one. */
    data class Unavailable(val reason: WalletActivityUnavailable, val retryAfter: Duration?) : WalletActivityResult
}

enum class WalletActivityUnavailable { RPC_NOT_CONFIGURED, PROVIDER_RATE_LIMITED, PROVIDER_UNAVAILABLE, NOT_MAINNET }

/**
 * [scanned] counts the listing entries the page covers, failed ones included; [unreadable] counts the successful ones that
 * yielded no decodable trade of the wallet. [nextBefore] is the last entry scanned, to pass as `before` for the next older
 * page, or null when the listing ended within this page. [requestsUsed] counts the provider requests actually sent.
 */
data class WalletActivityPage(
    val wallet: WalletAddress,
    val actions: List<WalletAction>,
    val unreadable: Int,
    val scanned: Int,
    val nextBefore: TransactionSignature?,
    val requestsUsed: Int,
    val source: String = WalletActivityLimits.SOURCE,
)

/**
 * One Pump.fun `TradeEvent` whose user is the wallet, identified by ([signature], [instructionIndex], [innerIndex]).
 * [solLamports] is the curve payment of the event, fees excluded; [tokenRaw] is in units of [tokenDecimals].
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
    val tokenDecimals: Int = WalletActivityLimits.TOKEN_DECIMALS,
    val venue: String = WalletActivityLimits.VENUE,
)
