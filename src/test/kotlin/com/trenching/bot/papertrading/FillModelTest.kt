package com.trenching.bot.papertrading

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.application.FillModel
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class FillModelTest {
    private val dataset = DipSample.load()
    private val config = SimulationConfig.mvp()

    /** Triggered at slot 1002 with a horizon ending at slot 5500; its observations are replaced per test. */
    private val intent = ReplayRunner().run(dataset, config).scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.intent

    /** Post-trade curve of `fixture:TP:1003:0`: spot 0.000000086, protocol fee 95 bps and creator fee 30 bps. */
    private val template = dataset.observations.single { it.id == "fixture:TP:1003:0" }

    /** About 40 SOL of virtual reserves at 0.00000005 SOL per token: a $10,000 market cap at 200 USDT per SOL. */
    private val tenThousandDollarCurve = curve(40_000_000_000, 800_000_000_000_000, 10_000_000_000, 520_100_000_000_000)

    private fun curve(virtualSol: Long, virtualToken: Long, realSol: Long, realToken: Long, feeBps: Int = 95, creatorFeeBps: Int = 30) =
        CurveState(CurveReserves(BigInteger.valueOf(virtualSol), BigInteger.valueOf(virtualToken), BigInteger.valueOf(realSol),
            BigInteger.valueOf(realToken)), feeBps, creatorFeeBps)

    private fun observation(slot: Long, n: Int = 0, curve: CurveState = template.curve, curveComplete: Boolean = false): MarketObservation =
        template.copy(id = "test:$slot:$n", sourceEventId = "test:$slot:$n", slot = Slot.of(slot), priceSlot = Slot.of(slot),
            curve = curve, curveComplete = curveComplete)

    private fun config(notional: String = "0.1", latencySlots: Long = 1, transactionCostSol: String = "0.001") =
        SimulationConfig.of(BigDecimal(notional), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, latencySlots, BigDecimal(transactionCostSol))

    private fun outcome(vararg observations: MarketObservation, config: SimulationConfig = this.config) =
        FillModel().fill(intent.copy(notional = config.notional), OrderedMarketData(observations.toList()), config)

    private fun fill(vararg observations: MarketObservation, config: SimulationConfig = this.config): PaperFill =
        assertIs<FillOutcome.Filled>(outcome(*observations, config = config)).fill

    private fun rejected(vararg observations: MarketObservation, config: SimulationConfig = this.config): RejectionReason =
        assertIs<FillOutcome.Rejected>(outcome(*observations, config = config)).reason

    @Test
    fun `buys against the fill trade's post-trade curve, paying its fees and the transaction cost on top of the notional`() {
        val fill = fill(observation(1003))

        assertEquals(Slot.of(1003), fill.actualSlot)
        assertEquals("test:1003:0", fill.observationId)
        assertEquals(0, BigDecimal("0.00000008616345143543821057475551350438").compareTo(fill.executionPrice))
        assertEquals("1160584.892249000000", fill.quantity.toPlainString())
        assertEquals("0.001250000000", fill.fee.toPlainString())
        assertEquals(125, fill.feeBps)
        assertEquals(0, fill.slippageBps)
        assertEquals("0.001000000000", fill.priorityFee.toPlainString())
        assertEquals(19, fill.priceImpactBps)
        assertEquals("0.102250000000", fill.costBasis.toPlainString())
    }

    @Test
    fun `0,1 SOL against 40 SOL of virtual reserves moves the price 25 bps`() {
        assertEquals(25, fill(observation(1003, curve = tenThousandDollarCurve)).priceImpactBps)
    }

    @Test
    fun `fees use the fill trade's basis points, each rounded up to a whole lamport`() {
        val fill = fill(observation(1003, curve = tenThousandDollarCurve.copy(feeBps = 100, creatorFeeBps = 5)))
        assertEquals("0.001050000000", fill.fee.toPlainString())
        assertEquals(105, fill.feeBps)

        val oneLamport = fill(observation(1003), config = config(notional = "0.000000001"))
        assertEquals("0.000000002000", oneLamport.fee.toPlainString())
    }

    @Test
    fun `the cost basis includes the configured transaction cost`() {
        val fill = fill(observation(1003), config = config(transactionCostSol = "0.005"))
        assertEquals("0.005000000000", fill.priorityFee.toPlainString())
        assertEquals("0.106250000000", fill.costBasis.toPlainString())
    }

    @Test
    fun `latency picks the first eligible trade at least that many slots after the trigger`() {
        val observations = arrayOf(observation(1002, n = 5), observation(1003), observation(1004), observation(1005))
        assertEquals(Slot.of(1003), fill(*observations).actualSlot)
        assertEquals(Slot.of(1005), fill(*observations, config = config(latencySlots = 3)).actualSlot)
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER, rejected(observation(1002, n = 5), observation(1004), config = config(latencySlots = 3)))
    }

    @Test
    fun `rejects an entry when the curve completes during its latency wait`() {
        val complete = curve(52_615_016_868, 611_802_521_716_526, 22_615_016_868, 0)
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER,
            rejected(observation(1003, curve = complete, curveComplete = true), observation(1005), config = config(latencySlots = 3)))
    }

    @Test
    fun `rejects an entry when a later trade in its trigger slot completes the curve`() {
        val complete = curve(52_615_016_868, 611_802_521_716_526, 22_615_016_868, 0)
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER,
            rejected(observation(1002), observation(1002, n = 1, curve = complete, curveComplete = true), observation(1005),
                config = config(latencySlots = 3)))
    }

    @Test
    fun `a 100 SOL entry near a 10,000 dollar market cap exceeds the curve and is never moved to a later trade`() {
        val roomy = curve(40_000_000_000, 800_000_000_000_000, 10_000_000_000, 800_000_000_000_000)
        assertEquals(RejectionReason.EXCEEDS_CURVE,
            rejected(observation(1003, curve = tenThousandDollarCurve), observation(1004, curve = roomy), config = config(notional = "100")))
    }

    @Test
    fun `an entry too small to buy one raw token unit has zero quantity`() {
        val expensive = curve(1_000_000_000_000_000_000, 1_000_000, 0, 1_000_000)
        assertEquals(RejectionReason.ZERO_QUANTITY, rejected(observation(1003, curve = expensive)))
    }

    @Test
    fun `a known liquidity quote no longer limits the entry`() {
        assertEquals(Slot.of(1003), fill(observation(1003).copy(liquidityQuote = BigDecimal("0.000001"))).actualSlot)
    }

    @Test
    fun `skips ineligible trades and never fills after the curve completes`() {
        val invalid = observation(1003).copy(quality = ObservationQuality.INVALID)
        assertEquals(Slot.of(1004), fill(invalid, observation(1004)).actualSlot)
        val complete = curve(52_615_016_868, 611_802_521_716_526, 22_615_016_868, 0)
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER, rejected(observation(1003, curve = complete, curveComplete = true), observation(1004)))
    }

    @Test
    fun `never fills after the horizon`() {
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER, rejected(observation(5501)))
        assertEquals(Slot.of(5500), fill(observation(5500)).actualSlot)
    }

    @Test
    fun `an intent without a trigger is rejected with its watch outcome`() {
        mapOf(
            DipWatchOutcome.NO_DIP to RejectionReason.NO_DIP,
            DipWatchOutcome.DIP_BELOW_MIN_MARKET_CAP to RejectionReason.DIP_BELOW_MIN_MARKET_CAP,
            DipWatchOutcome.MIGRATED_BEFORE_ENTRY to RejectionReason.MIGRATED_BEFORE_ENTRY,
            DipWatchOutcome.UNSUPPORTED_SUPPLY to RejectionReason.UNSUPPORTED_SUPPLY,
        ).forEach { (watch, reason) ->
            val outcome = FillModel().fill(intent.copy(watch = watch, trigger = null), OrderedMarketData(listOf(observation(1003))), config)
            assertEquals(reason, assertIs<FillOutcome.Rejected>(outcome).reason)
        }
    }

    @Test
    fun `exceeding the curve is an entry rejection`() {
        assertEquals(true, RejectionReason.EXCEEDS_CURVE in RejectionReason.ENTRY)
    }
}
