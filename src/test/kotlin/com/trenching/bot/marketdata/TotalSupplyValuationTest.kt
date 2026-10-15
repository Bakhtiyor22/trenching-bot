package com.trenching.bot.marketdata

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TotalSupplyValuation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.marketdata.domain.ValuationOutcome
import com.trenching.bot.marketdata.domain.ValuationUnavailableReason
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class TotalSupplyValuationTest {
    private val rate = BigDecimal("150.5")

    @Test
    fun `price times rate times the bonding curve supply at the policy scale`() {
        val valuation = TotalSupplyValuation.of(observation(price = BigDecimal("0.00000002")), rate)

        assertEquals("3010.000000000000", (valuation as ValuationOutcome.Valued).usdt.toPlainString())
    }

    @Test
    fun `the valuation rounds half even at the policy scale`() {
        val tie = TotalSupplyValuation.of(observation(price = BigDecimal("0.000000000000000000001")), BigDecimal("2.5"))

        assertEquals("0.000000000002", (tie as ValuationOutcome.Valued).usdt.toPlainString())
    }

    @Test
    fun `it equals the market cap the dip strategy judged for the sample's trigger`() {
        val report = ReplayRunner().run(DipSample.load(), SimulationConfig.mvp())
        val dataset = DipSample.load()
        val triggers = report.scenarios.mapNotNull { it.intent.trigger }
        assertEquals(true, triggers.isNotEmpty())

        triggers.forEach { trigger ->
            val observation = dataset.observations.single { it.id == trigger.observationId }
            val valuation = TotalSupplyValuation.of(observation, trigger.rate)
            assertEquals(trigger.marketCapUsd, (valuation as ValuationOutcome.Valued).usdt, trigger.observationId)
        }
    }

    @Test
    fun `an observation quoted in another token is not SOL quoted`() {
        val other = MarketPair.of(MintAddress.of(MINT), MintAddress.of(OTHER_MINT))

        assertEquals(unavailable(ValuationUnavailableReason.NOT_SOL_QUOTED), TotalSupplyValuation.of(observation(pair = other), rate))
    }

    @Test
    fun `an observation from another source is an unsupported token`() {
        assertEquals(unavailable(ValuationUnavailableReason.UNSUPPORTED_TOKEN),
            TotalSupplyValuation.of(observation(source = "pumpswap-trade/v1"), rate))
    }

    @Test
    fun `a trade whose price does not follow from its amounts under six decimals is a decimals mismatch`() {
        val ninePlaces = BondingCurve.price(LAMPORTS, TOKENS, 9)

        assertEquals(unavailable(ValuationUnavailableReason.DECIMALS_MISMATCH),
            TotalSupplyValuation.of(observation(price = ninePlaces, trade = trade()), rate))
    }

    @Test
    fun `a trade whose price follows from its amounts is valued`() {
        val price = BondingCurve.price(LAMPORTS, TOKENS)

        val valuation = TotalSupplyValuation.of(observation(price = price, trade = trade()), rate)

        val expected = DecimalPolicy.scale(price.multiply(rate).multiply(BigDecimal.valueOf(PumpFunLayout.TOKEN_SUPPLY)))
        assertEquals(expected, assertNotNull(valuation as? ValuationOutcome.Valued).usdt)
    }

    private fun unavailable(reason: ValuationUnavailableReason) = ValuationOutcome.Unavailable(reason)

    private fun trade() = ObservedTrade.of(TRADER, TradeSide.BUY, LAMPORTS, TOKENS)

    private fun observation(
        price: BigDecimal = BigDecimal("0.00000002"),
        pair: MarketPair = MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL),
        source: String = PumpFunLayout.TRADE_SOURCE,
        trade: ObservedTrade? = null,
    ) = MarketObservation("id", pair, Slot.of(1), Slot.of(1), Instant.parse("2026-10-15T10:00:30Z"), price, null, source, "event",
        Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false,
        CurveState(CurveReserves(BigInteger.TEN, BigInteger.TEN, BigInteger.ONE, BigInteger.ONE), 0, 0), trade)

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye"
        const val TRADER = "HhnetdgMGNq5nYHJ8ejPDeB6Mk6C1jTGiuofDYN3eWWR"
        val LAMPORTS: BigInteger = BigInteger.valueOf(2_500_000_000)
        val TOKENS: BigInteger = BigInteger("12345678901234")
    }
}
