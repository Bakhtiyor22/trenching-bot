package com.trenching.bot.replay.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot

/**
 * The slots of one market window whose trades a schema v5 dataset holds completely: every trade of [pair] in
 * `[fromSlot, throughSlot]`. A window with a lead decided at its first buy S covers `[S - lead, S]`; one recorded before the
 * lead starts at `S + 1` and then observed nothing, so `throughSlot = fromSlot - 1` is allowed.
 */
@ConsistentCopyVisibility
data class MarketCoverage private constructor(val pair: MarketPair, val fromSlot: Slot, val throughSlot: Slot) {
    companion object {
        fun of(pair: MarketPair, fromSlot: Slot, throughSlot: Slot): MarketCoverage {
            require(throughSlot.value >= fromSlot.value - 1) {
                "Coverage must not end before the slot preceding its start fromSlot=${fromSlot.value} throughSlot=${throughSlot.value}"
            }
            return MarketCoverage(pair, fromSlot, throughSlot)
        }

        /** The written and hashed order: by base mint, then quote mint. */
        fun canonical(coverage: List<MarketCoverage>): List<MarketCoverage> =
            coverage.sortedWith(compareBy({ it.pair.base.value }, { it.pair.quote.value }))
    }
}
