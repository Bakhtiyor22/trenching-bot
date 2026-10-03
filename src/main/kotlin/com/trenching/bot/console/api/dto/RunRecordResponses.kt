package com.trenching.bot.console.api.dto

import java.time.Instant

/** A tracked-wallet transaction; [outcome] is `ACCEPTED` (with [side]) or `IGNORED` (with [ignoredReason]). */
data class RunEventResponse(
    val eventId: String,
    val signature: String,
    val instructionIndex: Int,
    val slot: Long,
    val blockTime: Instant?,
    val wallet: String,
    val pair: PairResponse,
    val baseDelta: String,
    val quoteDelta: String,
    val baseDecimals: Int,
    val quoteDecimals: Int,
    val successful: Boolean,
    val supportedRoute: Boolean,
    val signer: Boolean,
    val source: String,
    val commitment: String,
    val outcome: String,
    val side: String?,
    val ignoredReason: String?,
)

data class OrderResponse(
    val orderId: String,
    val intentId: String,
    val entryOffset: Int,
    val targetSlot: Long,
    val status: String,
    val rejectionReason: String?,
    val fill: FillResponse?,
)

data class PositionResponse(
    val id: String,
    val intentId: String,
    val pair: PairResponse,
    val entryOffset: Int,
    val state: String,
    val fill: FillResponse,
    val close: CloseResponse?,
    val exitUnavailableReason: String?,
)
