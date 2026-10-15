package com.trenching.bot.console.api.dto

import java.time.Instant

/**
 * [price] and [liquidityQuote] are exact decimal strings in the pair's quote units. [trade] is null for data recorded before
 * schema v4.
 */
data class ObservationResponse(
    val id: String,
    val pair: PairResponse,
    val slot: Long,
    val priceSlot: Long,
    val observedTime: Instant,
    val price: String,
    val liquidityQuote: String?,
    val source: String,
    val sourceEventId: String,
    val commitment: String,
    val quality: String,
    val trade: ObservedTradeResponse?,
)

/** [solLamports] and [tokenRaw] are raw integer strings. */
data class ObservedTradeResponse(val trader: String, val side: String, val solLamports: String, val tokenRaw: String)
