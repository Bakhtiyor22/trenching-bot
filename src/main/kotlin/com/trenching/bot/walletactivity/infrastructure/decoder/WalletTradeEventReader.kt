package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Instant
import tools.jackson.databind.JsonNode

internal sealed interface WalletTradeReading {
    data class Read(val actions: List<WalletAction>) : WalletTradeReading

    /** No trade by the wallet could be decoded without guessing: none present, a malformed instruction, or a non-SOL quote. */
    data object Unreadable : WalletTradeReading
}

/** Reads every Pump.fun `TradeEvent` whose user is the wallet from one checked `getTransaction` result. */
internal object WalletTradeEventReader {
    fun read(transaction: JsonNode, signature: TransactionSignature, wallet: WalletAddress): WalletTradeReading {
        val instructions = PumpFunInstructionReader.read(transaction)
        if (instructions.any { it is PumpFunInstruction.Malformed }) return WalletTradeReading.Unreadable
        val events = instructions.filterIsInstance<PumpFunInstruction.TradeEvent>().filter { it.user == wallet }
        val slot = Slot.of(transaction.path("slot").asLong())
        val blockTime = transaction.path("blockTime").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.let { Instant.ofEpochSecond(it.asLong()) }
        val actions = events.mapNotNull { event ->
            side(event)?.takeUnless { isNonSolQuote(event) }?.let { side ->
                WalletAction(signature, event.topLevelIndex, event.innerIndex, slot, blockTime, side, event.mint, event.solAmount, event.tokenAmount)
            }
        }
        return if (actions.isEmpty() || actions.size != events.size) WalletTradeReading.Unreadable else WalletTradeReading.Read(actions)
    }

    private fun side(event: PumpFunInstruction.TradeEvent): TradeSide? = when (event.isBuy) {
        BUY -> TradeSide.BUY
        SELL -> TradeSide.SELL
        else -> null
    }

    /** A non-SOL curve reports its quote elsewhere, so its SOL fields are all zero. */
    private fun isNonSolQuote(event: PumpFunInstruction.TradeEvent): Boolean =
        event.solAmount.signum() == 0 && event.virtualSolReserves.signum() == 0

    private const val SELL = 0
    private const val BUY = 1
}
