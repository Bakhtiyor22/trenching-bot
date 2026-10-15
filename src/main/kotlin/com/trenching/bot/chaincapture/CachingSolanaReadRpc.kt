package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/**
 * Serves `getTransaction` from a [SharedTransactionCache] before asking [delegate], and keeps what the provider returns.
 * A hit sends no request and charges no budget. Only a response that [CapturedTransactionCheck] accepts as a valid
 * artifact for its signature is stored: a null transaction, a failure or a malformed object is outcome-dependent or
 * possibly transient, and caching it would serve the same bad answer to every retry.
 */
class CachingSolanaReadRpc(private val delegate: SolanaReadRpc, private val cache: SharedTransactionCache) : SolanaReadRpc {
    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        if (method != ChainCaptureFormat.GET_TRANSACTION) return delegate.call(method, params, budget)
        val key = cache.keyFor(params) ?: return delegate.call(method, params, budget)
        cache.read(key)?.let {
            budget.counters.cacheHit()
            return it
        }
        return delegate.call(method, params, budget).also { if (CapturedTransactionCheck.isValidFor(it, key.signature)) cache.write(key, it) }
    }
}
