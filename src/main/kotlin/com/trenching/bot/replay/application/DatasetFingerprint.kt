package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.DatasetTrades
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.OrderedReferenceRates

/** The v3 observation parts are unchanged; v4 appends the four trade values to each observation's parts. */
object DatasetFingerprint {
    fun checksum(dataset: ReplayDataset): String {
        val transactions = OrderedWalletTransactions.canonical(dataset.transactions)
        val observations = OrderedMarketData.canonical(dataset.observations)
        val parts = mutableListOf(dataset.schemaVersion.toString(), dataset.trackedWallet.value)
        transactions.forEach { transaction ->
            parts.addAll(listOf("transaction", transaction.wallet.value, transaction.signature.value,
                transaction.instructionIndex.toString(), transaction.slot.value.toString(), transaction.blockTime?.toString() ?: "",
                transaction.pair.base.value, transaction.pair.quote.value, transaction.baseDelta.toString(),
                transaction.quoteDelta.toString(), transaction.baseDecimals.toString(), transaction.quoteDecimals.toString(),
                transaction.successful.toString(), transaction.supportedRoute.toString(), transaction.signer.toString(),
                transaction.source, transaction.commitment.name))
        }
        observations.forEach { observation ->
            parts.addAll(listOf("observation", observation.id, observation.pair.base.value, observation.pair.quote.value,
                observation.slot.value.toString(), observation.priceSlot.value.toString(), observation.observedTime.toString(),
                observation.price.stripTrailingZeros().toPlainString(), observation.liquidityQuote?.stripTrailingZeros()?.toPlainString() ?: "",
                observation.source, observation.sourceEventId, observation.commitment.name, observation.quality.name,
                observation.curveComplete.toString(), observation.curve.reserves.virtualSol.toString(),
                observation.curve.reserves.virtualToken.toString(), observation.curve.reserves.realSol.toString(),
                observation.curve.reserves.realToken.toString(), observation.curve.feeBps.toString(),
                observation.curve.creatorFeeBps.toString()))
            if (ReplayDataset.recordsTrades(dataset.schemaVersion)) {
                val trade = DatasetTrades.required(observation)
                parts.addAll(listOf(trade.trader, trade.side.name, trade.solLamports.toString(), trade.tokenRaw.toString()))
            }
        }
        OrderedReferenceRates.canonical(dataset.referenceRates).forEach { rate ->
            parts.addAll(listOf("rate", rate.minute.toString(), rate.rate.stripTrailingZeros().toPlainString(), rate.source))
        }
        return StableHash.of(parts)
    }
}
