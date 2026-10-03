package com.trenching.bot.console.solana.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode

/** [result] is the raw JSON-RPC result, kept as a tree so the probe can tell an absent field from a null one. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcResponse(
    val result: JsonNode? = null,
    val error: JsonRpcError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcError(
    val code: Long,
    val message: String = "",
)
