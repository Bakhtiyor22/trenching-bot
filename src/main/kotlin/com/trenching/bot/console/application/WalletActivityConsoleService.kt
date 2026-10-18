package com.trenching.bot.console.application

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.console.domain.CapturedTokens
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.InvalidWalletAddressException
import com.trenching.bot.console.domain.WalletActivityAnnotations
import com.trenching.bot.console.domain.WalletActivityUnavailableException
import com.trenching.bot.console.domain.WalletActivityView
import com.trenching.bot.walletactivity.WalletActivityLimits
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult

/**
 * Serves a wallet's recent activity for picking a token: from [cache] when the same page was read in the last minute,
 * unless [activity]'s `refresh` asks for a new read, then annotated with the captures that hold each mint as they are now.
 * A page served from the cache sent no request, so it reports `requestsUsed` 0.
 */
class WalletActivityConsoleService(
    private val lookup: WalletActivityLookup,
    private val cache: WalletActivityCache,
    private val captures: CapturedTokens,
) {
    /** [limit] defaults to [WalletActivityLimits.DEFAULT_LIMIT]; a blank [before] reads the newest page. */
    fun activity(address: String, limit: Int?, before: String?, refresh: Boolean): WalletActivityView {
        val request = request(address, limit ?: WalletActivityLimits.DEFAULT_LIMIT, before?.takeUnless { it.isBlank() })
        val cached = if (refresh) null else cache.fresh(request)
        val page = cached?.let(::fromCache) ?: read(request)
        return WalletActivityAnnotations.annotate(page, captures.capturesOf(request.wallet), cached?.readAt)
    }

    private fun fromCache(cached: CachedWalletActivity): WalletActivityPage = cached.page.copy(requestsUsed = 0)

    private fun read(request: WalletActivityRequest): WalletActivityPage = when (val result = lookup.recent(request, USAGE)) {
        is WalletActivityResult.Found -> result.page.also { cache.store(request, it) }
        is WalletActivityResult.Unavailable -> throw WalletActivityUnavailableException(result.reason, result.retryAfter)
    }

    private fun request(address: String, limit: Int, before: String?): WalletActivityRequest {
        val wallet = ConsoleAddresses.walletOrNull(address) ?: throw InvalidWalletAddressException(address.length)
        if (limit !in WalletActivityLimits.MIN_LIMIT..WalletActivityLimits.MAX_LIMIT) {
            throw InvalidConsoleRequestException(LIMIT, "must be between ${WalletActivityLimits.MIN_LIMIT} and ${WalletActivityLimits.MAX_LIMIT}")
        }
        val cursor = before?.let { ConsoleAddresses.signatureOrNull(it) ?: throw InvalidConsoleRequestException(BEFORE, "must be a transaction signature") }
        return WalletActivityRequest(wallet, limit, cursor)
    }

    private companion object {
        val USAGE = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
        const val LIMIT = "limit"
        const val BEFORE = "before"
    }
}
