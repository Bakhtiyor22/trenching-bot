package com.trenching.bot.replay.infrastructure.fixture

import com.trenching.bot.common.MarketPair
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.domain.DatasetTrades
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.walletactivity.domain.WalletTransaction
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Writes the canonical form that [ReplayDatasetLoader] reads: deduplicated, ordered, exact decimals. A v3 dataset is written
 * exactly as before schema v4 existed, without trades; a v4 dataset adds each observation's `trade` after its `curve`, and a v5
 * dataset its `coverage` after the rates.
 */
class ReplayDatasetWriter(private val mapper: ObjectMapper) {
    fun write(dataset: ReplayDataset): String {
        val root = mapper.createObjectNode()
        root.put("schemaVersion", dataset.schemaVersion)
        root.put("checksum", DatasetFingerprint.checksum(dataset))
        root.put("trackedWallet", dataset.trackedWallet.value)
        val transactions = root.putArray("transactions")
        OrderedWalletTransactions.canonical(dataset.transactions).forEach { transactions.add(transaction(it)) }
        val observations = root.putArray("observations")
        val recordsTrades = ReplayDataset.recordsTrades(dataset.schemaVersion)
        OrderedMarketData.canonical(dataset.observations).forEach { observations.add(observation(it, recordsTrades)) }
        val rates = root.putArray("referenceRates")
        OrderedReferenceRates.canonical(dataset.referenceRates).forEach { rate ->
            rates.add(mapper.createObjectNode().apply {
                put("minute", rate.minute.toString())
                put("rate", rate.rate.toPlainString())
                put("source", rate.source)
            })
        }
        if (ReplayDataset.recordsCoverage(dataset.schemaVersion)) {
            val coverage = root.putArray("coverage")
            MarketCoverage.canonical(dataset.coverage).forEach { entry ->
                coverage.add(mapper.createObjectNode().apply {
                    pair(this, entry.pair)
                    put("fromSlot", entry.fromSlot.value)
                    put("throughSlot", entry.throughSlot.value)
                })
            }
        }
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

    private fun observation(observation: MarketObservation, recordsTrades: Boolean): ObjectNode = mapper.createObjectNode().apply {
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
        put("curveComplete", observation.curveComplete)
        putObject("curve").apply {
            put(ReplayCurveFields.VIRTUAL_SOL_RESERVES, observation.curve.reserves.virtualSol.toString())
            put(ReplayCurveFields.VIRTUAL_TOKEN_RESERVES, observation.curve.reserves.virtualToken.toString())
            put(ReplayCurveFields.REAL_SOL_RESERVES, observation.curve.reserves.realSol.toString())
            put(ReplayCurveFields.REAL_TOKEN_RESERVES, observation.curve.reserves.realToken.toString())
            put(ReplayCurveFields.FEE_BPS, observation.curve.feeBps.toString())
            put(ReplayCurveFields.CREATOR_FEE_BPS, observation.curve.creatorFeeBps.toString())
        }
        if (recordsTrades) {
            val trade = DatasetTrades.required(observation)
            putObject(ReplayTradeFields.TRADE).apply {
                put(ReplayTradeFields.TRADER, trade.trader)
                put(ReplayTradeFields.SIDE, trade.side.name)
                put(ReplayTradeFields.SOL_LAMPORTS, trade.solLamports.toString())
                put(ReplayTradeFields.TOKEN_RAW, trade.tokenRaw.toString())
            }
        }
    }

    private fun pair(node: ObjectNode, pair: MarketPair) {
        node.put("baseMint", pair.base.value)
        node.put("quoteMint", pair.quote.value)
    }
}
