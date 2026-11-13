package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * [retryAfterSeconds] is sent only with a rate-limited reply whose provider named a retry time, and [errorId] only with an
 * `INTERNAL_ERROR`, where it names the backend log line that holds the failure.
 */
data class ConsoleErrorResponse(
    val code: String,
    val message: String,
    val fields: Map<String, String>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val retryAfterSeconds: Long?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val errorId: String?,
)
