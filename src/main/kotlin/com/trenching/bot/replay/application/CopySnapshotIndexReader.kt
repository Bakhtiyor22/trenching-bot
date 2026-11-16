package com.trenching.bot.replay.application

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.IndexedTrade
import com.trenching.bot.replay.domain.RunSearchIndex
import java.math.BigDecimal
import java.time.Instant
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Reads the search index of a copy run from its own stored dataset snapshot, which is the dataset serialized as the ledger wrote
 * it (value types as `{"value": ...}`), not the dataset file format. Throws [IllegalArgumentException] on a shape it cannot read.
 */
@Component
class CopySnapshotIndexReader(private val json: ObjectMapper) {
    fun read(snapshot: String): RunSearchIndex {
        val root = json.readTree(snapshot)
        val trades = items(root.path(TRANSACTIONS)).map { node ->
            IndexedTrade(
                wallet = value(node, "wallet"),
                slot = number(node.path("slot").path(VALUE), "slot").longValueExact(),
                signature = value(node, "signature"),
                instructionIndex = number(node.path("instructionIndex"), "instructionIndex").intValueExact(),
                blockTime = node.path("blockTime").takeIf { it.isString }?.let { Instant.parse(it.asString()) },
                baseMint = base(node),
                baseDelta = number(node.path("baseDelta"), "baseDelta").toBigInteger(),
                successful = node.path("successful").asBoolean(false),
                supportedRoute = node.path("supportedRoute").asBoolean(false),
            )
        }
        val pairBases = trades.map { it.baseMint } + items(root.path(OBSERVATIONS)).map(::base)
        return RunSearchIndex.of(value(root, "trackedWallet"), trades, pairBases)
    }

    private fun items(array: JsonNode): List<JsonNode> = array.iterator().asSequence().toList()

    private fun base(node: JsonNode): MintAddress = MintAddress.of(value(node.path("pair"), "base"))

    private fun value(node: JsonNode, field: String): String {
        val text = node.path(field).path(VALUE)
        require(text.isString) { "Dataset snapshot has no $field" }
        return text.asString()
    }

    private fun number(node: JsonNode, field: String): BigDecimal {
        require(node.isNumber) { "Dataset snapshot has no number $field" }
        return node.decimalValue()
    }

    private companion object {
        const val TRANSACTIONS = "transactions"
        const val OBSERVATIONS = "observations"
        const val VALUE = "value"
    }
}
