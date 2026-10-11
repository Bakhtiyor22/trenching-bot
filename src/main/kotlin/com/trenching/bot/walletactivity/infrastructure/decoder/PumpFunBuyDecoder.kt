package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionName
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.walletactivity.PumpFunBuyFacts
import com.trenching.bot.walletactivity.PumpFunBuyInstruction
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.time.Instant
import tools.jackson.databind.JsonNode

/**
 * Decodes a successful transaction signed by the wallet that contains exactly one SOL-quoted Pump.fun bonding-curve buy:
 * a legacy `buy` or a `buy_exact_quote_in_v3`.
 * The trade is read from Pump.fun's self-invoked `TradeEvent` instruction, never from `Program data:` logs,
 * because only Pump.fun can emit that instruction while any program can write a log line.
 */
internal class PumpFunBuyDecoder {
    /** With a [targetMint], a buy of any other mint is rejected as [PumpFunBuyRejection.OTHER_MINT]. */
    fun decode(transaction: JsonNode, wallet: WalletAddress, targetMint: MintAddress? = null): PumpFunBuyDecoding {
        val instructions = PumpFunInstructionReader.read(transaction)
        val names = names(instructions)
        val decoding = decodeBuy(transaction, wallet, instructions, names)
        return if (decoding is PumpFunBuyDecoding.Decoded && targetMint != null && decoding.facts.mint != targetMint) {
            rejected(PumpFunBuyRejection.OTHER_MINT, names)
        } else decoding
    }

