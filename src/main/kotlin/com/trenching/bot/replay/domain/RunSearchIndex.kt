package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import java.math.BigInteger
import java.time.Instant

/** The tracked wallet's trade fields the search index reads, whatever shape the dataset was stored in. */
data class IndexedTrade(
    val wallet: String,
    val slot: Long,
    val signature: String,
    val instructionIndex: Int,
    val blockTime: Instant?,
    val baseMint: MintAddress,
    val baseDelta: BigInteger,
    val successful: Boolean,
    val supportedRoute: Boolean,
)

/**
 * What a run is searchable by. The datasets carry no separate reference buy, so the anchor is the tracked wallet's earliest
 * successful buy on a supported route, in canonical order (slot, signature, instruction index). [mints] are the dataset's
 * pair base mints and the anchor buy's mint.
 */
data class RunSearchIndex(val mints: Set<MintAddress>, val anchorBlockTime: Instant?) {
    companion object {
        fun of(trackedWallet: String, trades: List<IndexedTrade>, pairBases: Collection<MintAddress>): RunSearchIndex {
            val anchor = trades
                .filter { it.wallet == trackedWallet && it.successful && it.supportedRoute && it.baseDelta.signum() > 0 }
                .minWithOrNull(compareBy({ it.slot }, { it.signature }, { it.instructionIndex }))
            return RunSearchIndex(pairBases.toSet() + listOfNotNull(anchor?.baseMint), anchor?.blockTime)
        }

        fun of(dataset: ReplayDataset): RunSearchIndex = of(
            dataset.trackedWallet.value,
            dataset.transactions.map {
                IndexedTrade(it.wallet.value, it.slot.value, it.signature.value, it.instructionIndex, it.blockTime, it.pair.base,
                    it.baseDelta, it.successful, it.supportedRoute)
            },
            dataset.transactions.map { it.pair.base } + dataset.observations.map { it.pair.base },
        )
    }
}
