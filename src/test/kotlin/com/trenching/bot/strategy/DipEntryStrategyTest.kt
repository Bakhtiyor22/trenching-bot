package com.trenching.bot.strategy

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.DecimalPolicy
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
import com.trenching.bot.strategy.domain.WalletSellSignal
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import com.trenching.bot.walletactivity.domain.TradeSide as WalletSide

/**
 * The wallet buys 1,000,000 tokens for 0.1 SOL at slot 1000 (average 1e-7), moving the curve to a post-trade spot of 1.1e-7.
 * Every curve here has 300M virtual tokens, so its spot is its virtual SOL / 3e17; at 200 USDT per SOL the buy is worth $20,000
 * on its average and $22,000 after it. A 15% dip is 0.85e-7 on the average and 0.935e-7 on the post-trade price.
 */
class DipEntryStrategyTest {
    private val wallet = WalletAddress.of(Base58.encode(ByteArray(32) { 7 }))
    private val otherTrader = Base58.encode(ByteArray(32) { 8 })
    private val pair = MarketPair.of(MintAddress.of(Base58.encode(ByteArray(32) { 5 })), MintAddress.WRAPPED_SOL)
    private val buySignature = signature(9)
    private val rates = OrderedReferenceRates(listOf(ReferenceRate(T0, BigDecimal("200"), "test")))

    private val buy = WalletTradeObserved(WalletEventIdentity.of(buySignature, BUY_INDEX), wallet, buySignature, BUY_INDEX, Slot.of(1000),
        T0, WalletSide.BUY, pair, TokenAmount.of(BigInteger.valueOf(1_000_000_000_000), PumpFunLayout.TOKEN_DECIMALS),
        TokenAmount.of(BigInteger.valueOf(100_000_000), PumpFunLayout.SOL_AMOUNT_DECIMALS), PumpFunLayout.BUY_SOURCE, Commitment.CONFIRMED)

    /** The wallet's buy as the market data records it: average 1e-7, post-trade spot 1.1e-7. */
    private val buyObservation = observation(1000, price = "0.0000001", virtualSol = 33_000_000_000,
        sourceEventId = "${buySignature.value}:$BUY_INDEX:-", trade = trade(wallet.value, TradeSide.BUY))

    /** Averages 0.95e-7 (above both dip levels) and leaves a post-trade spot of 0.92e-7 (below the post-trade one). */
    private val postTradeDip = observation(1003, price = "0.000000095", virtualSol = 27_600_000_000)

    private fun signature(byte: Int): TransactionSignature = TransactionSignature.of(Base58.encode(ByteArray(64) { byte.toByte() }))

    private fun trade(trader: String, side: TradeSide): ObservedTrade =
        ObservedTrade.of(trader, side, BigInteger.valueOf(1_000_000), BigInteger.valueOf(10_000_000_000))

    private fun observation(
        slot: Long,
        n: Int = 0,
        price: String = "0.0000001",
        virtualSol: Long = 30_000_000_000,
        sourceEventId: String = "test:$slot:$n",
        trade: ObservedTrade? = null,
        secondsAfterT0: Long = slot - 1000,
    ): MarketObservation = MarketObservation("test:$slot:$n", pair, Slot.of(slot), Slot.of(slot), T0.plusSeconds(secondsAfterT0),
        BigDecimal(price), null, PumpFunLayout.TRADE_SOURCE, sourceEventId, Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false,
        CurveState(CurveReserves(BigInteger.valueOf(virtualSol), BigInteger.valueOf(300_000_000_000_000), BigInteger.valueOf(1_000_000_000),
            BigInteger.valueOf(100_000_000_000_000)), 95, 30), trade)

    private fun walletSell(slot: Long, n: Int = 0, secondsAfterT0: Long = slot - 1000): MarketObservation =
        observation(slot, n, price = "0.0000001", trade = trade(wallet.value, TradeSide.SELL), secondsAfterT0 = secondsAfterT0)

