package com.trenching.bot.replay

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
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
            DipSample.NO_EXIT_FILL to RejectionReason.NO_EXIT_FILL,
        ).forEach { (cycle, reason) ->
            val scenario = scenario(cycle)
            assertEquals(PositionState.OPEN, assertNotNull(scenario.position).state)
            assertEquals(reason, scenario.exitUnavailable, "cycle=$cycle")
        }
        assertEquals("12600.000000000000", assertNotNull(scenario(DipSample.NO_EXIT_FILL).intent.trigger).marketCapUsd.toPlainString())
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(assertNotNull(scenario(DipSample.NO_EXIT_FILL).position).exitTrigger).reason)
        assertNull(assertNotNull(scenario(DipSample.HORIZON).position).exitTrigger)
    }

    @Test
    fun `a rerun and a reordered repeated delivery produce the identical report`() {
        val original = DipSample.load()
        val reordered = original.copy(transactions = original.transactions.reversed() + original.transactions,
            observations = original.observations.reversed() + original.observations,
            referenceRates = original.referenceRates.reversed() + original.referenceRates)
        assertEquals(report, ReplayRunner().run(original, SimulationConfig.mvp()))
        assertEquals(report, ReplayRunner().run(reordered, SimulationConfig.mvp()))
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
    fun `engine dip-paper-v3 hashes latency and transaction cost into the configuration and the run`() {
        assertEquals("dip-paper-v3", report.engineVersion)
        val slower = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 2, BigDecimal("0.001"))
        val dearer = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.002"))
        listOf(slower, dearer).map { ReplayRunner().run(DipSample.load(), it) }.forEach { other ->
            assertTrue(other.configurationHash != report.configurationHash)
            assertTrue(other.runId != report.runId)
            assertTrue(other.resultChecksum != report.resultChecksum)
        }
    }

    @Test
    fun `dip-sample-v3 and its v4 twin keep their pinned dip-paper-v3 run identity and result`() {
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

    private companion object {
        const val PINNED_CONFIGURATION_HASH = "be937336820a84b0af7482105efb9334ebb710c0ccf8e4d6bae88f2666a0a329"
        const val PINNED_V3_RUN_ID = "3ec5984abf7e67dad91089961bfbb247b62d425b1ba77281194cfd581ab9d512"
        const val PINNED_V3_RESULT_CHECKSUM = "7dde0b61d4f503a7ff6b45e9b024065a893c57d720522d9bc5ecf0d5f3f490ac"
        const val PINNED_V4_RUN_ID = "f7aaab5afff8776db00c3c02ed95aad40d5cf81c63a1d75147ee8cfe26dbfc3d"
        const val PINNED_V4_RESULT_CHECKSUM = "2eae1e3abc2bdd8db609e2e14489fa7536d9502a6cbf70069d84dfee7ebbce6a"
    }
}
