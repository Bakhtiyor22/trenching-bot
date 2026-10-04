package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger

sealed interface PumpFunInstruction {
    data class Buy(val topLevelIndex: Int, val tokenAmount: BigInteger) : PumpFunInstruction

    /** [innerIndex] is the position inside the top-level instruction's inner instructions, or null for a top-level event. */
    data class TradeEvent(
        val topLevelIndex: Int,
        val innerIndex: Int?,
        val mint: MintAddress,
        val solAmount: BigInteger,
        val tokenAmount: BigInteger,
        val isBuy: Int,
        val user: WalletAddress,
    ) : PumpFunInstruction

    data object Other : PumpFunInstruction

    data object Malformed : PumpFunInstruction
}
