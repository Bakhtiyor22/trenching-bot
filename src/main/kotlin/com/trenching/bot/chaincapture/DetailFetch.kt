package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/** What one detail request returned: a transaction already classified as [O], or the provider's answer that its version is unsupported. */
sealed interface DetailFetch<out O> {
    data class Fetched<O>(val raw: JsonNode, val outcome: O) : DetailFetch<O>

    data object UnsupportedVersion : DetailFetch<Nothing>

    companion object {
        /** Requests the transaction of [signature] and classifies it; any other provider failure propagates for the caller to record. */
        fun <O> request(rpc: SolanaReadRpc, signature: String, budget: CaptureBudget, classify: (JsonNode) -> O): DetailFetch<O> = try {
            val raw = ChainCaptureReads.transaction(rpc, signature, budget)
            Fetched(raw, classify(raw))
        } catch (exception: CaptureProviderException) {
            if (exception.category == CaptureCategory.UNSUPPORTED_VERSION) UnsupportedVersion else throw exception
        }
    }
}
