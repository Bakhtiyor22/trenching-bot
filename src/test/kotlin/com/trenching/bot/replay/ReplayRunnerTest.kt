package com.trenching.bot.replay

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.DipSampleV6
import com.trenching.bot.walletactivity.domain.IngestionReason
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ReplayRunnerTest {
    private val loader = ReplayDatasetLoader(JsonMapper.builder().build())
    private val report: SimulationReport = ReplayRunner().run(DipSample.load(), SimulationConfig.mvp())

    private fun scenario(cycle: Int): ScenarioResult = report.scenarios.single { it.intent.pair == DipSample.pair(cycle) }

    private fun rejection(cycle: Int): RejectionReason = (scenario(cycle).outcome as FillOutcome.Rejected).reason

    @Test
    fun `the first buy of each mint starts one cycle and later buys of it start none`() {
        assertEquals(10, report.scenarios.size)
        assertEquals(1, report.ingestion.count { it is NormalizationOutcome.Ignored && it.reason == IngestionReason.LATER_BUY_SAME_MINT })
        val takeProfit = scenario(DipSample.TAKE_PROFIT).intent
        assertEquals(Slot.of(1000), takeProfit.creationSlot)
        assertEquals(0, BigDecimal("0.0000001").compareTo(takeProfit.referencePrice))
        assertEquals(0, BigDecimal("0.000000085").compareTo(takeProfit.dipPrice))
        assertEquals(Slot.of(5500), takeProfit.horizonEndSlot)
        assertEquals(0, takeProfit.entryOffset)
    }

    @Test
    fun `a dip triggers at the first eligible trade in a later slot and buys from the next slot's post-trade curve`() {
        val scenario = scenario(DipSample.TAKE_PROFIT)
        val trigger = assertNotNull(scenario.intent.trigger)
        assertEquals(Slot.of(1002), trigger.slot)
        assertEquals("16800.000000000000", trigger.marketCapUsd.toPlainString())
        assertEquals(0, BigDecimal("200").compareTo(trigger.rate))
        val fill = (scenario.outcome as FillOutcome.Filled).fill
        assertEquals(Slot.of(1003), fill.actualSlot)
        assertEquals(0, BigDecimal("0.00000008616345143543821057475551350438").compareTo(fill.executionPrice))
        assertEquals("1160584.892249000000", fill.quantity.toPlainString())
        assertEquals("0.001250000000", fill.fee.toPlainString())
        assertEquals(125, fill.feeBps)
        assertEquals(19, fill.priceImpactBps)
        assertEquals("0.001000000000", fill.priorityFee.toPlainString())
        assertEquals("0.102250000000", fill.costBasis.toPlainString())
    }

    @Test
    fun `take profit exits at the trade after the trigger with hand checked pnl`() {
        val position = assertNotNull(scenario(DipSample.TAKE_PROFIT).position)
        assertEquals(0, BigDecimal("0.00000011201248686606967374718216755569").compareTo(position.takeProfitPrice))
        assertEquals(0, BigDecimal("0.00000007323893372012247898854218647872").compareTo(position.stopLossPrice))
        assertEquals(Slot.of(1005), assertNotNull(position.exitTrigger).slot)
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.TAKE_PROFIT, close.reason)
        assertEquals(Slot.of(1006), close.slot)
        assertEquals(0, BigDecimal("0.00000011474828329182461687631176780630").compareTo(close.executionPrice))
        assertEquals("0.133175124000", close.grossProceeds.toPlainString())
        assertEquals("0.001664690000", close.fee.toPlainString())
        assertEquals(125, close.feeBps)
        assertEquals(-22, close.priceImpactBps)
        assertEquals("0.001000000000", close.transactionCost.toPlainString())
        assertEquals("0.130510434000", close.netProceeds.toPlainString())
        assertEquals(DipSample.TAKE_PROFIT_PNL, close.realizedPnl.toPlainString())
    }

    @Test
    fun `a stop loss gapped past its level fills at the real lower price`() {
        val position = assertNotNull(scenario(DipSample.STOP_LOSS).position)
        assertEquals(0, BigDecimal("0.00000006813399957875238558536119800360").compareTo(position.stopLossPrice))
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.STOP_LOSS, close.reason)
        assertEquals(Slot.of(2005), close.slot)
        assertEquals(0, BigDecimal("0.00000004992237946739523467462528718257").compareTo(close.executionPrice))
        assertEquals("0.062280246000", close.grossProceeds.toPlainString())
        assertEquals(-16, close.priceImpactBps)
        assertTrue(close.executionPrice < position.stopLossPrice)
        assertEquals(DipSample.STOP_LOSS_PNL, close.realizedPnl.toPlainString())
    }

    @Test
    fun `cycles without an entry are rejected with their reason`() {
        assertEquals(RejectionReason.NO_DIP, rejection(DipSample.NO_DIP))
        assertEquals(RejectionReason.REFERENCE_BELOW_MIN_MARKET_CAP, rejection(DipSample.LOW_CAP))
        assertEquals(RejectionReason.MIGRATED_BEFORE_ENTRY, rejection(DipSample.MIGRATED_EARLY))
        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER, rejection(DipSample.NO_FILL))
        assertEquals(RejectionReason.UNSUPPORTED_SUPPLY, rejection(DipSample.UNSUPPORTED_SUPPLY))
        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, scenario(DipSample.LOW_CAP).intent.watch)
        assertNull(scenario(DipSample.LOW_CAP).intent.trigger)
        assertEquals(DipWatchOutcome.TRIGGERED, scenario(DipSample.NO_FILL).intent.watch)
        listOf(DipSample.NO_DIP, DipSample.LOW_CAP, DipSample.MIGRATED_EARLY, DipSample.NO_FILL, DipSample.UNSUPPORTED_SUPPLY)
            .forEach { assertNull(scenario(it).position) }
    }

    @Test
    fun `positions without an exit stay open with their reason`() {
        mapOf(
            DipSample.MIGRATED_IN_POSITION to RejectionReason.MIGRATED,
            DipSample.HORIZON to RejectionReason.HORIZON_REACHED,
            DipSample.NO_EXIT_FILL to RejectionReason.HORIZON_REACHED,
        ).forEach { (cycle, reason) ->
            val scenario = scenario(cycle)
            assertEquals(PositionState.OPEN, assertNotNull(scenario.position).state)
            assertEquals(reason, scenario.exitUnavailable, "cycle=$cycle")
        }
        assertNull(assertNotNull(scenario(DipSample.HORIZON).position).exitTrigger)
        assertTrue(report.scenarios.none { it.exitUnavailable == RejectionReason.NO_EXIT_FILL })
    }

    @Test
    fun `a take profit with no later trade stays open in a v3 dataset, which cannot prove that no trade landed within 60 s`() {
        val scenario = scenario(DipSample.NO_EXIT_FILL)
        val position = assertNotNull(scenario.position)

        assertEquals(PositionState.OPEN, position.state)
        assertEquals(RejectionReason.HORIZON_REACHED, scenario.exitUnavailable)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(position.exitTrigger).reason)
        assertEquals(Slot.of(DipSample.STANDING_CURVE_TRIGGER_SLOT), assertNotNull(position.exitTrigger).slot)
    }

    @Test
    fun `a take profit with no trade within 60 s sells against the curve standing at its sale slot with hand checked pnl`() {
        // Bought 1188191190525 raw tokens from slot 8002's curve for a 0.10225 cost basis; take profit triggers at slot 8003 (00:01:00)
        // and the coverage end time 00:02:01 proves no trade followed within 60 s, so the sale at slot 8004 is against 8003's
        // post-trade curve (virtual SOL 62151427980, virtual tokens 517928566506284): 1188191190525 * 62151427980 /
        // (517928566506284 + 1188191190525), rounded down, = 142256588 lamports. Fees round up: 1351438 + 426770 = 1778208 lamports.
        // Net 0.142256588 - 0.001778208 - 0.001 = 0.13947838; P&L 0.03722838.
        val noExitFill = DipSample.pair(DipSample.NO_EXIT_FILL)
        val covered = DipSampleV6.dataset(mapOf(noExitFill to Instant.parse("2026-10-01T00:02:01Z")))
        val scenario = ReplayRunner().run(covered, SimulationConfig.mvp()).scenarios.single { it.intent.pair == noExitFill }
        val position = assertNotNull(scenario.position)
        assertEquals("12600.000000000000", assertNotNull(scenario.intent.trigger).marketCapUsd.toPlainString())
        assertEquals(Slot.of(8003), assertNotNull(position.exitTrigger).slot)
        assertNull(scenario.exitUnavailable)
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.TAKE_PROFIT, close.reason)
        assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
        assertEquals(Slot.of(8004), close.slot)
        assertEquals("fixture:NOEXIT:8003:0", close.observationId)
        assertEquals(0, BigDecimal("0.00000011972533472255773859984367266272").compareTo(close.executionPrice))
        assertEquals("0.142256588000", close.grossProceeds.toPlainString())
        assertEquals("0.001778208000", close.fee.toPlainString())
        assertEquals(-23, close.priceImpactBps)
        assertEquals("0.139478380000", close.netProceeds.toPlainString())
        assertEquals(DipSample.STANDING_CURVE_PNL, close.realizedPnl.toPlainString())
        assertEquals(ExitPriceAssumption.NEXT_TRADE, assertNotNull(assertNotNull(scenario(DipSample.TAKE_PROFIT).position).close).priceAssumption)
    }

    @Test
    fun `a timed configuration over a v6 dataset times out the dip and the hold`() {
        val timed = ReplayRunner().run(timedDataset(), TIMED)
        fun timedScenario(cycle: Int) = timed.scenarios.single { it.intent.pair == DipSample.pair(cycle) }

        assertEquals(scenario(DipSample.TAKE_PROFIT).position?.close, timedScenario(DipSample.TAKE_PROFIT).position?.close)
        assertEquals(RejectionReason.NO_DIP, (timedScenario(DipSample.STOP_LOSS).outcome as FillOutcome.Rejected).reason)
        assertEquals(scenario(DipSample.NO_EXIT_FILL).position?.close, timedScenario(DipSample.NO_EXIT_FILL).position?.close)

        // Bought 1188191190525 raw tokens at slot 7002 (00:01:00), so the exit starts at min(00:04:00, 00:05:00). No recorded trade
        // follows 7003 within the horizon, and the coverage end time 00:05:00 proves the exit start, so the sale is against slot
        // 7003's curve (virtual SOL 53824715512, virtual tokens 598052394588567): 106725169 lamports gross, fees 1013890 + 320176.
        // Net 0.106725169 - 0.001334066 - 0.001 = 0.104391103; P&L 0.002141103.
        val horizon = assertNotNull(timedScenario(DipSample.HORIZON).position)
        assertEquals(Instant.parse("2026-10-01T00:04:00Z"), horizon.exitStart)
        val close = assertNotNull(horizon.close)
        assertEquals(CloseReason.TIME_EXIT, close.reason)
        assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
        assertEquals("fixture:HORIZON:7003:0", close.observationId)
        assertEquals(Slot.of(11500), close.slot)
        assertEquals(0, BigDecimal("0.00000008982154543061684260504082481234").compareTo(close.executionPrice))
        assertEquals("0.106725169000", close.grossProceeds.toPlainString())
        assertEquals("0.001334066000", close.fee.toPlainString())
        assertEquals(-20, close.priceImpactBps)
        assertEquals("0.002141103000", close.realizedPnl.toPlainString())
        assertEquals(emptyList(), DatasetCoverage.undecided(timed, timedDataset()))

        val withoutTime = ReplayRunner().run(timedDataset(throughTime = null), TIMED).scenarios
            .single { it.intent.pair == DipSample.pair(DipSample.HORIZON) }
        assertEquals(RejectionReason.HORIZON_REACHED, withoutTime.exitUnavailable)
        assertNull(assertNotNull(withoutTime.position).exitTrigger)
    }

    @Test
    fun `a rerun and a reordered repeated delivery produce the identical report`() {
        val original = DipSample.load()
        val reordered = original.copy(transactions = original.transactions.reversed() + original.transactions,
            observations = original.observations.reversed() + original.observations,
            referenceRates = original.referenceRates.reversed() + original.referenceRates)
        assertEquals(report, ReplayRunner().run(original, SimulationConfig.mvp()))
        assertEquals(report, ReplayRunner().run(reordered, SimulationConfig.mvp()))

        val timed = timedDataset()
        val timedReordered = timed.copy(transactions = timed.transactions.reversed() + timed.transactions,
            observations = timed.observations.reversed() + timed.observations,
            referenceRates = timed.referenceRates.reversed() + timed.referenceRates,
            coverage = timed.coverage.reversed())
        val timedReport = ReplayRunner().run(timed, TIMED)
        assertEquals(timedReport, ReplayRunner().run(timed, TIMED))
        assertEquals(timedReport, ReplayRunner().run(timedReordered, TIMED))
    }

    @Test
    fun `a lower minimum market cap lets the low cap token enter`() {
        val config = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("1000"), 4500, 1, BigDecimal("0.001"))
        val scenario = ReplayRunner().run(DipSample.load(), config).scenarios.single { it.intent.pair == DipSample.pair(DipSample.LOW_CAP) }
        assertEquals(DipWatchOutcome.TRIGGERED, scenario.intent.watch)
        assertTrue(report.runId != ReplayRunner().run(DipSample.load(), config).runId)
    }

    @Test
    fun `the minimum market cap is checked at the wallet's buy, not at the dip`() {
        // The take-profit buy is at $20,000 (1e-7 SOL per token at 200 USDT); its dip is below $19,000.
        assertEquals(DipWatchOutcome.TRIGGERED, takeProfitWatch(DipSample.load(), BigDecimal("19000")))
        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, takeProfitWatch(DipSample.load(), BigDecimal("20000")))
    }

    @Test
    fun `a buy whose minute has no rate is valued at the rate of the token's next trade`() {
        val original = DipSample.load()
        val buySlot = Slot.of(1000)
        val unrated = original.copy(transactions = original.transactions.map {
            if (it.slot == buySlot) it.copy(blockTime = Instant.parse("2026-09-30T23:59:00Z")) else it
        })
        assertEquals(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP, takeProfitWatch(unrated, BigDecimal("20000")))
    }

    private fun takeProfitWatch(dataset: ReplayDataset, minMarketCapUsd: BigDecimal): DipWatchOutcome {
        val config = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, minMarketCapUsd, 4500, 1, BigDecimal("0.001"))
        return ReplayRunner().run(dataset, config).scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.intent.watch
    }

    @Test
    fun `engine dip-paper-v4 hashes latency and transaction cost into the configuration and the run`() {
        assertEquals("dip-paper-v4", report.engineVersion)
        val slower = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 2, BigDecimal("0.001"))
        val dearer = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.002"))
        listOf(slower, dearer).map { ReplayRunner().run(DipSample.load(), it) }.forEach { other ->
            assertTrue(other.configurationHash != report.configurationHash)
            assertTrue(other.runId != report.runId)
            assertTrue(other.resultChecksum != report.resultChecksum)
        }
    }

    @Test
    fun `the configuration hash names a timed field only when it is set, so the default's hash is unchanged`() {
        fun timed(dipWindowMs: Long? = null, maxHoldMs: Long? = null, hardExitStartMs: Long? = null) =
            SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"),
                dipWindowMs = dipWindowMs, maxHoldMs = maxHoldMs, hardExitStartMs = hardExitStartMs)
        val hashes = listOf(timed(), timed(dipWindowMs = 30_000), timed(dipWindowMs = 31_000),
            timed(maxHoldMs = 180_000, hardExitStartMs = 240_000), timed(maxHoldMs = 181_000, hardExitStartMs = 240_000),
            timed(maxHoldMs = 180_000, hardExitStartMs = 241_000), SimulationConfig.agreed()).map(ReplayRunner::configurationHash)

        assertEquals(PINNED_CONFIGURATION_HASH, hashes.first())
        assertEquals(hashes.size, hashes.distinct().size)
    }

    @Test
    fun `dip-sample-v3 and its v4 twin keep their pinned dip-paper-v4 run identity and result`() {
        val v4 = ReplayRunner().run(DipSampleV4.dataset(), SimulationConfig.mvp())

        assertEquals(listOf(PINNED_V3_RUN_ID, PINNED_CONFIGURATION_HASH, PINNED_V3_RESULT_CHECKSUM),
            listOf(report.runId, report.configurationHash, report.resultChecksum))
        assertEquals(listOf(PINNED_V4_RUN_ID, PINNED_CONFIGURATION_HASH, PINNED_V4_RESULT_CHECKSUM),
            listOf(v4.runId, v4.configurationHash, v4.resultChecksum))
    }

    @Test
    fun `swaps against another quote mint create no cycle`() {
        val original = DipSample.load()
        val otherQuote = original.transactions.first().let {
            it.copy(instructionIndex = 20, pair = MarketPair.of(it.pair.base, MintAddress.of("11111111111111111111111111111113")))
        }
        val result = ReplayRunner().run(original.copy(transactions = original.transactions + otherQuote), SimulationConfig.mvp())
        assertEquals(10, result.scenarios.size)
        assertTrue(result.ingestion.any { it is NormalizationOutcome.Ignored && it.reason == IngestionReason.UNSUPPORTED_QUOTE })
    }

    @Test
    fun `conflicting transactions cannot silently share an idempotency key`() {
        val original = DipSample.load()
        assertFailsWith<IllegalArgumentException> {
            ReplayRunner().run(original.copy(transactions = original.transactions + original.transactions.first().copy(successful = false)),
                SimulationConfig.mvp())
        }
    }

    @Test
    fun `the loader refuses v1 datasets, tampering and observations without a rate`() {
        val json = DipSample.json()
        val v1 = assertFailsWith<IllegalArgumentException> { loader.load(json.replace("\"schemaVersion\": 3", "\"schemaVersion\": 1").byteInputStream()) }
        assertTrue(v1.message.orEmpty().contains("UNSUPPORTED_SCHEMA"), v1.message)
        assertFailsWith<IllegalArgumentException> { loader.load(json.replace("\"0.000000086\"", "\"0.000000087\"").byteInputStream()) }
        val noRate = assertFailsWith<IllegalArgumentException> {
            loader.load(json.replace("\"minute\": \"2026-10-01T00:02:00Z\"", "\"minute\": \"2026-10-01T00:03:00Z\"").byteInputStream())
        }
        assertTrue(noRate.message.orEmpty().contains("REFERENCE_RATE_MISSING"), noRate.message)
    }

    @Test
    fun `datasets persistence would reject fail at load time`() {
        val json = DipSample.json()
        // Each failure must come from the specific check, not from the checksum mismatch the edit also causes.
        listOf(
            json.replace("2026-10-01T00:00:00Z\",\n      \"baseMint", "2026-10-01T00:00:00.000000001Z\",\n      \"baseMint") to "microsecond",
            json.replace("\"pumpfun-bonding-curve-trade/v1\"", "\"" + "s".repeat(129) + "\"") to "maxLength=128",
        ).forEach { (tampered, reason) ->
            val error = assertFailsWith<IllegalArgumentException> { loader.load(tampered.byteInputStream()) }
            assertTrue(requireNotNull(error.message).contains(reason), error.message)
        }
    }

    /**
     * The v6 twin with the stop-loss cycle's trades moved 31 s after its buy, outside a 30 s dip window, and the horizon cycle's
     * coverage recorded through [throughTime].
     */
    private fun timedDataset(throughTime: Instant? = Instant.parse("2026-10-01T00:05:00Z")): ReplayDataset {
        val stopLoss = DipSample.pair(DipSample.STOP_LOSS)
        val v6 = DipSampleV6.dataset(listOfNotNull(throughTime?.let { DipSample.pair(DipSample.HORIZON) to it }).toMap())
        return v6.copy(observations = v6.observations.map { if (it.pair == stopLoss) it.copy(observedTime = it.observedTime.plusSeconds(31)) else it })
    }

    private companion object {
        /** The mvp levels with spec P7 and P11's clocks: a 30 s dip window, then three minutes held or four from the buy. */
        val TIMED: SimulationConfig = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1,
            BigDecimal("0.001"), dipWindowMs = 30_000, maxHoldMs = 180_000, hardExitStartMs = 240_000)
        const val PINNED_CONFIGURATION_HASH = "be937336820a84b0af7482105efb9334ebb710c0ccf8e4d6bae88f2666a0a329"
        const val PINNED_V3_RUN_ID = "9f56738063e32b99c8d7b98955704784ad7d9dd83fb9ee7cf6fa95dd8a7fdace"
        const val PINNED_V3_RESULT_CHECKSUM = "b32af5db493a578784d4eccb299dbd0cd9f17eb838307f19a0583f98eb1d3d22"
        const val PINNED_V4_RUN_ID = "e4f60e845ef85b4ba50df80f213c126cb994a7701270943d7f8f4b6ecafc7e23"
        const val PINNED_V4_RESULT_CHECKSUM = "08c7f1dbf9e75d6f47737a8a6154f3819c78123c706c3384e5fea8efc55d2677"
    }
}
