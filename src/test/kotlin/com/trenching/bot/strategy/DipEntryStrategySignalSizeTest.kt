package com.trenching.bot.strategy

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TokenAmount
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.strategy.application.DipEntryStrategy
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.PriceMeasure
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import com.trenching.bot.walletactivity.domain.TradeSide as WalletSide

/**
 * Spec P3 at 1.9 SOL. The wallet's buy at slot 1000 is recorded with a curve payment of [AT_MINIMUM] lamports, which pays exactly
 * 1.9 SOL with its 95 and 30 basis point fees, and leaves a post-trade spot of 1.1e-7, worth $22,000 at 200 USDT per SOL.
 * The trade at slot 1003 meets a 15% dip on both measures.
 */
class DipEntryStrategySignalSizeTest {
    private val wallet = WalletAddress.of(Base58.encode(ByteArray(32) { 7 }))
    private val pair = MarketPair.of(MintAddress.of(Base58.encode(ByteArray(32) { 5 })), MintAddress.WRAPPED_SOL)
    private val signature = TransactionSignature.of(Base58.encode(ByteArray(64) { 9 }))
    private val rates = OrderedReferenceRates(listOf(ReferenceRate(T0, BigDecimal("200"), "test")))

    private val buy = WalletTradeObserved(WalletEventIdentity.of(signature, BUY_INDEX), wallet, signature, BUY_INDEX, Slot.of(1000), T0,
        WalletSide.BUY, pair, TokenAmount.of(BigInteger.valueOf(1_000_000_000_000), PumpFunLayout.TOKEN_DECIMALS),
        TokenAmount.of(BigInteger.valueOf(100_000_000), PumpFunLayout.SOL_AMOUNT_DECIMALS), PumpFunLayout.BUY_SOURCE, Commitment.CONFIRMED)

    private val dip = observation(1003, price = "0.000000085", virtualSol = 25_500_000_000)

    private fun buyObservation(curvePayment: BigInteger = AT_MINIMUM, id: String = "test:1000:0"): MarketObservation =
        observation(1000, id = id, sourceEventId = "${signature.value}:$BUY_INDEX:-", virtualSol = 33_000_000_000,
            trade = ObservedTrade.of(wallet.value, TradeSide.BUY, curvePayment, BigInteger.valueOf(1_000_000_000_000)))

    private fun observation(
        slot: Long,
        id: String = "test:$slot:0",
        price: String = "0.0000001",
        virtualSol: Long = 30_000_000_000,
        sourceEventId: String = id,
        trade: ObservedTrade? = null,
    ): MarketObservation = MarketObservation(id, pair, Slot.of(slot), Slot.of(slot), T0.plusSeconds(slot - 1000), BigDecimal(price), null,
        PumpFunLayout.TRADE_SOURCE, sourceEventId, Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false,
        CurveState(CurveReserves(BigInteger.valueOf(virtualSol), BigInteger.valueOf(300_000_000_000_000), BigInteger.valueOf(1_000_000_000),
            BigInteger.valueOf(100_000_000_000_000)), 95, 30), trade)

    private fun config(
        minSignalSol: String? = "1.9",
        minMarketCapUsd: String = "10000",
        priceMeasure: PriceMeasure = PriceMeasure.POST_TRADE,
    ) = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal(minMarketCapUsd), 4500, 1, BigDecimal("0.001"),
        priceMeasure = priceMeasure, minSignalSol = minSignalSol?.let(::BigDecimal))

    private fun intent(config: SimulationConfig, vararg observations: MarketObservation, trade: WalletTradeObserved = buy): TradeIntent =
        DipEntryStrategy().plan(listOf(trade), "run", "hash", config, OrderedMarketData(observations.toList()), rates).intents.single()

    @Test
    fun `a buy paying exactly the minimum with its fees is no signal, decided before the market cap`() {
        PriceMeasure.entries.forEach { measure ->
            val intent = intent(config(minMarketCapUsd = "1000000000", priceMeasure = measure), buyObservation(), dip)

            assertEquals(DipWatchOutcome.SIGNAL_BELOW_MIN_SIZE, intent.watch, measure.name)
            assertEquals(BigInteger.valueOf(1_900_000_000), intent.signalLamports)
            assertNull(intent.trigger)
        }
    }

    @Test
    fun `one lamport more is a signal and goes on to the market cap check`() {
        val oneMore = buyObservation(AT_MINIMUM.add(BigInteger.ONE))

        val belowCap = intent(config(minMarketCapUsd = "1000000000"), oneMore, dip)
        val entered = intent(config(), oneMore, dip)

        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, belowCap.watch)
        assertEquals(BigInteger.valueOf(1_900_000_001), belowCap.signalLamports)
        assertEquals(DipWatchOutcome.TRIGGERED, entered.watch)
        assertEquals(dip.id, entered.trigger?.observationId)
        assertEquals(BigInteger.valueOf(1_900_000_001), entered.signalLamports)
    }

    @Test
    fun `without exactly one eligible buy observation recording its trade the size is unavailable, with no fallback to the wallet trade`() {
        val second = buyObservation(id = "test:1000:1")
        listOf(listOf(), listOf(buyObservation(), second), listOf(buyObservation().copy(trade = null)),
            listOf(buyObservation().copy(quality = ObservationQuality.INVALID))).forEach { candidates ->
            val intent = intent(config(priceMeasure = PriceMeasure.TRADE_AVERAGE), *(candidates + dip).toTypedArray())

            assertEquals(DipWatchOutcome.SIGNAL_SIZE_UNAVAILABLE, intent.watch, "$candidates")
            assertNull(intent.signalLamports)
            assertNull(intent.trigger)
        }
    }

    @Test
    fun `under the post-trade measure a missing buy observation leaves the reference unavailable before the size is checked`() {
        listOf(listOf(), listOf(buyObservation(), buyObservation(id = "test:1000:1"))).forEach { candidates ->
            assertEquals(DipWatchOutcome.REFERENCE_UNAVAILABLE, intent(config(), *(candidates + dip).toTypedArray()).watch, "$candidates")
        }
        assertEquals(DipWatchOutcome.SIGNAL_SIZE_UNAVAILABLE, intent(config(), buyObservation().copy(trade = null), dip).watch)
    }

    @Test
    fun `an unsupported buy is refused before it is sized`() {
        val intent = intent(config(), buyObservation(), dip, trade = buy.copy(source = "other-dex/v1"))

        assertEquals(DipWatchOutcome.UNSUPPORTED_SUPPLY, intent.watch)
    }

    @Test
    fun `without a minimum no buy is sized and the outcomes are those of dip-paper-v5`() {
        PriceMeasure.entries.forEach { measure ->
            val small = intent(config(minSignalSol = null, priceMeasure = measure), buyObservation(BigInteger.ONE), dip)
            val unrecorded = intent(config(minSignalSol = null, priceMeasure = PriceMeasure.TRADE_AVERAGE), dip)

            assertEquals(DipWatchOutcome.TRIGGERED, small.watch, measure.name)
            assertNull(small.signalLamports)
            assertEquals(DipWatchOutcome.TRIGGERED, unrecorded.watch)
            assertNull(unrecorded.signalLamports)
        }
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val BUY_INDEX = 2

        /** 1,876,543,209 + 17,827,161 (95 bps) + 5,629,630 (30 bps) = 1,900,000,000 lamports. */
        val AT_MINIMUM: BigInteger = BigInteger.valueOf(1_876_543_209)
    }
}
