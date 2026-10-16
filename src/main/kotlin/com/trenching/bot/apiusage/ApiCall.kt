package com.trenching.bot.apiusage

/**
 * One HTTP request sent to [provider], or one answer served from a cache ([ApiCallOutcome.CACHE_HIT]). [operation] is the
 * JSON-RPC method or the HTTP path, never a URL, query string or key; [responseBytes] counts the response body bytes read.
 */
data class ApiCall(
    val provider: ApiProvider,
    val operation: String,
    val context: ApiUsageContext,
    val outcome: ApiCallOutcome,
    val responseBytes: Long,
) {
    init {
        require(responseBytes >= 0) { "responseBytes must not be negative" }
    }
}
