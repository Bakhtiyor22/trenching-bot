package com.trenching.bot.replay.infrastructure.fixture

import com.trenching.bot.common.MarketPair
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.walletactivity.domain.WalletTransaction
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Writes the canonical v1 form that [ReplayDatasetLoader] reads: deduplicated, ordered, exact decimals. */
class ReplayDatasetWriter(private val mapper: ObjectMapper) {
    fun write(dataset: ReplayDataset): String {
        val root = mapper.createObjectNode()
        root.put("schemaVersion", dataset.schemaVersion)
        root.put("checksum", DatasetFingerprint.checksum(dataset))
        root.put("trackedWallet", dataset.trackedWallet.value)
        val transactions = root.putArray("transactions")
        OrderedWalletTransactions.canonical(dataset.transactions).forEach { transactions.add(transaction(it)) }
        val observations = root.putArray("observations")
        OrderedMarketData.canonical(dataset.observations).forEach { observations.add(observation(it)) }
        return mapper.writeValueAsString(root)
    }

    private fun transaction(transaction: WalletTransaction): ObjectNode = mapper.createObjectNode().apply {
        put("wallet", transaction.wallet.value)
        put("signature", transaction.signature.value)
        put("instructionIndex", transaction.instructionIndex)
        put("slot", transaction.slot.value)
        transaction.blockTime?.let { put("blockTime", it.toString()) }
        pair(this, transaction.pair)
        put("baseDelta", transaction.baseDelta.toString())
        put("quoteDelta", transaction.quoteDelta.toString())
        put("baseDecimals", transaction.baseDecimals)
        put("quoteDecimals", transaction.quoteDecimals)
        put("successful", transaction.successful)
        put("supportedRoute", transaction.supportedRoute)
        put("signer", transaction.signer)
        put("source", transaction.source)
        put("commitment", transaction.commitment.name)
    }

    private fun observation(observation: MarketObservation): ObjectNode = mapper.createObjectNode().apply {
        put("id", observation.id)
        pair(this, observation.pair)
        put("slot", observation.slot.value)
        put("priceSlot", observation.priceSlot.value)
        put("observedTime", observation.observedTime.toString())
        put("price", observation.price.toPlainString())
        observation.liquidityQuote?.let { put("liquidityQuote", it.toPlainString()) }
        put("source", observation.source)
        put("sourceEventId", observation.sourceEventId)
        put("commitment", observation.commitment.name)
        put("quality", observation.quality.name)
    }

    private fun pair(node: ObjectNode, pair: MarketPair) {
        node.put("baseMint", pair.base.value)
        node.put("quoteMint", pair.quote.value)
    }
}
