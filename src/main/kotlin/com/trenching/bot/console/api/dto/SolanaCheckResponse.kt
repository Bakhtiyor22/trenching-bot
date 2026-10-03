package com.trenching.bot.console.api.dto

import java.time.Instant

data class SolanaCheckResponse(
    val outcome: String,
    val cached: Boolean,
    val checkedAt: Instant,
    val latencyMs: Long?,
    val version: String?,
    val slot: Long?,
    val cluster: String?,
    val rpcErrorCode: Long?,
    val message: String?,
)
