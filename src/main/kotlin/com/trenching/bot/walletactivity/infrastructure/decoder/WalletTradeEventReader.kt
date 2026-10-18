package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.chaincapture.CapturedTransactionCheck
import com.trenching.bot.chaincapture.CapturedTransactionShape
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Instant
import tools.jackson.databind.JsonNode

internal sealed interface WalletTradeEvents {
    data class Read(val actions: List<WalletAction>) : WalletTradeEvents

    data object Unreadable : WalletTradeEvents
}

/**
 * Reads the wallet's own Pump.fun `TradeEvent`s from a transaction its listing reported as successful. A transaction that
 * does not match its listing entry, carries any malformed Pump.fun data, has no wallet event, or has a wallet event that
 * is not a SOL-quoted curve trade (a PumpSwap route, an unknown layout, a non-SOL quote) is unreadable as a whole.
 */
internal object WalletTradeEventReader {
    private const val SELL = 0
    private const val BUY = 1

    fun read(transaction: JsonNode, entry: SignatureListingEntry, wallet: WalletAddress): WalletTradeEvents {
        if (CapturedTransactionCheck.shape(transaction, entry.signature, entry.slot) != CapturedTransactionShape.VALID) return WalletTradeEvents.Unreadable
        val instructions = PumpFunInstructionReader.read(transaction)
        if (instructions.any { it is PumpFunInstruction.Malformed }) return WalletTradeEvents.Unreadable
        val events = instructions.filterIsInstance<PumpFunInstruction.TradeEvent>().filter { it.user == wallet }
        val sides = events.map(::side)
        if (events.isEmpty() || sides.any { it == null }) return WalletTradeEvents.Unreadable
        val signature = TransactionSignature.of(entry.signature)
        val blockTime = TargetMintActivity.blockTime(transaction)?.let(Instant::ofEpochSecond)
        return WalletTradeEvents.Read(events.zip(sides.filterNotNull()) { event, side ->
            WalletAction(signature, event.topLevelIndex, event.innerIndex, Slot.of(entry.slot), blockTime, side, event.mint, event.solAmount, event.tokenAmount)
        })
    }

    /** Null for an event that is not a SOL-quoted curve trade: a non-SOL curve reports zero SOL, and a zero amount trades nothing. */
    private fun side(event: PumpFunInstruction.TradeEvent): TradeSide? {
        if (event.solAmount.signum() == 0 || event.tokenAmount.signum() == 0 || event.mint == MintAddress.WRAPPED_SOL) return null
        return when (event.isBuy) {
            BUY -> TradeSide.BUY
            SELL -> TradeSide.SELL
            else -> null
        }
    }
}
