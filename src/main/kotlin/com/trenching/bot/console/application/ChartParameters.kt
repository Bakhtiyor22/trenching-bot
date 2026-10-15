package com.trenching.bot.console.application

import com.trenching.bot.common.MarketPair
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import java.time.Instant

object ChartLimits {
    const val DEFAULT_MAX_BUCKETS = 300
    const val MAX_BUCKETS = 2_000
    const val DEFAULT_MARKER_LIMIT = 500
    const val MAX_MARKER_LIMIT = 2_000
    const val MAX_WALLETS = 20
}

/** A validated [ChartQuery]: limits defaulted and clamped, the pair, wallets and cursor parsed. */
data class ChartParameters(
    val pair: MarketPair?,
    val from: Instant?,
    val to: Instant?,
    val maxBuckets: Int,
    val markerLimit: Int,
    val wallets: Set<String>,
    val cursor: ChartPosition?,
) {
    fun contains(time: Instant): Boolean = (from == null || time >= from) && (to == null || time <= to)

    companion object {
        private const val FROM = "from"
        private const val MAX_BUCKETS = "maxBuckets"
        private const val MARKER_LIMIT = "markerLimit"
        private const val WALLET = "wallet"
        private const val CURSOR = "cursor"

        /** @throws InvalidConsoleRequestException for the first invalid parameter. */
        fun of(query: ChartQuery): ChartParameters {
            if (query.from != null && query.to != null && query.from > query.to) {
                throw InvalidConsoleRequestException(FROM, "must not be after to")
            }
            val wallets = query.wallets.toSet()
            if (wallets.size > ChartLimits.MAX_WALLETS) {
                throw InvalidConsoleRequestException(WALLET, "accepts at most ${ChartLimits.MAX_WALLETS} wallets")
            }
            wallets.forEach { wallet ->
                ConsoleAddresses.walletOrNull(wallet) ?: throw InvalidConsoleRequestException(WALLET, "must be a base58 wallet address")
            }
            return ChartParameters(
                pair = query.pair?.let(ConsolePairs::parse),
                from = query.from,
                to = query.to,
                maxBuckets = limit(MAX_BUCKETS, query.maxBuckets, ChartLimits.DEFAULT_MAX_BUCKETS, ChartLimits.MAX_BUCKETS),
                markerLimit = limit(MARKER_LIMIT, query.markerLimit, ChartLimits.DEFAULT_MARKER_LIMIT, ChartLimits.MAX_MARKER_LIMIT),
                wallets = wallets,
                cursor = query.cursor?.let { ChartPosition.decode(it) ?: throw InvalidConsoleRequestException(CURSOR, "is not a valid cursor") },
            )
        }

        private fun limit(field: String, requested: Int?, default: Int, cap: Int): Int {
            if (requested != null && requested < 1) throw InvalidConsoleRequestException(field, "must be at least 1")
            return minOf(requested ?: default, cap)
        }
    }
}
