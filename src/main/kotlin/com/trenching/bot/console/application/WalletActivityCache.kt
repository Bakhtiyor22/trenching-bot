package com.trenching.bot.console.application

import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class CachedWalletActivity(val page: WalletActivityPage, val readAt: Instant)

/**
 * Wallet activity pages read in the last [ttl], per request (wallet, limit, before). Storing drops every expired page, so
 * the cache never holds more than one [ttl] of lookups. Memory only; a restart empties it.
 */
class WalletActivityCache(private val clock: Clock, private val ttl: Duration = DEFAULT_TTL) {
    private val entries = ConcurrentHashMap<WalletActivityRequest, CachedWalletActivity>()

    /** The page stored for [request] less than [ttl] ago. */
    fun fresh(request: WalletActivityRequest): CachedWalletActivity? = entries[request]?.takeIf { isFresh(it, clock.instant()) }

    fun store(request: WalletActivityRequest, page: WalletActivityPage) {
        val now = clock.instant()
        entries.values.removeIf { !isFresh(it, now) }
        entries[request] = CachedWalletActivity(page, now)
    }

    private fun isFresh(entry: CachedWalletActivity, now: Instant): Boolean = Duration.between(entry.readAt, now) < ttl

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofSeconds(60)
    }
}