    private fun config(
        priceMeasure: PriceMeasure = PriceMeasure.POST_TRADE,
        followWalletSells: Boolean = false,
        minMarketCapUsd: String = "10000",
        dipWindowMs: Long? = null,
    ) = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal(minMarketCapUsd), 4500, 1, BigDecimal("0.001"),
        dipWindowMs = dipWindowMs, priceMeasure = priceMeasure, followWalletSells = followWalletSells)

    private fun intent(
        config: SimulationConfig,
        vararg observations: MarketObservation,
        trades: List<WalletTradeObserved> = listOf(buy),
    ): TradeIntent = DipEntryStrategy().plan(trades, "run", "hash", config, OrderedMarketData(observations.toList()), rates).intents.single()

    private fun assertPrice(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(assertNotNull(actual)), "$actual")

    @Test
    fun `the post-trade reference is the spot price after the wallet's own buy observation`() {
        val intent = intent(config(), buyObservation, postTradeDip)

        assertPrice("0.00000011", intent.referencePrice)
        assertEquals(DecimalPolicy.PRICE_SCALE, intent.referencePrice.scale())
        assertPrice("0.0000000935", intent.dipPrice)
        assertEquals(buyObservation.id, intent.referenceObservationId)
        assertEquals(wallet, intent.wallet)
    }

    @Test
    fun `the reference is unavailable without exactly one matching buy observation`() {
        val secondMatch = buyObservation.copy(id = "test:1000:1")
        val otherWallet = buyObservation.copy(trade = trade(otherTrader, TradeSide.BUY))
        val otherIndex = buyObservation.copy(sourceEventId = "${buySignature.value}:${BUY_INDEX + 1}:-")
        val asSell = buyObservation.copy(trade = trade(wallet.value, TradeSide.SELL))
        val otherSignature = buyObservation.copy(sourceEventId = "${signature(10).value}:$BUY_INDEX:-")

        listOf(listOf(), listOf(buyObservation, secondMatch), listOf(otherWallet), listOf(otherIndex), listOf(asSell), listOf(otherSignature))
            .forEach { candidates ->
                val intent = intent(config(), *(candidates + postTradeDip).toTypedArray())

                assertEquals(DipWatchOutcome.REFERENCE_UNAVAILABLE, intent.watch, "$candidates")
                assertNull(intent.trigger)
                assertNull(intent.referenceObservationId)
            }
    }

    @Test
    fun `a matching buy observation that is not an eligible price leaves the reference unavailable`() {
        listOf(buyObservation.copy(quality = ObservationQuality.INVALID), buyObservation.copy(commitment = Commitment.PROCESSED),
            buyObservation.copy(priceSlot = Slot.of(997))).forEach { ineligible ->
            val intent = intent(config(), ineligible, postTradeDip)

            assertEquals(DipWatchOutcome.REFERENCE_UNAVAILABLE, intent.watch, ineligible.toString())
            assertNull(intent.referenceObservationId)
        }
    }

    @Test
    fun `an observation recorded without its trade matches on signature and instruction index alone`() {
        val intent = intent(config(), buyObservation.copy(trade = null), postTradeDip)

        assertEquals(buyObservation.id, intent.referenceObservationId)
        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
    }

    @Test
    fun `the minimum market cap is judged at the post-trade reference`() {
        val average = intent(config(PriceMeasure.TRADE_AVERAGE, minMarketCapUsd = "21000"), buyObservation, postTradeDip)
        val postTrade = intent(config(minMarketCapUsd = "21000"), buyObservation, postTradeDip)

        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, average.watch)
        assertEquals(DipWatchOutcome.TRIGGERED, postTrade.watch)
        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, intent(config(minMarketCapUsd = "22000"), buyObservation, postTradeDip).watch)
    }

    @Test
    fun `an unsupported buy is refused before its reference is looked for`() {
        val unsupported = buy.copy(source = "other-dex/v1")

        assertEquals(DipWatchOutcome.UNSUPPORTED_SUPPLY, intent(config(), postTradeDip, trades = listOf(unsupported)).watch)
    }

    @Test
    fun `a trade whose post-trade price meets the dip level triggers even when its average does not`() {
        val intent = intent(config(), buyObservation, postTradeDip)

        val trigger = assertNotNull(intent.trigger)
        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertEquals(postTradeDip.id, trigger.observationId)
        assertPrice("0.000000092", trigger.price)
        assertEquals("18400.000000000000", trigger.marketCapUsd.toPlainString())
        assertEquals(DipWatchOutcome.NO_DIP, intent(config(PriceMeasure.TRADE_AVERAGE), buyObservation, postTradeDip).watch)
    }

    @Test
    fun `a trade whose average meets the dip level but whose post-trade price does not never triggers`() {
        val averageDip = observation(1003, price = "0.000000093", virtualSol = 28_200_000_000)

        assertEquals(DipWatchOutcome.NO_DIP, intent(config(), buyObservation, averageDip).watch)
        assertEquals(DipWatchOutcome.TRIGGERED, intent(config(PriceMeasure.TRADE_AVERAGE), buyObservation,
            observation(1003, price = "0.000000085", virtualSol = 28_200_000_000)).watch)
    }

    @Test
    fun `the defaults keep the average reference and the average trigger and record no new provenance`() {
        val averageDip = observation(1003, price = "0.000000085", virtualSol = 28_200_000_000)

        val intent = intent(config(PriceMeasure.TRADE_AVERAGE), buyObservation, walletSell(1001), averageDip)

        assertPrice("0.0000001", intent.referencePrice)
        assertPrice("0.000000085", intent.dipPrice)
        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertPrice("0.000000085", intent.trigger?.price)
        assertEquals("17000.000000000000", intent.trigger?.marketCapUsd?.toPlainString())
        assertNull(intent.referenceObservationId)
        assertNull(intent.walletSell)
    }

    @Test
    fun `a wallet sell before the trigger ends the cycle as wallet sold at the sell`() {
        val sell = walletSell(1001)

        val intent = intent(config(followWalletSells = true), buyObservation, sell, postTradeDip)

        assertEquals(DipWatchOutcome.WALLET_SOLD, intent.watch)
        assertEquals(WalletSellSignal(sell.id, Slot.of(1001)), intent.walletSell)
        assertNull(intent.trigger)
    }

    @Test
    fun `a wallet sell counts even when it is not an eligible price`() {
        val sell = walletSell(1001).copy(quality = ObservationQuality.INVALID)

        val intent = intent(config(followWalletSells = true), buyObservation, sell, postTradeDip)

        assertEquals(DipWatchOutcome.WALLET_SOLD, intent.watch)
        assertEquals(WalletSellSignal(sell.id, Slot.of(1001)), intent.walletSell)
    }

    @Test
    fun `a wallet sell in the buy's own slot counts, but the buy itself never does`() {
        val sameSlot = walletSell(1000, n = 1)

        assertEquals(WalletSellSignal(sameSlot.id, Slot.of(1000)),
            intent(config(followWalletSells = true), buyObservation, sameSlot, postTradeDip).walletSell)
        assertEquals(DipWatchOutcome.TRIGGERED, intent(config(followWalletSells = true), buyObservation, postTradeDip).watch)
    }

    @Test
    fun `a wallet sell in the trigger's own slot blocks the entry whatever its order inside the slot`() {
        val before = walletSell(1003, n = -1)
        val after = walletSell(1003, n = 1)

        listOf(before, after).forEach { sell ->
            val intent = intent(config(followWalletSells = true), buyObservation, postTradeDip, sell)

            assertEquals(DipWatchOutcome.WALLET_SOLD, intent.watch, sell.id)
            assertEquals(WalletSellSignal(sell.id, Slot.of(1003)), intent.walletSell)
        }
    }

    @Test
    fun `a wallet sell after the trigger slot changes nothing`() {
        val intent = intent(config(followWalletSells = true), buyObservation, postTradeDip, walletSell(1004))

        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertNull(intent.walletSell)
    }

    @Test
    fun `a wallet sell after the dip window ended the watch changes nothing`() {
        val windowed = config(followWalletSells = true, dipWindowMs = 30_000)

        val intent = intent(windowed, buyObservation, observation(1001, secondsAfterT0 = 31), walletSell(1002, secondsAfterT0 = 32), postTradeDip)

        assertEquals(DipWatchOutcome.NO_DIP, intent.watch)
        assertNull(intent.walletSell)
    }

    @Test
    fun `another trader's sell never counts`() {
        val otherSell = observation(1001, trade = trade(otherTrader, TradeSide.SELL))

        val intent = intent(config(followWalletSells = true), buyObservation, otherSell, postTradeDip)

        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertNull(intent.walletSell)
    }

    @Test
    fun `a sell recorded only as a wallet trade is not read`() {
        val walletTradeSell = buy.copy(id = "sell", signature = signature(11), slot = Slot.of(1001), side = WalletSide.SELL)

        val intent = intent(config(followWalletSells = true), buyObservation, postTradeDip, trades = listOf(buy, walletTradeSell))

        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertNull(intent.walletSell)
    }

    @Test
    fun `without the wallet-sell rule the wallet's sells are ignored`() {
        val intent = intent(config(), buyObservation, walletSell(1001), postTradeDip)

        assertEquals(DipWatchOutcome.TRIGGERED, intent.watch)
        assertNull(intent.walletSell)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val BUY_INDEX = 2
    }
}
