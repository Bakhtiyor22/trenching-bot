package com.trenching.bot.console.api.dto

import java.time.Instant

/**
 * `GET /api/wallets/{address}/activity`. [nextBefore] is the `before` of the next older page, null once the listing ended;
 * [requestsUsed] counts provider requests sent for this page; [cachedAt] is set only when served from the 60 s cache.
 */
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

/** [solLamports] (the curve payment, fees excluded) and [tokenRaw] are exact integer strings. */
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
    val capturedIn: List<WalletActionCaptureResponse>,
)

data class WalletActionCaptureResponse(val captureId: String, val displayName: String)
