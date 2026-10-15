package com.trenching.bot.console

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Hand-built observations for the chart tests: only the fields the chart reads vary. */
object ChartFixtures {
    const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    const val OTHER_MINT = "Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye"
    const val SIGNATURE = "2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM"
    val TRADER_A: String = Base58.encode(ByteArray(32) { 1 })
    val TRADER_B: String = Base58.encode(ByteArray(32) { 2 })
    val TRACKED: String = Base58.encode(ByteArray(32) { 3 })

    val PAIR: MarketPair = MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL)
    val OTHER_QUOTE_PAIR: MarketPair = MarketPair.of(MintAddress.of(MINT), MintAddress.of(OTHER_MINT))
    private val CURVE = CurveState(CurveReserves(BigInteger.TEN, BigInteger.TEN, BigInteger.ONE, BigInteger.ONE), 0, 0)

    fun at(isoTime: String): Instant = Instant.parse(isoTime)

    /** A price-driven observation without a trade. */
    fun observation(
        id: String,
        time: Instant,
        price: String,
        slot: Long = time.epochSecond,
        pair: MarketPair = PAIR,
        source: String = PumpFunLayout.TRADE_SOURCE,
        sourceEventId: String = "$SIGNATURE:0",
        trade: ObservedTrade? = null,
    ) = MarketObservation(id, pair, Slot.of(slot), Slot.of(slot), time, BigDecimal(price), null, source, sourceEventId,
        Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false, CURVE, trade)

    /** A trade observation whose price follows from the trade's amounts, as a recorded schema v4 one does. */
    fun tradeObservation(
        id: String,
        time: Instant,
        trader: String,
        side: TradeSide = TradeSide.BUY,
        slot: Long = time.epochSecond,
        lamports: Long = 1_000_000_000L,
        tokens: Long = 50_000_000_000L,
        sourceEventId: String = "$SIGNATURE:0",
    ): MarketObservation {
        val trade = ObservedTrade.of(trader, side, BigInteger.valueOf(lamports), BigInteger.valueOf(tokens))
        val price = BondingCurve.price(trade.solLamports, trade.tokenRaw)
        return observation(id, time, price.toPlainString(), slot, sourceEventId = sourceEventId, trade = trade)
    }
}
