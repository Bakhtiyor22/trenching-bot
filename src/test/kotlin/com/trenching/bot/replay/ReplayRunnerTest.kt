package com.trenching.bot.replay

import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.strategy.domain.SimulationConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReplayRunnerTest {
    private val loader = ReplayDatasetLoader(tools.jackson.databind.json.JsonMapper.builder().build())
    private fun dataset() = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
        loader.load(requireNotNull(it))
    }

    @Test
    fun `three scenarios close with hand calculated fees slippage and pnl`() {
        val report = ReplayRunner().run(dataset(), SimulationConfig.mvp())
        assertEquals(listOf(101L, 102L, 103L), report.scenarios.map { it.intent.targetSlot.value })
        assertEquals(3, report.scenarios.size)
        report.scenarios.forEach { scenario ->
            val fill = (scenario.outcome as FillOutcome.Filled).fill
            val position = requireNotNull(scenario.position)
            assertEquals(0, java.math.BigDecimal("10.05").compareTo(fill.executionPrice))
            assertEquals("9.950248756219", fill.quantity.toPlainString())
            assertEquals("0.100000000000", fill.fee.toPlainString())
            assertEquals(PositionState.CLOSED, position.state)
            assertEquals("8.696567164180", requireNotNull(position.close).realizedPnl.toPlainString())
            assertEquals(fill.actualSlot.value + 5, requireNotNull(position.close).slot.value)
            assertEquals(scenario.intent.id, position.intentId)
        }
    }

    @Test
    fun `reordered and repeated delivery produces identical business results`() {
        val original = dataset()
        val reordered = original.copy(transactions = original.transactions.reversed() + original.transactions,
            observations = original.observations.reversed() + original.observations)
        val runner = ReplayRunner()
        assertEquals(runner.run(original, SimulationConfig.mvp()), runner.run(reordered, SimulationConfig.mvp()))
    }

    @Test
    fun `no observation within the entry window rejects without a position`() {
        val original = dataset()
        val report = ReplayRunner().run(original.copy(observations = original.observations.filter { it.slot.value >= 106 }), SimulationConfig.mvp())
        assertTrue(report.scenarios.all { (it.outcome as FillOutcome.Rejected).reason == RejectionReason.MISSING_OBSERVATION })
        assertTrue(report.scenarios.all { it.position == null })
    }

    @Test
    fun `missing exit data leaves auditable open positions`() {
        val original = dataset()
        val report = ReplayRunner().run(original.copy(observations = original.observations.filter { it.slot.value < 106 }), SimulationConfig.mvp())
        assertTrue(report.scenarios.all { it.position?.state == PositionState.OPEN })
        assertTrue(report.scenarios.all { it.exitUnavailable == RejectionReason.MISSING_OBSERVATION })
    }

    @Test
    fun `tampered fixtures and unsupported schema are rejected`() {
        val json = requireNotNull(javaClass.getResource("/replay/samples/weekly-buy-v1.json")).readText()
        assertFailsWith<IllegalArgumentException> { loader.load(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").byteInputStream()) }
        assertFailsWith<IllegalArgumentException> { loader.load(json.replace("\"10\"", "\"12\"").byteInputStream()) }
    }

    @Test
    fun `datasets persistence would reject fail at load time`() {
        val json = requireNotNull(javaClass.getResource("/replay/samples/weekly-buy-v1.json")).readText()
        // Each failure must come from the specific check, not from the checksum mismatch the edit also causes.
        listOf(
            json.replace("2026-09-23T00:00:00Z", "2026-09-23T00:00:00.000000001Z") to "microsecond",
            json.replace("\"curated-fixture\"", "\"" + "s".repeat(129) + "\"") to "maxLength=128",
        ).forEach { (tampered, reason) ->
            val error = assertFailsWith<IllegalArgumentException> { loader.load(tampered.byteInputStream()) }
            assertTrue(requireNotNull(error.message).contains(reason), error.message)
        }
    }

    @Test
    fun `swaps against another quote mint create no intents`() {
        val original = dataset()
        val otherQuote = original.transactions.first().let {
            it.copy(instructionIndex = 20, pair = com.trenching.bot.common.MarketPair.of(it.pair.base,
                com.trenching.bot.common.MintAddress.of("11111111111111111111111111111113")))
        }
        val report = ReplayRunner().run(original.copy(transactions = original.transactions + otherQuote), SimulationConfig.mvp())
        assertEquals(3, report.scenarios.size)
        assertTrue(report.ingestion.any {
            it is com.trenching.bot.walletactivity.domain.NormalizationOutcome.Ignored &&
                it.reason == com.trenching.bot.walletactivity.domain.IngestionReason.UNSUPPORTED_QUOTE
        })
    }

    @Test
    fun `overlapping buys are rejected independently in each scenario`() {
        val original = dataset()
        val second = original.transactions.first().copy(instructionIndex = 10, slot = com.trenching.bot.common.Slot.of(101))
        val report = ReplayRunner().run(original.copy(transactions = original.transactions + second), SimulationConfig.mvp())
        assertEquals(3, report.scenarios.count { (it.outcome as? FillOutcome.Rejected)?.reason == RejectionReason.ALREADY_OPEN })
        assertEquals(3, report.scenarios.count { it.position != null })
    }

    @Test
    fun `equal actual entry slots still allow only one position per scenario`() {
        val original = dataset()
        val first = original.transactions.first().copy(slot = com.trenching.bot.common.Slot.of(99))
        val second = first.copy(instructionIndex = 10, slot = com.trenching.bot.common.Slot.of(100))
        val observations = original.observations.map { if (it.slot.value == 101L) it.copy(quality = com.trenching.bot.marketdata.domain.ObservationQuality.INVALID) else it }
        val report = ReplayRunner().run(original.copy(transactions = listOf(first, second), observations = observations), SimulationConfig.mvp())
        assertEquals(3, report.scenarios.count { it.position != null })
    }

    @Test
    fun `conflicting transactions cannot silently share an idempotency key`() {
        val original = dataset()
        assertFailsWith<IllegalArgumentException> {
            ReplayRunner().run(original.copy(transactions = original.transactions + original.transactions.first().copy(successful = false)), SimulationConfig.mvp())
        }
    }
}
