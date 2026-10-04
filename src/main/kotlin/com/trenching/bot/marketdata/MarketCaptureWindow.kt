package com.trenching.bot.marketdata

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature

/**
 * Inclusive slot range of a base mint's bonding-curve trades against wrapped SOL. Discovery lists the mint's
 * signatures newest-first and never goes older than [untilSignature], the source trade the window follows.
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

    companion object {
        fun of(baseMint: MintAddress, fromSlot: Slot, toSlot: Slot, untilSignature: TransactionSignature): MarketCaptureWindow {
            require(baseMint != MintAddress.WRAPPED_SOL) { "Window base mint must differ from the wrapped SOL quote" }
            require(fromSlot <= toSlot) { "Window must not end before it starts fromSlot=${fromSlot.value} toSlot=${toSlot.value}" }
            return MarketCaptureWindow(baseMint, fromSlot, toSlot, untilSignature)
        }
    }
}

/** The simulation parameters a capture's windows were derived from; other parameters may need a new capture. */
data class MarketCaptureWindowParameters(
    val firstEntryOffset: Long,
    val lastEntryOffset: Long,
    val maxWaitSlots: Long,
    val maxAgeSlots: Long,
    val holdingSlots: Long,
)
