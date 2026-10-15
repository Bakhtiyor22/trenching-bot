package com.trenching.bot.replay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.CutoffDecision
import com.trenching.bot.marketdata.CutoffIdentity
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.DipOutcomeCutoff
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class DipOutcomeCutoffTest {
    private val sample = DipSampleV4.dataset()
    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(DipOutcomeCutoff::class.java) as Logger

    @BeforeEach fun attachAppender() { logs.start(); logger.addAppender(logs) }
    @AfterEach fun detachAppender() { logger.detachAppender(logs) }

    @Test
    fun `a take profit is decided at its close slot once the prefix reaches it and not before`() {
        val cutoff = cutoff(DipSample.TAKE_PROFIT)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, 1005))
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, 1006))
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, 1300))
    }

    @Test
    fun `a stop loss is decided at its close slot`() {
        assertEquals(CutoffDecision.Decided(Slot.of(2005), "STOP_LOSS"), evaluate(cutoff(DipSample.STOP_LOSS), DipSample.STOP_LOSS, 2400))
    }

    @Test
    fun `a migration before the entry is decided at the first completed curve`() {
        val cutoff = cutoff(DipSample.MIGRATED_EARLY)

        assertEquals(CutoffDecision.Decided(Slot.of(5001), "MIGRATED_BEFORE_ENTRY"), evaluate(cutoff, DipSample.MIGRATED_EARLY, 5001))
    }

    @Test
    fun `an unsupported supply is decided at the buy slot from an empty prefix without asking for a rate`() {
        val rates = FakeRates()
        val window = window(DipSample.UNSUPPORTED_SUPPLY)

        val decision = cutoff(DipSample.UNSUPPORTED_SUPPLY, rates).evaluate(window, emptyList(), Slot.of(window.fromSlot.value - 1))

        assertEquals(CutoffDecision.Decided(Slot.of(9500), "UNSUPPORTED_SUPPLY"), decision)
        assertEquals(emptyList(), rates.requests)
    }

    @Test
    fun `no dip stays undecided until the prefix reaches the horizon end`() {
        val cutoff = cutoff(DipSample.NO_DIP)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.NO_DIP, 3002))
        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.NO_DIP, 7499))
        assertEquals(CutoffDecision.Decided(Slot.of(7500), "NO_DIP"), evaluate(cutoff, DipSample.NO_DIP, 7500))
    }

    @Test
    fun `a minute without a rate cuts the prefix before its first trade and only that minute is asked for again`() {
        val laterMinute = Instant.parse("2026-10-01T00:01:00Z")
        val observations = prefix(DipSample.TAKE_PROFIT, 1006).map { if (it.slot.value >= 1005) it.copy(observedTime = laterMinute) else it }
        val rates = FakeRates(mapOf(FIRST_MINUTE to BigDecimal("200")))
        val cutoff = cutoff(DipSample.TAKE_PROFIT, rates)
        val window = window(DipSample.TAKE_PROFIT)

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, observations, Slot.of(1006)))
        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, observations, Slot.of(1006)))
        rates.available = mapOf(FIRST_MINUTE to BigDecimal("200"), laterMinute to BigDecimal("150"))
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), cutoff.evaluate(window, observations, Slot.of(1006)))

        assertEquals(listOf(setOf(FIRST_MINUTE, laterMinute), setOf(laterMinute), setOf(laterMinute)), rates.requests)
        assertTrue(logs.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a trigger in a minute without a rate is never replayed`() {
        val unrated = prefix(DipSample.TAKE_PROFIT, 1006).map { it.copy(observedTime = Instant.parse("2026-10-01T00:05:00Z")) }

        val decision = cutoff(DipSample.TAKE_PROFIT).evaluate(window(DipSample.TAKE_PROFIT), unrated, Slot.of(1006))

        assertEquals(CutoffDecision.Undecided, decision)
        assertTrue(logs.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a failed rate lookup leaves the window undecided and the next evaluation asks again`() {
        val rates = FakeRates().apply { failure = IllegalStateException("rate source down") }
        val cutoff = cutoff(DipSample.TAKE_PROFIT, rates)

        assertEquals(CutoffDecision.Undecided, evaluate(cutoff, DipSample.TAKE_PROFIT, 1006))
        rates.failure = null
        assertEquals(CutoffDecision.Decided(Slot.of(1006), "TAKE_PROFIT"), evaluate(cutoff, DipSample.TAKE_PROFIT, 1006))
        assertEquals(listOf(setOf(FIRST_MINUTE), setOf(FIRST_MINUTE)), rates.requests)
    }

    @Test
    fun `a cycle whose buy is not the window's anchor is undecided`() {
        val laterBuy = sample.transactions.filter { it.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.maxBy { it.slot.value }
        val first = buy(DipSample.TAKE_PROFIT)
        val window = MarketCaptureWindow.of(DipSample.mint(DipSample.TAKE_PROFIT), first.slot.plus(1), first.slot.plus(HORIZON),
            laterBuy.signature)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(first)), FakeRates())

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, prefix(DipSample.TAKE_PROFIT, 1006), Slot.of(1006)))
    }

    @Test
    fun `a replay failure is logged as an error and leaves the window undecided`() {
        val broken = buy(DipSample.TAKE_PROFIT).copy(source = " ")
        val window = window(DipSample.TAKE_PROFIT)
        val cutoff = DipOutcomeCutoff(sample.trackedWallet, mapOf(window to listOf(broken)), FakeRates())

        assertEquals(CutoffDecision.Undecided, cutoff.evaluate(window, prefix(DipSample.TAKE_PROFIT, 1006), Slot.of(1006)))
        val error = logs.list.single { it.level == Level.ERROR }
        assertTrue(error.formattedMessage.startsWith("market_capture cutoff replay_failed"), error.formattedMessage)
        assertTrue(error.throwableProxy != null)
    }

    @Test
    fun `its identity is the engine version and the configuration hash of the mvp replay`() {
        val report = ReplayRunner().run(sample, SimulationConfig.mvp())

        assertEquals(CutoffIdentity(ReplayRunner.ENGINE_VERSION, report.configurationHash), DipOutcomeCutoff.IDENTITY)
    }

    private fun evaluate(cutoff: DipOutcomeCutoff, cycle: Int, through: Long): CutoffDecision =
        cutoff.evaluate(window(cycle), prefix(cycle, through), Slot.of(through))

    private fun cutoff(cycle: Int, rates: SolUsdtRateSource = FakeRates()) =
        DipOutcomeCutoff(sample.trackedWallet, mapOf(window(cycle) to listOf(buy(cycle))), rates)

    private fun buy(cycle: Int): WalletTransaction = sample.transactions.filter { it.pair == DipSample.pair(cycle) }.minBy { it.slot.value }

    private fun window(cycle: Int): MarketCaptureWindow = buy(cycle).let {
        MarketCaptureWindow.of(DipSample.mint(cycle), it.slot.plus(1), it.slot.plus(HORIZON), it.signature)
    }

    /** The cycle's trades in `[fromSlot, through]`, in the canonical order the capture hands them over. */
    private fun prefix(cycle: Int, through: Long): List<MarketObservation> {
        val window = window(cycle)
        return OrderedMarketData.canonical(sample.observations.filter { it.pair == window.pair && it.slot >= window.fromSlot && it.slot.value <= through })
    }

    /** Answers each minute from [available], recording every request; [failure] makes the next requests throw. */
    private inner class FakeRates(var available: Map<Instant, BigDecimal> = sample.referenceRates.associate { it.minute to it.rate }) :
        SolUsdtRateSource {
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
        val FIRST_MINUTE: Instant = ReferenceRate.minuteOf(Instant.parse("2026-10-01T00:00:00Z"))
    }
}