    private fun decodeBuy(transaction: JsonNode, wallet: WalletAddress, instructions: List<PumpFunInstruction>, names: Set<String>): PumpFunBuyDecoding {
        if (instructions.any { it is PumpFunInstruction.Malformed }) return rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE, names)
        val buys = instructions.filter { it is PumpFunInstruction.Buy || it is PumpFunInstruction.BuyExactQuoteInV3 }
        val events = instructions.filterIsInstance<PumpFunInstruction.TradeEvent>()
        if (buys.size > 1) return rejected(PumpFunBuyRejection.MULTIPLE_BUYS, names)
        if (events.size > 1) return rejected(PumpFunBuyRejection.MULTIPLE_TRADE_EVENTS, names)
        val buy = buys.firstOrNull() ?: return rejected(PumpFunBuyRejection.NO_BUY, names)
        val event = events.firstOrNull() ?: return rejected(PumpFunBuyRejection.NO_TRADE_EVENT, names)
        val mismatch = mismatch(buy, event, wallet)
        if (mismatch != null) return rejected(mismatch, names)
        return when (val change = WalletTokenBalances.change(transaction.path("meta"), wallet, event.mint)) {
            is WalletTokenBalanceChange.Unproven -> rejected(change.reason, names)
            is WalletTokenBalanceChange.Measured ->
                if (change.delta != event.tokenAmount) rejected(PumpFunBuyRejection.BALANCE_MISMATCH, names)
                else represent(transaction, wallet, buy, event, change.decimals, names)
        }
    }

    /** Checks shared by both buy instructions, then the instruction's own binding to the event's amounts. */
    private fun mismatch(buy: PumpFunInstruction, event: PumpFunInstruction.TradeEvent, wallet: WalletAddress): PumpFunBuyRejection? =
        when {
            event.isBuy == SELL -> PumpFunBuyRejection.SELL_EVENT
            event.isBuy != BUY -> PumpFunBuyRejection.MALFORMED_EVIDENCE
            event.user != wallet -> PumpFunBuyRejection.OTHER_USER
            topLevelIndex(buy) != event.topLevelIndex -> PumpFunBuyRejection.INSTRUCTION_MISMATCH
            // Before the amount checks: a non-SOL curve reports its quote elsewhere, so its SOL fields are all zero.
            event.solAmount.signum() == 0 && event.virtualSolReserves.signum() == 0 -> PumpFunBuyRejection.NON_SOL_QUOTE
            !amountsMatch(buy, event) -> PumpFunBuyRejection.AMOUNT_MISMATCH
            event.tokenAmount.signum() == 0 || event.solAmount.signum() == 0 -> PumpFunBuyRejection.ZERO_AMOUNT
            event.mint == MintAddress.WRAPPED_SOL -> PumpFunBuyRejection.MALFORMED_EVIDENCE
            else -> null
        }

    /**
     * Legacy `buy` names its exact token output. `buy_exact_quote_in_v3` spends exactly the curve payment plus both fees and
     * names a minimum output, per the pumpfun-exact-quote-in-v3-buys ADR.
     */
    private fun amountsMatch(buy: PumpFunInstruction, event: PumpFunInstruction.TradeEvent): Boolean = when (buy) {
        is PumpFunInstruction.Buy -> buy.tokenAmount == event.tokenAmount
        is PumpFunInstruction.BuyExactQuoteInV3 ->
            buy.spendableQuoteIn == event.solAmount.add(event.feeLamports).add(event.creatorFeeLamports) &&
                event.tokenAmount >= buy.minBaseAmountOut
        else -> false
    }

    private fun topLevelIndex(buy: PumpFunInstruction): Int? = when (buy) {
        is PumpFunInstruction.Buy -> buy.topLevelIndex
        is PumpFunInstruction.BuyExactQuoteInV3 -> buy.topLevelIndex
        else -> null
    }

    private fun kind(buy: PumpFunInstruction): PumpFunBuyInstruction =
        if (buy is PumpFunInstruction.BuyExactQuoteInV3) PumpFunBuyInstruction.BUY_EXACT_QUOTE_IN_V3 else PumpFunBuyInstruction.BUY

    /** The named Pump.fun instructions present, plus markers for unrecognised Pump.fun data or no Pump.fun instruction at all. */
    private fun names(instructions: List<PumpFunInstruction>): Set<String> {
        val names = instructions.mapNotNullTo(sortedSetOf()) { instruction ->
            when (instruction) {
                is PumpFunInstruction.Buy -> PumpFunInstructionName.BUY.name
                is PumpFunInstruction.BuyExactQuoteInV3 -> PumpFunInstructionName.BUY_EXACT_QUOTE_IN_V3.name
                is PumpFunInstruction.Unsupported -> instruction.name.name
                PumpFunInstruction.Other -> OTHER_PUMPFUN
                is PumpFunInstruction.TradeEvent, PumpFunInstruction.Malformed -> null
            }
        }
        if (instructions.isEmpty()) names += NO_PUMPFUN
        return names
    }

    private fun represent(
        transaction: JsonNode,
        wallet: WalletAddress,
        buy: PumpFunInstruction,
        event: PumpFunInstruction.TradeEvent,
        baseDecimals: Int,
        names: Set<String>,
    ): PumpFunBuyDecoding {
        val malformed = rejected(PumpFunBuyRejection.MALFORMED_EVIDENCE, names)
        val signature = signature(transaction) ?: return malformed
        val slot = slot(transaction) ?: return malformed
        val blockTime = transaction.path("blockTime")
        val time = if (blockTime.isNull) null else blockTime(blockTime) ?: return malformed
        val facts = facts(transaction, wallet, signature, slot, event, kind(buy)) ?: return malformed
        return PumpFunBuyDecoding.Decoded(WalletTransaction(
            wallet = wallet,
            signature = signature,
            instructionIndex = event.topLevelIndex,
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
            source = PumpFunLayout.BUY_SOURCE,
            commitment = Commitment.CONFIRMED,
        ), facts)
    }

    private fun facts(
        transaction: JsonNode,
        wallet: WalletAddress,
        signature: TransactionSignature,
        slot: Slot,
        event: PumpFunInstruction.TradeEvent,
        instruction: PumpFunBuyInstruction,
    ): PumpFunBuyFacts? {
        if (!CurveReserves.isValid(event.virtualSolReserves, event.virtualTokenReserves, event.realSolReserves, event.realTokenReserves)) {
            return null
        }
        val transactionFee = WalletLamportBalances.transactionFee(transaction) ?: return null
        val walletDelta = WalletLamportBalances.delta(transaction, wallet) ?: return null
        return PumpFunBuyFacts(
            signature = signature,
            slot = slot,
            mint = event.mint,
            solAmount = event.solAmount,
            tokenAmount = event.tokenAmount,
            feeLamports = event.feeLamports,
            creatorFeeLamports = event.creatorFeeLamports,
            feeBps = event.feeBasisPoints,
            creatorFeeBps = event.creatorFeeBasisPoints,
            postReserves = CurveReserves(event.virtualSolReserves, event.virtualTokenReserves, event.realSolReserves, event.realTokenReserves),
            transactionFeeLamports = transactionFee,
            walletLamportDelta = walletDelta,
            instruction = instruction,
        )
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

    private fun rejected(reason: PumpFunBuyRejection, names: Set<String>): PumpFunBuyDecoding = PumpFunBuyDecoding.Rejected(reason, names)

    private companion object {
        const val SELL = 0
        const val BUY = 1
        const val OTHER_PUMPFUN = "OTHER_PUMPFUN"
        const val NO_PUMPFUN = "NO_PUMPFUN"
    }
}
