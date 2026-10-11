package com.trenching.bot.walletactivity.domain

import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.TokenAccountGap
import com.trenching.bot.walletactivity.WalletHistoryDiscoveryStop

/**
 * Step 1 of the wallet-token capture plan: the earliest decoded buy B of the target mint through the token account, accepted
 * only when the listing was exhausted, B is the only decoded buy in its slot, B credited the token account, and no record in
 * B's slot or earlier could be an acquisition the decoder did not recognise. Within-slot order is not relied on, so every
 * other record in B's slot counts as earlier.
 */
internal object TokenAccountFirstBuyRule {
    private val CANONICAL = compareBy<TokenAccountRecord>({ it.slot }, { it.signature.value })

    fun evaluate(discoveryStop: WalletHistoryDiscoveryStop?, records: List<TokenAccountRecord>): TokenAccountFirstBuy {
        when (discoveryStop) {
            null, WalletHistoryDiscoveryStop.LIMIT_REACHED, WalletHistoryDiscoveryStop.SCAN_BOUND ->
                return TokenAccountFirstBuy.NotEstablished(TokenAccountGap.HISTORY_NOT_EXHAUSTED, emptyList())
            WalletHistoryDiscoveryStop.HISTORY_END -> Unit
        }
        val ordered = records.sortedWith(CANONICAL)
        val first = ordered.firstNotNullOfOrNull { record -> (record.kind as? TokenAccountRecordKind.TargetBuy)?.let { record to it } }
            ?: return TokenAccountFirstBuy.NoBuy
        val (record, buy) = first
        val sameSlotBuys = ordered.filter { it.slot == record.slot && it.signature != record.signature && it.kind is TokenAccountRecordKind.TargetBuy }
        if (sameSlotBuys.isNotEmpty()) return TokenAccountFirstBuy.NotEstablished(TokenAccountGap.SAME_SLOT_BUY, sameSlotBuys.map { it.signature })
        if (!buy.creditsTokenAccount) return TokenAccountFirstBuy.NotEstablished(TokenAccountGap.NON_ATA_CREDIT, listOf(record.signature))
        val earlier = ordered.filter { it.slot <= record.slot && it.signature != record.signature }
        val undecoded = earlier.filter { it.kind is TokenAccountRecordKind.Undecoded && it.kind.walletBalance != TargetBalanceChange.NOT_INCREASED }
        val unknown = earlier.filter { it.kind == TokenAccountRecordKind.Unknown }
        return when {
            undecoded.isNotEmpty() -> TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNDECODED_ACQUISITION, undecoded.map { it.signature })
            unknown.isNotEmpty() -> TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNKNOWN_RECORD, unknown.map { it.signature })
            else -> TokenAccountFirstBuy.Found(record.signature, record.slot, buy.instructionIndex)
        }
    }
}
