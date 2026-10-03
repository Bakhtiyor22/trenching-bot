package com.trenching.bot.console.api.dto

data class SolanaStatusResponse(
    val configured: Boolean,
    val host: String?,
    val cluster: String?,
    val lastCheck: SolanaCheckResponse?,
)
