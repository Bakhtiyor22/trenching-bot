package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger

sealed interface PumpFunInstruction {
    /** Legacy `buy`: exact [tokenAmount] out. */
    data class Buy(val topLevelIndex: Int, val tokenAmount: BigInteger) : PumpFunInstruction

    /** `buy_exact_quote_in_v3`: spends exactly [spendableQuoteIn] including fees, for at least [minBaseAmountOut] tokens. */
    data class BuyExactQuoteInV3(val topLevelIndex: Int, val spendableQuoteIn: BigInteger, val minBaseAmountOut: BigInteger) : PumpFunInstruction

    /** A recognised instruction no decoder supports, such as a sell. */
    data class Unsupported(val topLevelIndex: Int, val name: PumpFunInstructionName) : PumpFunInstruction

    /**
     * [innerIndex] is the position inside the top-level instruction's inner instructions, or null for a top-level event.
     * Reserves are the curve's state after the trade. The basis points are raw u64 values; consumers validate their range.
     */
    data class TradeEvent(
        val topLevelIndex: Int,
        val innerIndex: Int?,
        val mint: MintAddress,
        val solAmount: BigInteger,
        val tokenAmount: BigInteger,
        val isBuy: Int,
        val user: WalletAddress,
        val virtualSolReserves: BigInteger,
        val virtualTokenReserves: BigInteger,
        val realSolReserves: BigInteger,
        /** Zero means the bonding curve completed with this trade. */
        val realTokenReserves: BigInteger,
        val feeBasisPoints: BigInteger,
        val feeLamports: BigInteger,
        val creatorFeeBasisPoints: BigInteger,
        val creatorFeeLamports: BigInteger,
    ) : PumpFunInstruction

    data object Other : PumpFunInstruction

    data object Malformed : PumpFunInstruction
}

/** Names of the Pump.fun instructions a capture summary can report. */
enum class PumpFunInstructionName { BUY, BUY_EXACT_QUOTE_IN_V3, SELL, SELL_V3, BUY_EXACT_QUOTE_IN, BUY_V3 }
