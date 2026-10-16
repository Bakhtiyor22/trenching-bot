package com.trenching.bot.chaincapture

import java.time.Duration
import tools.jackson.databind.JsonNode

internal sealed interface CaptureHttpReply {
    data class Success(val result: JsonNode) : CaptureHttpReply
    /**
     * [rateLimited] marks the provider asking callers to slow down, which the whole endpoint's limiter honours. [cause] is the
     * innermost exception's class name of a transport failure, for logs only; null for an HTTP or RPC failure.
     */
    data class Failed(
        val category: String,
        val retryable: Boolean,
        val retryAfter: Duration? = null,
        val rateLimited: Boolean = false,
        val cause: String? = null,
    ) : CaptureHttpReply
}
