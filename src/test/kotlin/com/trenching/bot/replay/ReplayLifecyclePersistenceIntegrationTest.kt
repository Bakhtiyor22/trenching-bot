package com.trenching.bot.replay

import com.trenching.bot.replay.application.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SimulationRunConflictException
import java.math.BigDecimal
import java.sql.SQLException
import javax.sql.DataSource
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.runRowCounts
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class ReplayLifecyclePersistenceIntegrationTest {
    @Autowired private lateinit var ledger: SimulationRunLedger
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var intents: TradeIntentStore
    @Autowired private lateinit var orders: PaperOrderStore
    @Autowired private lateinit var positions: PositionStore
    @Autowired private lateinit var application: PersistedReplayService
    @Autowired private lateinit var dataSource: DataSource

    private fun dataset(): ReplayDataset = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
        ReplayDatasetLoader(json).load(requireNotNull(it))
    }

    private fun counts(runId: String): List<Int> = runRowCounts(dataSource, runId)

    @Test
    fun `complete lifecycle restores exactly after an idempotent replay`() {
        val dataset = dataset()
        val report = ReplayRunner().run(dataset, SimulationConfig.mvp())
        val first = application.execute(dataset, SimulationConfig.mvp())
        val repeated = dataset.copy(transactions = dataset.transactions.reversed() + dataset.transactions,
            observations = dataset.observations.reversed() + dataset.observations)
        assertEquals(first, application.execute(repeated, SimulationConfig.mvp()))
        assertEquals(DIP_SAMPLE_ROWS, counts(report.runId))
        assertEquals(report.scenarios.map { it.intent }, intents.intents(report.runId))
        assertEquals(report.scenarios.map { it.outcome }, orders.outcomes(report.runId))
        assertEquals(report.scenarios.mapNotNull { it.position }, positions.positions(report.runId))
        assertEquals(5, orders.outcomes(report.runId).count { it is FillOutcome.Filled })
        assertEquals(setOf(DipSample.TAKE_PROFIT_PNL, DipSample.STOP_LOSS_PNL),
            positions.positions(report.runId).mapNotNull { it.close?.realizedPnl?.toPlainString() }.toSet())
    }

    @Test
    fun `different configuration has independent intents fills and positions`() {
        val input = dataset()
        val first = application.execute(input, SimulationConfig.mvp())
        val alternate = application.execute(input, SimulationConfig.of(BigDecimal("0.102"), 2, 1500, 3000, 1500, java.math.BigDecimal("10000"), 4500, 1, BigDecimal("0.001")))
        assertNotEquals(first.id, alternate.id)
        assertTrue(intents.intents(first.id).map { it.id }.toSet().intersect(intents.intents(alternate.id).map { it.id }.toSet()).isEmpty())
        assertEquals(DIP_SAMPLE_ROWS, counts(alternate.id))
    }

    @Test
    fun `missing prices retain rejected orders without fills or positions`() {
        val input = dataset().copy(observations = emptyList())
        val run = application.execute(input, SimulationConfig.mvp())
        assertEquals(listOf(1, 11, 0, 10, 10, 0, 0), counts(run.id))
        assertTrue(orders.outcomes(run.id).all { it is FillOutcome.Rejected })
    }

    @Test
    fun `missing exits retain open positions and explicit reasons`() {
        val original = dataset()
        // Up to three slots after each buy, without the standing-curve cycle's take profit: every entry fills, and no exit can follow.
        val input = original.copy(observations = original.observations.filter {
            it.slot.value % 1000 <= 3 && it.slot.value != DipSample.STANDING_CURVE_TRIGGER_SLOT
        })
        val run = application.execute(input, SimulationConfig.mvp())
        assertEquals(5, positions.positions(run.id).size)
        assertTrue(positions.positions(run.id).all { it.state == PositionState.OPEN && it.close == null })
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from positions where run_id = ? and exit_unavailable = 'HORIZON_REACHED'").use { statement ->
                statement.setString(1, run.id)
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(4, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun `unrepresentable market decimal rolls back run and earlier wallet writes`() {
        val original = dataset()
        val input = original.copy(observations = original.observations.mapIndexed { index, observation ->
            if (index == 0) observation.copy(price = BigDecimal("10.000000000000000000000000000000000000001")) else observation
        })
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        assertFailsWith<ArithmeticException> { ledger.persist(input, report) }
        assertNull(ledger.find(report.runId))
        assertTrue(counts(report.runId).all { it == 0 })
    }

    @Test
    fun `submicrosecond source times cannot silently round in storage`() {
        val original = dataset()
        val input = original.copy(observations = original.observations.mapIndexed { index, observation ->
            if (index == 0) observation.copy(observedTime = observation.observedTime.plusNanos(1)) else observation
        })
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        assertFailsWith<IllegalArgumentException> { ledger.persist(input, report) }
        assertTrue(counts(report.runId).all { it == 0 })
    }

    @Test
    fun `same run cannot overwrite a conflicting deterministic result`() {
        val input = dataset()
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        ledger.persist(input, report)
        assertFailsWith<SimulationRunConflictException> {
            ledger.persist(input, report.copy(resultChecksum = "0".repeat(64)))
        }
        assertEquals(report.resultChecksum, requireNotNull(ledger.find(report.runId)).resultChecksum)
    }

    @Test
    fun `database prevents duplicate event identity`() {
        val run = application.execute(dataset(), SimulationConfig.mvp())
        dataSource.connection.use { connection ->
            connection.prepareStatement("update wallet_events set signature = " +
                "(select signature from wallet_events other where other.run_id = ? and other.slot = 1000) where run_id = ? and slot = 2000").use { statement ->
                statement.setString(1, run.id)
                statement.setString(2, run.id)
                assertEquals("23505", assertFailsWith<SQLException> { statement.executeUpdate() }.sqlState)
            }
        }
    }

    private companion object {
        /** Runs, wallet events, observations, intents, orders, fills and positions of the dip sample's default run. */
        val DIP_SAMPLE_ROWS = listOf(1, 11, 31, 10, 10, 5, 5)
    }
}
