package com.trenching.bot.console.api.dto

import java.time.Instant

data class SolanaAddressCheckResponse(
    val address: String,
    val outcome: String,
    val cached: Boolean,
    val checkedAt: Instant,
    val signature: String?,
    val slot: Long?,
    val transactionVersion: String?,
    val presentFields: List<String>,
    val missingFields: List<String>,
    val signaturesExamined: Int?,
    val rpcErrorCode: Long?,
    val message: String?,
)
