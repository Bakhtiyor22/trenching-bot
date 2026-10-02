package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.*
import java.math.BigDecimal
import java.time.Instant

data class MarketObservation(
    val id: String,
    val pair: MarketPair,
    val slot: Slot,
    val priceSlot: Slot,
    val observedTime: Instant,
    val price: BigDecimal,
    val liquidityQuote: BigDecimal?,
    val source: String,
    val sourceEventId: String,
    val commitment: Commitment,
    val quality: ObservationQuality,
)

enum class ObservationQuality { LAST_TRADE, MIDPOINT, INVALID }
