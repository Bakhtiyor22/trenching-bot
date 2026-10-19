package com.trenching.bot.console

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TokenAmount
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.ChartFixtures.OTHER_QUOTE_PAIR
import com.trenching.bot.console.ChartFixtures.PAIR
import com.trenching.bot.console.ChartFixtures.SIGNATURE
import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.tradeObservation
import com.trenching.bot.console.application.ChartWalletTrades
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import com.trenching.bot.walletactivity.domain.TradeSide as WalletSide

class ChartWalletTradesTest {
    private val firstBuyAt = at("2026-10-19T15:58:43Z")
    private val rates = OrderedReferenceRates(listOf(ReferenceRate(at("2026-10-19T15:58:00Z"), BigDecimal("104"), "test-rates")))

    @Test
    fun `a first buy no observation records becomes a tracked wallet marker at the price it was made at`() {
        val marker = ChartWalletTrades.unobserved(listOf(firstBuy()), emptyList(), PAIR, rates).single()

        assertEquals(MarkerKind.OBSERVED, marker.kind)
        assertEquals(WalletEventIdentity.of(TransactionSignature.of(FIRST_BUY_SIGNATURE), 2), marker.observationId)
        assertEquals(TRACKED, marker.trader)
        assertTrue(marker.tracked)
        assertEquals(TradeSide.BUY, marker.side)
        assertEquals(BigInteger.valueOf(2_933_333_333), marker.solLamports)
        assertEquals(BigInteger.valueOf(40_000_022_776_188), marker.tokenRaw)
        assertEquals(0, BigDecimal("7.333329156867911432373951222540E-8").compareTo(marker.price))
        assertEquals("7626.662323142628", marker.mcap?.toPlainString())
        assertEquals(458_891_590L, marker.slot)
        assertEquals(firstBuyAt, marker.time)
        assertEquals(FIRST_BUY_SIGNATURE, marker.signature)
        assertEquals(PumpFunLayout.BUY_SOURCE, marker.source)
    }

    @Test
    fun `a trade the market tape already holds is not repeated`() {
        val observed = tradeObservation("o", firstBuyAt, TRACKED, sourceEventId = "$FIRST_BUY_SIGNATURE:4")

        assertTrue(ChartWalletTrades.unobserved(listOf(firstBuy()), listOf(observed), PAIR, rates).isEmpty())
    }

    @Test
    fun `trades of another pair or without a block time are left out`() {
        val trades = listOf(firstBuy(pair = OTHER_QUOTE_PAIR), firstBuy(blockTime = null))

        assertTrue(ChartWalletTrades.unobserved(trades, emptyList(), PAIR, rates).isEmpty())
    }

    @Test
    fun `a minute without a rate or a trade that is not a SOL-quoted Pump fun buy has no mcap`() {
        val noRate = ChartWalletTrades.unobserved(listOf(firstBuy()), emptyList(), PAIR, null).single()
        val otherRoute = ChartWalletTrades.unobserved(listOf(firstBuy(source = "other-route/v1")), emptyList(), PAIR, rates).single()

        assertNull(noRate.mcap)
        assertNull(otherRoute.mcap)
        assertEquals(noRate.price, otherRoute.price)
    }

    @Test
    fun `markers come in chart order`() {
        val later = firstBuy(blockTime = firstBuyAt.plusSeconds(5), signature = SIGNATURE)

        val markers = ChartWalletTrades.unobserved(listOf(later, firstBuy()), emptyList(), PAIR, rates)

        assertEquals(listOf(firstBuyAt, firstBuyAt.plusSeconds(5)), markers.map { it.time })
    }

    private fun firstBuy(
        pair: MarketPair = PAIR,
        blockTime: Instant? = firstBuyAt,
        source: String = PumpFunLayout.BUY_SOURCE,
        signature: String = FIRST_BUY_SIGNATURE,
    ) = WalletTradeObserved(
        WalletEventIdentity.of(TransactionSignature.of(signature), 2), WalletAddress.of(TRACKED), TransactionSignature.of(signature), 2,
        Slot.of(458_891_590), blockTime, WalletSide.BUY, pair, TokenAmount.of(BigInteger.valueOf(40_000_022_776_188), 6),
        TokenAmount.of(BigInteger.valueOf(2_933_333_333), 9), source, Commitment.CONFIRMED,
    )

    private companion object {
        const val FIRST_BUY_SIGNATURE = "3uzy2eQpHGrLWNsrWgveyj69y9ueGgWzgSkyyLzmXK7NTdXK9N6vK9Ko3bTuzyc91v9PHJFYTnZPLLgB5rNzxa9F"
    }
}
