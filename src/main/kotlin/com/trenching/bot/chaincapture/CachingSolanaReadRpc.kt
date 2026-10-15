package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/**
 * Serves `getTransaction` from a [SharedTransactionCache] before asking [delegate], and keeps what the provider returns.
 * A hit sends no request and charges no budget; a missing (null) transaction and every failure are never stored.
 */
class CachingSolanaReadRpc(private val delegate: SolanaReadRpc, private val cache: SharedTransactionCache) : SolanaReadRpc {
    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        if (method != ChainCaptureFormat.GET_TRANSACTION) return delegate.call(method, params, budget)
        val key = cache.keyFor(params) ?: return delegate.call(method, params, budget)
        cache.read(key)?.let {
            budget.counters.cacheHit()
            return it
        }
        return delegate.call(method, params, budget).also { if (it.isObject) cache.write(key, it) }
    }
}
