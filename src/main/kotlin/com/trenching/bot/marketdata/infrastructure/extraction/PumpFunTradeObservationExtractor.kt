package com.trenching.bot.marketdata.infrastructure.extraction

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.time.Instant
import tools.jackson.databind.JsonNode

/**
 * Turns every Pump.fun `TradeEvent` instruction of a verified transaction into one `LAST_TRADE` observation or one
 * counted exclusion, per the pumpfun-last-trade-observations ADR. Events are read from the self-invoked instruction,
 * never from `Program data:` log lines. An observation's trade actor is the event's `user`, never the fee payer or a router.
 */
internal class PumpFunTradeObservationExtractor {
    fun extract(transaction: JsonNode, signature: TransactionSignature, slot: Slot, inWindow: (MintAddress, Slot) -> Boolean): List<TradeExtraction> =
        PumpFunInstructionReader.read(transaction).mapNotNull { instruction ->
            when (instruction) {
                is PumpFunInstruction.TradeEvent -> event(transaction, signature, slot, instruction, inWindow)
                PumpFunInstruction.Malformed -> TradeExtraction.Excluded(MarketObservationExclusion.MALFORMED_EVENT)
                is PumpFunInstruction.Buy, is PumpFunInstruction.BuyExactQuoteInV3, is PumpFunInstruction.Unsupported,
                PumpFunInstruction.Other -> null
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
        val curve = curveState(event) ?: return TradeExtraction.Excluded(MarketObservationExclusion.MALFORMED_EVENT)
        val decimals = mintDecimals(transaction.path("meta"), event.mint)
            ?: return TradeExtraction.Excluded(MarketObservationExclusion.DECIMALS_UNPROVEN)
        val price = BondingCurve.price(event.solAmount, event.tokenAmount, decimals)
        val sourceEventId = "${signature.value}:${event.topLevelIndex}:${event.innerIndex ?: TOP_LEVEL_INNER_INDEX}"
        val id = "${PumpFunLayout.TRADE_SOURCE}:$sourceEventId:${event.mint.value}"
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
            source = PumpFunLayout.TRADE_SOURCE,
            sourceEventId = sourceEventId,
            commitment = Commitment.CONFIRMED,
            quality = ObservationQuality.LAST_TRADE,
            curveComplete = curve.complete,
            curve = curve,
            trade = ObservedTrade.of(event.user.value, if (event.isBuy == BUY) TradeSide.BUY else TradeSide.SELL, event.solAmount, event.tokenAmount),
        ))
    }

    /** The event's curve state, or null when its reserves break the curve's invariants or a fee basis point is out of range. */
    private fun curveState(event: PumpFunInstruction.TradeEvent): CurveState? {
        if (!CurveReserves.isValid(event.virtualSolReserves, event.virtualTokenReserves, event.realSolReserves, event.realTokenReserves)) {
            return null
        }
        val feeBps = CurveState.basisPoints(event.feeBasisPoints) ?: return null
        val creatorFeeBps = CurveState.basisPoints(event.creatorFeeBasisPoints) ?: return null
        return CurveState(CurveReserves(event.virtualSolReserves, event.virtualTokenReserves, event.realSolReserves, event.realTokenReserves),
            feeBps, creatorFeeBps)
    }

    /** The mint's decimals when every pre and post token balance of the mint in the transaction agrees on them. */
    private fun mintDecimals(meta: JsonNode, mint: MintAddress): Int? =
        listOf(meta.path("preTokenBalances"), meta.path("postTokenBalances"))
            .flatMap { balances -> balances.filter { it.path("mint").asString() == mint.value } }
            .map { it.path("uiTokenAmount").path("decimals").asInt() }
            .distinct()
            .singleOrNull()

    private companion object {
        const val TOP_LEVEL_INNER_INDEX = "-"
        const val SELL = 0
        const val BUY = 1
        val LATEST_BLOCK_TIME: Long = Instant.parse("9999-12-31T23:59:59Z").epochSecond
    }
}
