package com.trenching.bot.chaincapture

import java.time.Duration
import tools.jackson.databind.JsonNode

internal sealed interface CaptureHttpReply {
    data class Success(val result: JsonNode) : CaptureHttpReply
    data class Failed(val category: String, val retryable: Boolean, val retryAfter: Duration? = null) : CaptureHttpReply
}
