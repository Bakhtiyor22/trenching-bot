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
    /** Whether the trade left its bonding curve complete: from this trade on, the token has migrated off the curve. */
    val curveComplete: Boolean,
    val curve: CurveState,
    /** The decoded trade behind the observation; null for data recorded before trades were kept, never inferred. */
    val trade: ObservedTrade? = null,
)

enum class ObservationQuality { LAST_TRADE, MIDPOINT, INVALID }
