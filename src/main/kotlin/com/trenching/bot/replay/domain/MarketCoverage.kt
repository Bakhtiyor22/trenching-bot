package com.trenching.bot.replay.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import java.time.Instant

/**
 * The slots of one market window whose trades a schema v5 or v6 dataset holds completely: every trade of [pair] in
 * `[fromSlot, throughSlot]`. A window with a lead decided at its first buy S covers `[S - lead, S]`; one recorded before the
 * lead starts at `S + 1` and then observed nothing, so `throughSlot = fromSlot - 1` is allowed. Schema v6 adds [throughTime],
 * the block time of the nearest produced block at or below [throughSlot], so never later than the moment the coverage reaches;
 * it is null in v5 and for a window whose capture recorded none.
 */
@ConsistentCopyVisibility
data class MarketCoverage private constructor(val pair: MarketPair, val fromSlot: Slot, val throughSlot: Slot, val throughTime: Instant?) {
    companion object {
        fun of(pair: MarketPair, fromSlot: Slot, throughSlot: Slot, throughTime: Instant? = null): MarketCoverage {
            require(throughSlot.value >= fromSlot.value - 1) {
                "Coverage must not end before the slot preceding its start fromSlot=${fromSlot.value} throughSlot=${throughSlot.value}"
            }
            return MarketCoverage(pair, fromSlot, throughSlot, throughTime)
        }

        /** The written and hashed order: by base mint, then quote mint. */
        fun canonical(coverage: List<MarketCoverage>): List<MarketCoverage> =
            coverage.sortedWith(compareBy({ it.pair.base.value }, { it.pair.quote.value }))
    }
}
