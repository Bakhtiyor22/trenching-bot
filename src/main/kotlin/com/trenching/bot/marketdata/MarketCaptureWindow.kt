package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature

/**
 * Inclusive slot range of a base mint's bonding-curve trades against wrapped SOL, planned around [untilSignature], the
 * wallet's first buy of the mint, which anchors and identifies the window. Discovery lists the mint's signatures
 * newest-first down to [fromSlot]; a legacy window (no lead) also never lists older than [untilSignature].
 */
@ConsistentCopyVisibility
data class MarketCaptureWindow private constructor(
    val baseMint: MintAddress,
    val fromSlot: Slot,
    val toSlot: Slot,
    val untilSignature: TransactionSignature,
) {
    val pair: MarketPair get() = MarketPair.of(baseMint, MintAddress.WRAPPED_SOL)

    fun contains(mint: MintAddress, slot: Slot): Boolean = mint == baseMint && slot >= fromSlot && slot <= toSlot

    /**
     * The slot of the first buy this window was planned for under [parameters], the inverse of [forFirstBuy]. Nothing is
     * decided before it. For a legacy window it is `fromSlot - 1`, which is `-1` only for a window no buy could produce.
     */
    fun firstBuySlot(parameters: MarketCaptureWindowParameters): Long = Math.addExact(fromSlot.value, parameters.leadSlots)

    companion object {
        /**
         * The window of a wallet's first buy of [mint] at [buySlot]: `[buySlot - leadSlots, buySlot + horizonSlots]`, anchored
         * at [buySignature]. With [MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS] it is the legacy `[buySlot + 1, ...]`.
         * A lead reaching before slot 0 (see [leadFits]) is refused with [MarketCaptureReasons.MARKET_WINDOW_INVALID].
         */
        fun forFirstBuy(
            mint: MintAddress,
            buySlot: Slot,
            buySignature: TransactionSignature,
            parameters: MarketCaptureWindowParameters,
        ): MarketCaptureWindow {
            if (!leadFits(buySlot, parameters)) throw CaptureValidationException(MarketCaptureReasons.MARKET_WINDOW_INVALID)
            val fromSlot = Math.subtractExact(buySlot.value, parameters.leadSlots)
            return of(mint, Slot.of(fromSlot), buySlot.plus(parameters.horizonSlots), buySignature)
        }

        /** Whether a first buy at [buySlot] leaves room for the lead, so its window would not start before slot 0. */
        fun leadFits(buySlot: Slot, parameters: MarketCaptureWindowParameters): Boolean = buySlot.value >= parameters.leadSlots

        fun of(baseMint: MintAddress, fromSlot: Slot, toSlot: Slot, untilSignature: TransactionSignature): MarketCaptureWindow {
            require(baseMint != MintAddress.WRAPPED_SOL) { "Window base mint must differ from the wrapped SOL quote" }
            require(fromSlot <= toSlot) { "Window must not end before it starts fromSlot=${fromSlot.value} toSlot=${toSlot.value}" }
            return MarketCaptureWindow(baseMint, fromSlot, toSlot, untilSignature)
        }
    }
}

/**
 * The simulation parameters a capture's windows were derived from: each window covers [horizonSlots] after the
 * wallet's first buy of its mint, from [leadSlots] before that buy (see [MarketCaptureWindow.forFirstBuy]). A run with a
 * longer horizon needs a new capture. The two-argument form means [LEGACY_LEAD_SLOTS]: a capture from before the lead
 * (market manifest version 2 or 3), whose windows start the slot after the buy and list only newer than it, bounded by
 * `until`. A new capture must pass [DEFAULT_LEAD_SLOTS], as `ReplayMarketWindowPlanner` does.
 */
data class MarketCaptureWindowParameters(
    val horizonSlots: Long,
    val maxAgeSlots: Long,
    val leadSlots: Long = LEGACY_LEAD_SLOTS,
) {
    companion object {
        /** Windows start at `S + 1`, after the buy's slot, and list only newer than the buy: manifest versions 2 and 3. */
        const val LEGACY_LEAD_SLOTS = -1L

        /** About 10 s at the measured slot pace: new captures record the market from this many slots before the first buy. */
        const val DEFAULT_LEAD_SLOTS = 50L
    }
}
