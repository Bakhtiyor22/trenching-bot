package com.trenching.bot.console.domain

import com.trenching.bot.marketdata.domain.TradeSide
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/**
 * One bounded chart of a pair: price and mcap candles, observed trade markers and, for a run, its paper fills. [range] is the
 * window the buckets were sized for: the requested bounds, else the first and last observation (null for no data).
 */
data class ChartView(
    val bucketSeconds: Int,
    val range: ChartRange,
    val trackedWallet: String?,
    val valuationBasis: ValuationBasisView,
    val candles: List<CandleView>,
    val markers: MarkersView,
    val paperFills: List<PaperFillView>,
)

data class ChartRange(val from: Instant?, val to: Instant?)

/** How the mcap was derived, so a reader can tell what the number means and why it is missing. */
data class ValuationBasisView(
    val label: String,
    val supply: String,
    val supplySource: String,
    val quoteCurrency: String,
    val rateSource: String?,
    val rateGranularity: String,
    val available: Boolean,
    val unavailableReason: ValuationBasisReason?,
)

enum class ValuationBasisReason { UNSUPPORTED_TOKEN, NOT_SOL_QUOTED, RATES_UNAVAILABLE }

data class Ohlc(val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal)

/**
 * A bucket with data. [price] is in SOL per token; [mcap] in USDT over the points that could be valued (null when none
 * could); [mcapMissingPoints] counts the points whose minute has no recorded rate.
 */
data class CandleView(
    val bucketStart: Instant,
    val observationCount: Int,
    val price: Ohlc,
    val mcap: Ohlc?,
    val mcapMissingPoints: Int,
)

enum class MarkerMode { ITEMS, CLUSTERED }

enum class MarkersUnavailableReason { NOT_RECORDED_BEFORE_SCHEMA_V4 }

enum class MarkerKind { OBSERVED, PAPER }

/**
 * Observed trades. In [MarkerMode.ITEMS] [items] holds every matching trade. In [MarkerMode.CLUSTERED] it holds the tracked
 * wallet's trades and [clusters] the rest, per candle bucket, so `items + clusters == total` on a single page; [nextCursor]
 * continues the tracked wallet's trades, whose later pages carry no clusters.
 */
data class MarkersView(
    val available: Boolean,
    val unavailableReason: MarkersUnavailableReason?,
    val mode: MarkerMode,
    val total: Int,
    val items: List<ObservedMarkerView>,
    val nextCursor: String?,
    val clusters: List<MarkerClusterView>,
)

/** [price] is the observation's; [mcap] its valuation, null when unavailable. */
data class ObservedMarkerView(
    val kind: MarkerKind,
    val observationId: String,
    val trader: String,
    val tracked: Boolean,
    val side: TradeSide,
    val solLamports: BigInteger,
    val tokenRaw: BigInteger,
    val price: BigDecimal,
    val mcap: BigDecimal?,
    val slot: Long,
    val time: Instant,
    val signature: String?,
    val source: String,
)

data class MarkerClusterView(
    val bucketStart: Instant,
    val count: Int,
    val buyCount: Int,
    val sellCount: Int,
    val firstSlot: Long,
    val lastSlot: Long,
    val firstTime: Instant,
    val lastTime: Instant,
)

enum class PaperPhase { ENTRY, EXIT }

/** A simulated fill, never an observed trade. [price] is the execution price; [mcap] values the observation the fill used. */
data class PaperFillView(
    val kind: MarkerKind,
    val phase: PaperPhase,
    val side: TradeSide,
    val intentId: String,
    val slot: Long,
    val time: Instant,
    val price: BigDecimal,
    val mcap: BigDecimal?,
    val quantity: BigDecimal,
    val reason: String?,
)
