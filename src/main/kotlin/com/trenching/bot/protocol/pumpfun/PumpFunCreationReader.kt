package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.Base58
import tools.jackson.databind.JsonNode

/** Reads which mints a transaction's Pump.fun `create` and `create_v2` instructions initialise, top-level or inner. */
object PumpFunCreationReader {
    /** Instructions with unreadable data or without the mint account are skipped, so they never identify a creation. */
    fun createdMints(transaction: JsonNode): List<String> {
        val topLevel = transaction.path("transaction").path("message").path("instructions").toList()
        val inner = transaction.path("meta").path("innerInstructions").flatMap { it.path("instructions") }
        return (topLevel + inner).mapNotNull(::createdMint)
    }

    private fun createdMint(instruction: JsonNode): String? {
        val programId = instruction.path("programId")
        val data = instruction.path("data")
        if (!programId.isString || programId.asString() != PumpFunLayout.PROGRAM_ID) return null
        if (!data.isString || data.asString().length > PumpFunLayout.MAX_DATA_CHARACTERS) return null
        val bytes = Base58.decode(data.asString()) ?: return null
        if (PumpFunCoverage.CREATION_DISCRIMINATORS.none { bytes.startsWith(it) }) return null
        val mint = instruction.path("accounts").path(PumpFunLayout.CREATE_MINT_ACCOUNT_INDEX)
        return if (mint.isString) mint.asString() else null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
