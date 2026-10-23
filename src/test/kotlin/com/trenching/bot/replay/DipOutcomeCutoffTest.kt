package com.trenching.bot.replay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.CutoffDecision
import com.trenching.bot.marketdata.CutoffIdentity
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.DipOutcomeCutoff
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The cutoff under `SimulationConfig.agreed()` on the sample's cycles. Every sample trade lands at its buy's block time; the
 * take-profit cycle is reshaped to the agreed levels: its dip trigger at slot 1002 is at the 20% level, the fill is slot 1003's
 * curve, and slot 1005 crosses +55%, so the sale is into slot 1006. Trades placed later are copies at a chosen time.
 */
class DipOutcomeCutoffTest {
    private val sample = DipSampleV4.dataset().let { it.copy(observations = it.observations.map(::agreedLevels)) }
    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(DipOutcomeCutoff::class.java) as Logger
    private val timeReads = mutableListOf<Slot>()

    @BeforeEach fun attachAppender() { logs.start(); logger.addAppender(logs) }
    @AfterEach fun detachAppender() { logger.detachAppender(logs) }

    @Test
    fun `a take profit decided within the prefix waits until the recording reaches four minutes after the buy`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)
        val observations = prefix(DipSample.TAKE_PROFIT, 1006)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1400, T0.plusSeconds(239)))
        assertEquals(CutoffDecision.Decided(Slot.of(1400), "TAKE_PROFIT"),
            evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1400, T0.plus(ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING)))
        assertEquals(listOf(Slot.of(1400), Slot.of(1400)), timeReads)
    }

    @Test
    fun `a take profit is never decided before its close, whatever the recorded time`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, prefix(DipSample.TAKE_PROFIT, 1005), 1005, LATE))
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"),
            evaluate(cutoff, DipSample.TAKE_PROFIT, prefix(DipSample.TAKE_PROFIT, 1006), 1006, LATE))
    }

    @Test
    fun `the first trade four minutes after the buy proves the recording without reading its time`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)
        val observations = prefix(DipSample.TAKE_PROFIT, 1006) + tradeAt(DipSample.TAKE_PROFIT, 1300, 240) + tradeAt(DipSample.TAKE_PROFIT, 1350, 300)

        val decision = evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1400, null)

        assertEquals(CutoffDecision.Decided(Slot.of(1300), "TAKE_PROFIT"), decision)
        assertEquals(emptyList(), timeReads)
    }

    @Test
    fun `a block time that cannot be read leaves a decided outcome undecided`() {
        val decision = evaluate(cutoff(DipSample.TAKE_PROFIT), DipSample.TAKE_PROFIT, prefix(DipSample.TAKE_PROFIT, 1006), 1400, null)

        assertEquals(CutoffDecision.Undecided, decision)
        assertEquals(listOf(Slot.of(1400)), timeReads)
    }

    @Test
    fun `a buy below the minimum market cap is skipped at the buy and still recorded for four minutes`() {
        val cutoff = cutoff(DipSample.LOW_CAP)
        val observations = prefix(DipSample.LOW_CAP, 4001)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.LOW_CAP, observations, 4001, T0.plusSeconds(10)))
        assertEquals(CutoffDecision.Decided(Slot.of(4700), "REFERENCE_BELOW_MIN_MARKET_CAP"),
            evaluate(cutoff, DipSample.LOW_CAP, observations, 4700, T0.plusSeconds(240)))
    }

    @Test
    fun `an unsupported supply at the buy with an empty prefix stays undecided without asking for a rate`() {
        val rates = FakeRates()
        val window = window(DipSample.UNSUPPORTED_SUPPLY)
        val buyTime = buy(DipSample.UNSUPPORTED_SUPPLY).blockTime

        val decision = cutoff(DipSample.UNSUPPORTED_SUPPLY, rates).evaluate(window, emptyList(), Slot.of(window.fromSlot.value - 1)) {
            timeReads += Slot.of(window.fromSlot.value - 1)
            buyTime
        }

        assertEquals(CutoffDecision.Undecided, decision)
        assertEquals(emptyList(), rates.requests)
        assertEquals(1, timeReads.size)
    }

    @Test
    fun `a no dip after the dip window is decided only at the first trade four minutes after the buy`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)
        val missed = retimed(prefix(DipSample.TAKE_PROFIT, 1006), mapOf(1001L to 10, 1002L to 31, 1003L to 32, 1004L to 40, 1005L to 50, 1006L to 60))

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, missed, 1500, T0.plusSeconds(200)))
        assertEquals(CutoffDecision.Decided(Slot.of(1600), "NO_DIP"),
            evaluate(cutoff, DipSample.TAKE_PROFIT, missed + tradeAt(DipSample.TAKE_PROFIT, 1600, 240), 1700, null))
    }

    @Test
    fun `a time exit on a token that stopped trading is decided by the looked-up time once it reaches four minutes`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)
        val stopped = prefix(DipSample.TAKE_PROFIT, 1004)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, stopped, 2000, null))
        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, stopped, 2000, T0.plusSeconds(179)))
        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, stopped, 2000, T0.plusSeconds(239)))
        assertEquals(CutoffDecision.Decided(Slot.of(2000), "TIME_EXIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, stopped, 2000, T0.plusSeconds(240)))
    }

    @Test
    fun `the time is not read while the dip watch is still open`() {
        val untriggered = DipOutcomeCutoff(sample.trackedWallet, mapOf(window(DipSample.NO_DIP) to listOf(buy(DipSample.NO_DIP))), FakeRates())

        val decision = evaluate(untriggered, DipSample.NO_DIP, prefix(DipSample.NO_DIP, 3002), 3500, LATE)

        assertEquals(CutoffDecision.Undecided, decision)
        assertEquals(emptyList(), timeReads)
    }

    @Test
    fun `a buy without a block time is never decided early and its time is never read`() {
        val untimed = buy(DipSample.TAKE_PROFIT).copy(blockTime = null)
        val window = window(DipSample.TAKE_PROFIT)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(untimed)), FakeRates())
        val observations = prefix(DipSample.TAKE_PROFIT, 1006) + tradeAt(DipSample.TAKE_PROFIT, 1300, 240)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1400, LATE))
        assertEquals(emptyList(), timeReads)
    }

    @Test
    fun `a lead window holding the buy's own slot and dip-priced lead trades is decided as without its lead`() {
        val window = leadWindow(DipSample.TAKE_PROFIT)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(buy(DipSample.TAKE_PROFIT))), FakeRates())
        val lead = leadTrades(DipSample.TAKE_PROFIT)

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, canonical(lead + prefix(DipSample.TAKE_PROFIT, 1005)), Slot.of(1005)) { LATE })
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"),
            cutoff.evaluate(window, canonical(lead + prefix(DipSample.TAKE_PROFIT, 1006)), Slot.of(1006)) { LATE })
    }

    @Test
    fun `a lead window skipped at its buy records coverage through the slot proving four minutes`() {
        val window = leadWindow(DipSample.UNSUPPORTED_SUPPLY)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(buy(DipSample.UNSUPPORTED_SUPPLY))), FakeRates())
        val buySlot = buy(DipSample.UNSUPPORTED_SUPPLY).slot.value

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, canonical(leadTrades(DipSample.UNSUPPORTED_SUPPLY)), Slot.of(buySlot)) { null })
        assertEquals(CutoffDecision.Decided(Slot.of(buySlot + 700), "UNSUPPORTED_SUPPLY"),
            cutoff.evaluate(window, canonical(leadTrades(DipSample.UNSUPPORTED_SUPPLY)), Slot.of(buySlot + 700)) { LATE })
    }

    @Test
    fun `a minute without a rate cuts the prefix before its first trade, so the time of a later slot is never used`() {
        val laterMinute = Instant.parse("2026-10-01T00:01:00Z")
        val observations = prefix(DipSample.TAKE_PROFIT, 1006).map { if (it.slot.value >= 1005) it.copy(observedTime = laterMinute) else it }
        val rates = FakeRates(mapOf(FIRST_MINUTE to BigDecimal("200")))
        val cutoff = cutoff(DipSample.TAKE_PROFIT, rates)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1006, LATE))
        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1006, LATE))
        assertEquals(emptyList(), timeReads)
        rates.available = mapOf(FIRST_MINUTE to BigDecimal("200"), laterMinute to BigDecimal("150"))
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1006, LATE))

        assertEquals(listOf(setOf(FIRST_MINUTE, laterMinute), setOf(laterMinute), setOf(laterMinute)), rates.requests)
        assertTrue(logs.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a trigger in a minute without a rate is never replayed`() {
        val unrated = prefix(DipSample.TAKE_PROFIT, 1006).map { it.copy(observedTime = Instant.parse("2026-10-01T00:09:00Z")) }

        val decision = evaluate(cutoff(DipSample.TAKE_PROFIT), DipSample.TAKE_PROFIT, unrated, 1006, LATE)

        assertEquals(CutoffDecision.Undecided, decision)
        assertTrue(logs.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a failed rate lookup leaves the window undecided and the next evaluation asks again`() {
        val rates = FakeRates().apply { failure = IllegalStateException("rate source down") }
        val cutoff = cutoff(DipSample.TAKE_PROFIT, rates)
        val observations = prefix(DipSample.TAKE_PROFIT, 1006)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1006, LATE))
        rates.failure = null
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, observations, 1006, LATE))
        assertEquals(listOf(setOf(FIRST_MINUTE), setOf(FIRST_MINUTE)), rates.requests)
    }

    @Test
    fun `a cycle whose buy is not the window's anchor is undecided`() {
        val laterBuy = sample.transactions.filter { it.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.maxBy { it.slot.value }
        val first = buy(DipSample.TAKE_PROFIT)
        val window = MarketCaptureWindow.of(DipSample.mint(DipSample.TAKE_PROFIT), first.slot.plus(1), first.slot.plus(HORIZON),
            laterBuy.signature)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(first)), FakeRates())

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, prefix(DipSample.TAKE_PROFIT, 1006), Slot.of(1006)) { LATE })
    }

    @Test
    fun `a replay failure is logged as an error and leaves the window undecided`() {
        val broken = buy(DipSample.TAKE_PROFIT).copy(source = " ")
        val window = window(DipSample.TAKE_PROFIT)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(broken)), FakeRates())

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, prefix(DipSample.TAKE_PROFIT, 1006), Slot.of(1006)) { LATE })
        val error = logs.list.single { it.level == Level.ERROR }
        assertTrue(error.formattedMessage.startsWith("market_capture cutoff replay_failed"), error.formattedMessage)
        assertTrue(error.throwableProxy != null)
    }

    @Test
    fun `its identity is engine dip-paper-v4 and the configuration hash of the agreed replay`() {
        val report = ReplayRunner().run(sample, SimulationConfig.agreed())

        assertEquals(CutoffIdentity("dip-paper-v4", report.configurationHash), DipOutcomeCutoff.IDENTITY)
        assertEquals(Duration.ofMinutes(4), ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING)
    }

    private fun evaluate(cutoff: DipOutcomeCutoff, cycle: Int, observations: List<MarketObservation>, through: Long, time: Instant?): CutoffDecision =
        cutoff.evaluate(window(cycle), canonical(observations), Slot.of(through)) {
            timeReads += Slot.of(through)
            time
        }

    private fun cutoff(cycle: Int, rates: SolUsdtRateSource = FakeRates()) =
        DipOutcomeCutoff(sample.trackedWallet, mapOf(window(cycle) to listOf(buy(cycle))), rates)

    private fun buy(cycle: Int): WalletTransaction = sample.transactions.filter { it.pair == DipSample.pair(cycle) }.minBy { it.slot.value }

    private fun window(cycle: Int): MarketCaptureWindow = buy(cycle).let {
        MarketCaptureWindow.of(DipSample.mint(cycle), it.slot.plus(1), it.slot.plus(HORIZON), it.signature)
    }

    private fun leadWindow(cycle: Int): MarketCaptureWindow = buy(cycle).let {
        MarketCaptureWindow.forFirstBuy(DipSample.mint(cycle), it.slot, it.signature,
            MarketCaptureWindowParameters(HORIZON, 2, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS))
    }

    /** Copies of the take-profit cycle's agreed dip trigger in the lead `S - 50`, `S - 1` and twice in the buy's slot S. */
    private fun leadTrades(cycle: Int): List<MarketObservation> {
        val trigger = ReplayRunner().run(sample, SimulationConfig.agreed()).scenarios
            .single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.intent.trigger
        val dip = sample.observations.single { it.id == trigger?.observationId }
        val buySlot = buy(cycle).slot.value
        return listOf(buySlot - 50, buySlot - 1, buySlot, buySlot).mapIndexed { index, slot ->
            dip.copy(id = "lead:$cycle:$index", sourceEventId = "lead:$cycle:$index", pair = DipSample.pair(cycle), slot = Slot.of(slot),
                priceSlot = Slot.of(slot), observedTime = buy(cycle).blockTime ?: dip.observedTime)
        }
    }

    /** A copy of the cycle's first trade at [slot], [seconds] after the buy, at the buy's own price. */
    private fun tradeAt(cycle: Int, slot: Long, seconds: Long): MarketObservation {
        val first = prefix(cycle, Long.MAX_VALUE).first()
        return first.copy(id = "later:$cycle:$slot", sourceEventId = "later:$cycle:$slot", slot = Slot.of(slot), priceSlot = Slot.of(slot),
            observedTime = T0.plusSeconds(seconds))
    }

    private fun retimed(observations: List<MarketObservation>, seconds: Map<Long, Long>): List<MarketObservation> =
        observations.map { observation -> seconds[observation.slot.value]?.let { observation.copy(observedTime = T0.plusSeconds(it)) } ?: observation }

    private fun canonical(observations: List<MarketObservation>) = OrderedMarketData.canonical(observations)

    /** The cycle's trades in `[fromSlot, through]`, in the canonical order the capture hands them over. */
    private fun prefix(cycle: Int, through: Long): List<MarketObservation> {
        val window = window(cycle)
        return canonical(sample.observations.filter { it.pair == window.pair && it.slot >= window.fromSlot && it.slot.value <= through })
    }

    private fun agreedLevels(observation: MarketObservation): MarketObservation =
        AGREED_PRICES[observation.id]?.let { observation.copy(price = BigDecimal(it)) } ?: observation

    /** Answers each minute from [available], recording every request; [failure] makes the next requests throw. */
    private inner class FakeRates(var available: Map<Instant, BigDecimal> = RATES) : SolUsdtRateSource {
        val requests = mutableListOf<Set<Instant>>()
        var failure: RuntimeException? = null

        override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> {
            requests += minutes
            failure?.let { throw it }
            return minutes.associateWith { minute ->
                available[minute]?.let { RateLookup.Available(it, minute, SOURCE) } ?: RateLookup.Unavailable(RateUnavailableReason.NO_MARKET_DATA)
            }
        }

        override fun current(): RateLookup = error("The cutoff never asks for the current price")
    }

    private companion object {
        const val HORIZON = 4500L
        const val SOURCE = "test-fixture:SOLUSDT:1m:open"
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val FIRST_MINUTE: Instant = ReferenceRate.minuteOf(T0)

        /** A coverage end time well past four minutes after every sample buy. */
        val LATE: Instant = T0.plus(Duration.ofMinutes(10))

        /** The sample's 200 and 150 USDT minutes, and 151 for each later minute through the sixth. */
        val RATES: Map<Instant, BigDecimal> = DipSample.load().referenceRates.associate { it.minute to it.rate } +
            (3L..5L).associate { T0.plus(Duration.ofMinutes(it)) to BigDecimal("151") }

        /** The take-profit cycle's dip at exactly 20% below P0 and its take profit above +55% of the entry's execution price. */
        val AGREED_PRICES = mapOf("fixture:TP:1002:0" to "0.00000008", "fixture:TP:1005:0" to "0.00000014")
    }
}
