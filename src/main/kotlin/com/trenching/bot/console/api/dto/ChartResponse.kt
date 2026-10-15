package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** Prices are SOL per token, mcaps USDT, all as exact decimal strings; [markers] and [paperFills] are never mixed. */
data class ChartResponse(
    val bucketSeconds: Int,
    val range: ChartRangeResponse,
    val trackedWallet: String?,
    val valuationBasis: ValuationBasisResponse,
    val candles: List<CandleResponse>,
    val markers: MarkersResponse,
    val paperFills: List<PaperFillResponse>,
)

data class ChartRangeResponse(val from: Instant?, val to: Instant?)

data class ValuationBasisResponse(
    val label: String,
    val supply: String,
    val supplySource: String,
    val quoteCurrency: String,
    val rateSource: String?,
    val rateGranularity: String,
    val available: Boolean,
    val unavailableReason: String?,
)

data class OhlcResponse(val open: String, val high: String, val low: String, val close: String)

data class CandleResponse(
    val bucketStart: Instant,
    val observationCount: Int,
    val price: OhlcResponse,
    val mcap: OhlcResponse?,
    val mcapMissingPoints: Int,
)

data class MarkersResponse(
    val available: Boolean,
    val unavailableReason: String?,
    val mode: String,
    val total: Int,
    val items: List<ObservedMarkerResponse>,
    val nextCursor: String?,
    val clusters: List<MarkerClusterResponse>,
)

/** [tracked] is serialized as `isTrackedWallet`. */
data class ObservedMarkerResponse(
    val kind: String,
    val observationId: String,
    val trader: String,
    @get:JsonProperty("isTrackedWallet") val tracked: Boolean,
    val side: String,
    val solLamports: String,
    val tokenRaw: String,
    val price: String,
    val mcap: String?,
    val slot: Long,
    val time: Instant,
    val signature: String?,
    val source: String,
)

data class MarkerClusterResponse(
    val bucketStart: Instant,
    val count: Int,
    val buyCount: Int,
    val sellCount: Int,
    val firstSlot: Long,
    val lastSlot: Long,
    val firstTime: Instant,
    val lastTime: Instant,
)

data class PaperFillResponse(
    val kind: String,
    val phase: String,
    val side: String,
    val intentId: String,
    val slot: Long,
    val time: Instant,
    val price: String,
    val mcap: String?,
    val quantity: String,
    val reason: String?,
)
