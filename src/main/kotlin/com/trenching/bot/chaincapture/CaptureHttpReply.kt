package com.trenching.bot.chaincapture

import java.time.Duration
import tools.jackson.databind.JsonNode

internal sealed interface CaptureHttpReply {
    data class Success(val result: JsonNode) : CaptureHttpReply
    /** [rateLimited] marks the provider asking callers to slow down, which the whole endpoint's limiter honours. */
    data class Failed(val category: String, val retryable: Boolean, val retryAfter: Duration? = null, val rateLimited: Boolean = false) : CaptureHttpReply
}
