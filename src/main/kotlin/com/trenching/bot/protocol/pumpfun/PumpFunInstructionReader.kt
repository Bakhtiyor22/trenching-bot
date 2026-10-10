package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger
import java.util.HexFormat
import tools.jackson.databind.JsonNode

/** Reads every Pump.fun instruction, top-level and inner, tagged with the top-level instruction that contains it. */
object PumpFunInstructionReader {
    fun read(transaction: JsonNode): List<PumpFunInstruction> {
        val topLevel = transaction.path("transaction").path("message").path("instructions")
        val direct = topLevel.mapIndexedNotNull { index, instruction -> parse(instruction, index, innerIndex = null) }
        val inner = transaction.path("meta").path("innerInstructions").flatMap { group ->
            val index = group.path("index")
            val topLevelIndex = if (index.isIntegralNumber && index.canConvertToInt() && index.asInt() in 0 until topLevel.size()) index.asInt() else null
            group.path("instructions").mapIndexedNotNull { innerIndex, instruction ->
                if (topLevelIndex == null) PumpFunInstruction.Malformed.takeIf { isPumpFun(instruction) }
                else parse(instruction, topLevelIndex, innerIndex)
            }
        }
        return direct + inner
    }

    private fun parse(instruction: JsonNode, topLevelIndex: Int, innerIndex: Int?): PumpFunInstruction? {
        if (!isPumpFun(instruction)) return null
        val data = instruction.path("data")
        if (!data.isString || data.asString().length > PumpFunLayout.MAX_DATA_CHARACTERS) return PumpFunInstruction.Malformed
        val bytes = Base58.decode(data.asString()) ?: return PumpFunInstruction.Malformed
        return when {
            bytes.startsWith(PumpFunLayout.BUY_DISCRIMINATOR) -> buy(bytes, topLevelIndex)
            bytes.startsWith(PumpFunLayout.BUY_EXACT_QUOTE_IN_V3_DISCRIMINATOR) -> buyExactQuoteInV3(bytes, topLevelIndex)
            bytes.startsWith(PumpFunLayout.TRADE_EVENT_PREFIX) -> tradeEvent(bytes, topLevelIndex, innerIndex)
            else -> unsupported(bytes, topLevelIndex)
        }
    }

    private fun buyExactQuoteInV3(bytes: ByteArray, topLevelIndex: Int): PumpFunInstruction =
        if (bytes.size < PumpFunLayout.BUY_EXACT_QUOTE_IN_V3_DATA_BYTES) PumpFunInstruction.Malformed
        else PumpFunInstruction.BuyExactQuoteInV3(topLevelIndex,
            u64(bytes, PumpFunLayout.BUY_EXACT_QUOTE_IN_V3_SPENDABLE_OFFSET), u64(bytes, PumpFunLayout.BUY_EXACT_QUOTE_IN_V3_MIN_OUT_OFFSET))

    private fun unsupported(bytes: ByteArray, topLevelIndex: Int): PumpFunInstruction {
        if (bytes.size < PumpFunLayout.DISCRIMINATOR_BYTES) return PumpFunInstruction.Other
        val name = PumpFunLayout.UNSUPPORTED_INSTRUCTIONS[HEX.formatHex(bytes, 0, PumpFunLayout.DISCRIMINATOR_BYTES)]
        return if (name == null) PumpFunInstruction.Other else PumpFunInstruction.Unsupported(topLevelIndex, name)
    }

    private fun buy(bytes: ByteArray, topLevelIndex: Int): PumpFunInstruction =
        if (bytes.size < PumpFunLayout.BUY_DATA_BYTES) PumpFunInstruction.Malformed
        else PumpFunInstruction.Buy(topLevelIndex, u64(bytes, PumpFunLayout.BUY_TOKEN_AMOUNT_OFFSET))

    private fun tradeEvent(bytes: ByteArray, topLevelIndex: Int, innerIndex: Int?): PumpFunInstruction =
        if (bytes.size < PumpFunLayout.TRADE_EVENT_PREFIX_BYTES) PumpFunInstruction.Malformed
        else PumpFunInstruction.TradeEvent(
            topLevelIndex = topLevelIndex,
            innerIndex = innerIndex,
            mint = MintAddress.of(publicKey(bytes, PumpFunLayout.EVENT_MINT_OFFSET)),
            solAmount = u64(bytes, PumpFunLayout.EVENT_SOL_AMOUNT_OFFSET),
            tokenAmount = u64(bytes, PumpFunLayout.EVENT_TOKEN_AMOUNT_OFFSET),
            isBuy = bytes[PumpFunLayout.EVENT_IS_BUY_OFFSET].toInt() and 0xFF,
            user = WalletAddress.of(publicKey(bytes, PumpFunLayout.EVENT_USER_OFFSET)),
            virtualSolReserves = u64(bytes, PumpFunLayout.EVENT_VIRTUAL_SOL_RESERVES_OFFSET),
            virtualTokenReserves = u64(bytes, PumpFunLayout.EVENT_VIRTUAL_TOKEN_RESERVES_OFFSET),
            realSolReserves = u64(bytes, PumpFunLayout.EVENT_REAL_SOL_RESERVES_OFFSET),
            realTokenReserves = u64(bytes, PumpFunLayout.EVENT_REAL_TOKEN_RESERVES_OFFSET),
            feeBasisPoints = u64(bytes, PumpFunLayout.EVENT_FEE_BASIS_POINTS_OFFSET),
            feeLamports = u64(bytes, PumpFunLayout.EVENT_FEE_OFFSET),
            creatorFeeBasisPoints = u64(bytes, PumpFunLayout.EVENT_CREATOR_FEE_BASIS_POINTS_OFFSET),
            creatorFeeLamports = u64(bytes, PumpFunLayout.EVENT_CREATOR_FEE_OFFSET),
        )

    private fun isPumpFun(instruction: JsonNode): Boolean {
        val programId = instruction.path("programId")
        return programId.isString && programId.asString() == PumpFunLayout.PROGRAM_ID
    }

    private fun u64(bytes: ByteArray, offset: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(offset, offset + PumpFunLayout.U64_BYTES).reversedArray())

    private fun publicKey(bytes: ByteArray, offset: Int): String =
        Base58.encode(bytes.copyOfRange(offset, offset + PumpFunLayout.PUBLIC_KEY_BYTES))

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val HEX: HexFormat = HexFormat.of()
}
