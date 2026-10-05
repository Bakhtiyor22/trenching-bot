package com.trenching.bot.marketdata.infrastructure.extraction

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import tools.jackson.databind.JsonNode

/**
 * Turns every Pump.fun `TradeEvent` instruction of a verified transaction into one `LAST_TRADE` observation or one
 * counted exclusion, per the pumpfun-last-trade-observations ADR. Events are read from the self-invoked instruction,
 * never from `Program data:` log lines.
 */
internal class PumpFunTradeObservationExtractor {
    fun extract(transaction: JsonNode, signature: TransactionSignature, slot: Slot, inWindow: (MintAddress, Slot) -> Boolean): List<TradeExtraction> =
        PumpFunInstructionReader.read(transaction).mapNotNull { instruction ->
            when (instruction) {
                is PumpFunInstruction.TradeEvent -> event(transaction, signature, slot, instruction, inWindow)
                PumpFunInstruction.Malformed -> TradeExtraction.Excluded(MarketObservationExclusion.MALFORMED_EVENT)
                is PumpFunInstruction.Buy, PumpFunInstruction.Other -> null
            }
        }

    private fun event(
        transaction: JsonNode,
        signature: TransactionSignature,
        slot: Slot,
        event: PumpFunInstruction.TradeEvent,
        inWindow: (MintAddress, Slot) -> Boolean,
    ): TradeExtraction {
        val blockTime = transaction.path("blockTime")
        // The window check comes first, so every later reason concerns a trade a replay window needs.
        val reason = when {
            event.mint == MintAddress.WRAPPED_SOL -> MarketObservationExclusion.QUOTE_MINT
            !inWindow(event.mint, slot) -> MarketObservationExclusion.OUT_OF_WINDOW
            event.isBuy != SELL && event.isBuy != BUY -> MarketObservationExclusion.MALFORMED_EVENT
            event.solAmount.signum() == 0 || event.tokenAmount.signum() == 0 -> MarketObservationExclusion.ZERO_AMOUNT
            !blockTime.isIntegralNumber -> MarketObservationExclusion.BLOCK_TIME_MISSING
            else -> null
        }
        if (reason != null) return TradeExtraction.Excluded(reason)
        val decimals = mintDecimals(transaction.path("meta"), event.mint)
            ?: return TradeExtraction.Excluded(MarketObservationExclusion.DECIMALS_UNPROVEN)
        val price = price(event, decimals)
        val sourceEventId = "${signature.value}:${event.topLevelIndex}:${event.innerIndex ?: TOP_LEVEL_INNER_INDEX}"
        val id = "$SOURCE:$sourceEventId:${event.mint.value}"
        val seconds = blockTime.asLong()
        if (price.signum() <= 0 || price.precision() > DecimalPolicy.PRICE_PRECISION || seconds !in 0..LATEST_BLOCK_TIME ||
            id.length > ReplayLimits.MAX_OBSERVATION_ID_LENGTH) {
            return TradeExtraction.Excluded(MarketObservationExclusion.EXCEEDS_REPLAY_BOUNDS)
        }
        return TradeExtraction.Observed(MarketObservation(
            id = id,
            pair = MarketPair.of(event.mint, MintAddress.WRAPPED_SOL),
            slot = slot,
            priceSlot = slot,
            observedTime = Instant.ofEpochSecond(seconds),
            price = price,
            liquidityQuote = null,
            source = SOURCE,
            sourceEventId = sourceEventId,
            commitment = Commitment.CONFIRMED,
            quality = ObservationQuality.LAST_TRADE,
        ))
    }

    /** Curve SOL per whole token, fees excluded: one division of exact integers at the price scale. */
    private fun price(event: PumpFunInstruction.TradeEvent, baseDecimals: Int): BigDecimal {
        val quote = BigDecimal(event.solAmount.multiply(BigInteger.TEN.pow(baseDecimals)))
        val base = BigDecimal(event.tokenAmount.multiply(BigInteger.TEN.pow(PumpFunLayout.SOL_AMOUNT_DECIMALS)))
        return quote.divide(base, DecimalPolicy.PRICE_SCALE, DecimalPolicy.ROUNDING)
    }

    /** The mint's decimals when every pre and post token balance of the mint in the transaction agrees on them. */
    private fun mintDecimals(meta: JsonNode, mint: MintAddress): Int? =
        listOf(meta.path("preTokenBalances"), meta.path("postTokenBalances"))
            .flatMap { balances -> balances.filter { it.path("mint").asString() == mint.value } }
            .map { it.path("uiTokenAmount").path("decimals").asInt() }
            .distinct()
            .singleOrNull()

    private companion object {
        const val SOURCE = "pumpfun-bonding-curve-trade/v1"
        const val TOP_LEVEL_INNER_INDEX = "-"
        const val SELL = 0
        const val BUY = 1
        val LATEST_BLOCK_TIME: Long = Instant.parse("9999-12-31T23:59:59Z").epochSecond
    }
}
