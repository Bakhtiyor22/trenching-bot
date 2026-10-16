package com.trenching.bot.console.capture

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A wallet's recent activity for the new-capture form. Found pages are kept for [CACHE_TTL] per (wallet, limit, before), so
 * reopening the token step costs no requests; `refresh` bypasses the cache. Annotations are computed on every answer from the
 * current captures, after any provider call.
 */
class WalletActivityConsoleService(private val lookup: WalletActivityLookup, private val store: CaptureFileStore, private val clock: Clock) {
    private val cache = ConcurrentHashMap<WalletActivityRequest, CachedWalletActivity>()

    fun recent(address: String, limit: Int, before: String?, refresh: Boolean): WalletActivityView {
        val request = WalletActivityRequest(CaptureValidation.activityAddress(address), CaptureValidation.activityLimit(limit), CaptureValidation.activityBefore(before))
        val now = clock.instant()
        val cached = cache[request]?.takeIf { !refresh && isFresh(it, now) }
        val page = cached?.page ?: when (val result = lookup.recent(request, USAGE)) {
            is WalletActivityResult.Found -> result.page.also { remember(request, CachedWalletActivity(it, now), now) }
            is WalletActivityResult.Unavailable -> CaptureValidation.activityUnavailable(result.reason, result.retryAfter)
        }
        val captured = synchronized(store) {
            val records = store.sessionRecords()
            WalletActivityAnnotations.capturedIn(records, request.wallet, CaptureNamePolicy.displayNames(records))
        }
        return WalletActivityView(page, WalletActivityAnnotations.annotate(page.actions, captured), cached?.fetchedAt)
    }

    private fun remember(request: WalletActivityRequest, entry: CachedWalletActivity, now: Instant) {
        cache.values.removeIf { !isFresh(it, now) }
        cache[request] = entry
    }

    private fun isFresh(entry: CachedWalletActivity, now: Instant): Boolean = Duration.between(entry.fetchedAt, now) < CACHE_TTL

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
        val USAGE = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    }
}
