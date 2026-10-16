package com.trenching.bot.console.api.dto

import java.time.Instant

data class WalletActivityResponse(
    val wallet: String,
    val source: String,
    val actions: List<WalletActionResponse>,
    val unreadable: Int,
    val scanned: Int,
    val nextBefore: String?,
    val requestsUsed: Int,
    val cachedAt: Instant?,
)

/** [solLamports] and [tokenRaw] are exact integer strings. */
data class WalletActionResponse(
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int?,
    val slot: Long,
    val blockTime: Instant?,
    val side: String,
    val mint: String,
    val solLamports: String,
    val tokenRaw: String,
    val tokenDecimals: Int,
    val venue: String,
    val earlierActionInPage: Boolean,
    val overSignalThreshold: Boolean,
    val signalBasis: String,
    val capturedIn: List<CapturedInResponse>,
)

data class CapturedInResponse(val captureId: String, val displayName: String)
