package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.time.Instant
import tools.jackson.databind.JsonNode

/**
 * Decodes a successful transaction signed by the wallet that contains exactly one Pump.fun bonding-curve `buy`.
 * The trade is read from Pump.fun's self-invoked `TradeEvent` instruction, never from `Program data:` logs,
 * because only Pump.fun can emit that instruction while any program can write a log line.
 */
internal class PumpFunBuyDecoder {
    fun decode(transaction: JsonNode, wallet: WalletAddress): PumpFunBuyDecoding {
        val instructions = PumpFunInstructionReader.read(transaction)
        if (instructions.any { it is PumpFunInstruction.Malformed }) return rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        val buys = instructions.filterIsInstance<PumpFunInstruction.Buy>()
        val events = instructions.filterIsInstance<PumpFunInstruction.TradeEvent>()
        if (buys.size > 1) return rejected(PumpFunBuyRejection.MULTIPLE_BUYS)
        if (events.size > 1) return rejected(PumpFunBuyRejection.MULTIPLE_TRADE_EVENTS)
        val buy = buys.firstOrNull() ?: return rejected(PumpFunBuyRejection.NO_BUY)
        val event = events.firstOrNull() ?: return rejected(PumpFunBuyRejection.NO_TRADE_EVENT)
        val mismatch = mismatch(buy, event, wallet)
        if (mismatch != null) return rejected(mismatch)
        return when (val change = WalletTokenBalances.change(transaction.path("meta"), wallet, event.mint)) {
            is WalletTokenBalanceChange.Unproven -> rejected(change.reason)
            is WalletTokenBalanceChange.Measured ->
                if (change.delta != event.tokenAmount) rejected(PumpFunBuyRejection.BALANCE_MISMATCH)
                else represent(transaction, wallet, buy, event, change.decimals)
        }
    }

    private fun mismatch(buy: PumpFunInstruction.Buy, event: PumpFunInstruction.TradeEvent, wallet: WalletAddress): PumpFunBuyRejection? =
        when {
            event.isBuy == SELL -> PumpFunBuyRejection.SELL_EVENT
            event.isBuy != BUY -> PumpFunBuyRejection.MALFORMED_EVIDENCE
            event.user != wallet -> PumpFunBuyRejection.OTHER_USER
            buy.topLevelIndex != event.topLevelIndex -> PumpFunBuyRejection.INSTRUCTION_MISMATCH
            buy.tokenAmount != event.tokenAmount -> PumpFunBuyRejection.AMOUNT_MISMATCH
            event.tokenAmount.signum() == 0 || event.solAmount.signum() == 0 -> PumpFunBuyRejection.ZERO_AMOUNT
            event.mint == MintAddress.WRAPPED_SOL -> PumpFunBuyRejection.MALFORMED_EVIDENCE
            else -> null
        }

    private fun represent(
        transaction: JsonNode,
        wallet: WalletAddress,
        buy: PumpFunInstruction.Buy,
        event: PumpFunInstruction.TradeEvent,
        baseDecimals: Int,
    ): PumpFunBuyDecoding {
        val signature = signature(transaction) ?: return rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        val slot = slot(transaction) ?: return rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        val blockTime = transaction.path("blockTime")
        val time = if (blockTime.isNull) null else blockTime(blockTime) ?: return rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        return PumpFunBuyDecoding.Decoded(WalletTransaction(
            wallet = wallet,
            signature = signature,
            instructionIndex = buy.topLevelIndex,
            slot = slot,
            blockTime = time,
            pair = MarketPair.of(event.mint, MintAddress.WRAPPED_SOL),
            baseDelta = event.tokenAmount,
            quoteDelta = event.solAmount.negate(),
            baseDecimals = baseDecimals,
            quoteDecimals = PumpFunLayout.SOL_AMOUNT_DECIMALS,
            successful = true,
            supportedRoute = true,
            signer = true,
            source = SOURCE,
            commitment = Commitment.CONFIRMED,
        ))
    }

    private fun signature(transaction: JsonNode): TransactionSignature? {
        val value = transaction.path("transaction").path("signatures").path(0)
        if (!value.isString) return null
        return try { TransactionSignature.of(value.asString()) } catch (_: IllegalArgumentException) { null }
    }

    private fun slot(transaction: JsonNode): Slot? {
        val value = transaction.path("slot")
        return if (value.isIntegralNumber && value.canConvertToLong() && value.asLong() >= 0) Slot.of(value.asLong()) else null
    }

    private fun blockTime(value: JsonNode): Instant? =
        if (value.isIntegralNumber && value.canConvertToLong() && value.asLong() in Instant.MIN.epochSecond..Instant.MAX.epochSecond) {
            Instant.ofEpochSecond(value.asLong())
        } else null

    private fun rejected(reason: PumpFunBuyRejection): PumpFunBuyDecoding = PumpFunBuyDecoding.Rejected(reason)

    private companion object {
        const val SOURCE = "pumpfun-bonding-curve-buy/v1"
        const val SELL = 0
        const val BUY = 1
    }
}
