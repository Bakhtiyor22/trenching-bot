package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletAction

/**
 * One wallet action's position, in the deterministic order discovery processes and the watermark compares: slot, then
 * signature, instruction and inner index. A top-level event has inner index [TOP_LEVEL_INNER_INDEX].
 */
data class DiscoveryCursor(val slot: Long, val signature: String, val instructionIndex: Int, val innerIndex: Int) : Comparable<DiscoveryCursor> {
    override fun compareTo(other: DiscoveryCursor): Int = ORDER.compare(this, other)

    companion object {
        const val TOP_LEVEL_INNER_INDEX = -1

        /** Before every action: the provisional watermark of a wallet whose first catch-up has found no action yet. */
        val ORIGIN = DiscoveryCursor(-1, "", -1, TOP_LEVEL_INNER_INDEX)

        private val ORDER: Comparator<DiscoveryCursor> =
            compareBy<DiscoveryCursor> { it.slot }.thenBy { it.signature }.thenBy { it.instructionIndex }.thenBy { it.innerIndex }

        fun of(action: WalletAction): DiscoveryCursor =
            DiscoveryCursor(action.slot.value, action.signature.value, action.instructionIndex, action.innerIndex ?: TOP_LEVEL_INNER_INDEX)
    }
}
