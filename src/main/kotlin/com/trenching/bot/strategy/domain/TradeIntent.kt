package com.trenching.bot.strategy.domain

import com.trenching.bot.common.*
import java.math.BigDecimal

/**
 * The dip-entry decision for one mint, made from the tracked wallet's first buy of it at [creationSlot].
 * [referencePrice] is that buy's price (P0) and [dipPrice] the level a trade must reach. [trigger] is set exactly when
 * [watch] is [DipWatchOutcome.TRIGGERED]; [targetSlot] is the trigger's slot, or the first watched slot without one.
 */
data class TradeIntent(
    val id: String,
    val runId: String,
    val sourceEventId: String,
    val pair: MarketPair,
    val strategyName: String,
    val strategyVersion: String,
    val configurationHash: String,
    val entryOffset: Int,
    val creationSlot: Slot,
    val targetSlot: Slot,
    val notional: BigDecimal,
    val referencePrice: BigDecimal,
    val dipPrice: BigDecimal,
    val horizonEndSlot: Slot,
    val watch: DipWatchOutcome,
    val trigger: DipTrigger?,
)

/** The trade that met the dip, with the market cap and the SOL/USDT rate it was judged at. */
data class DipTrigger(
    val observationId: String,
    val slot: Slot,
    val price: BigDecimal,
    val marketCapUsd: BigDecimal,
    val rate: BigDecimal,
)

enum class DipWatchOutcome {
    TRIGGERED,
    NO_DIP,
    // Engine dip-paper-v2 only: it checked the minimum market cap at the dip. Kept so its stored runs still read.
    DIP_BELOW_MIN_MARKET_CAP,
    REFERENCE_BELOW_MIN_MARKET_CAP,
    MIGRATED_BEFORE_ENTRY,
    UNSUPPORTED_SUPPLY,
}

/** The intents of a run, plus the source events of buys that started no cycle because an earlier buy of the mint did. */
data class DipPlan(val intents: List<TradeIntent>, val laterBuys: List<String>)
