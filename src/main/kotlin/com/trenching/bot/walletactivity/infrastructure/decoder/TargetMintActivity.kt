package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.MintAddress
import com.trenching.bot.protocol.pumpfun.PumpFunCreationReader
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import java.time.Instant
import tools.jackson.databind.JsonNode

/** What a captured transaction shows about the target mint, for the actual-first-buy verification. */
internal object TargetMintActivity {
    fun mentions(transaction: JsonNode, mint: MintAddress): Boolean =
        transaction.path("transaction").path("message").path("accountKeys").any { key ->
            key.path("pubkey").let { it.isString && it.asString() == mint.value }
        }

    /** Whether a Pump.fun `create` or `create_v2` in the transaction initialises [mint]. */
    fun creates(transaction: JsonNode, mint: MintAddress): Boolean = mint.value in PumpFunCreationReader.createdMints(transaction)

    /** Whether Pump.fun emitted a `TradeEvent` of [mint] in the transaction. */
    fun trades(transaction: JsonNode, mint: MintAddress): Boolean =
        PumpFunInstructionReader.read(transaction).any { it is PumpFunInstruction.TradeEvent && it.mint == mint }

    /** The block time in epoch seconds, or null when the transaction has none or one no [Instant] can hold. */
    fun blockTime(transaction: JsonNode): Long? = transaction.path("blockTime")
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
        ?.takeIf { it in Instant.MIN.epochSecond..Instant.MAX.epochSecond }
}
