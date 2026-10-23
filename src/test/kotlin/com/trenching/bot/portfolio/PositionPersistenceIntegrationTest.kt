package com.trenching.bot.portfolio

import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.infrastructure.persistence.PositionEntity
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV6
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceException
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class PositionPersistenceIntegrationTest {
    @Autowired private lateinit var application: PersistedReplayService
    @Autowired private lateinit var positions: PositionStore
    @Autowired private lateinit var intents: TradeIntentStore
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var factory: EntityManagerFactory
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `closed positions store their curve exit fields and open positions leave them null`() {
        val dataset = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
            ReplayDatasetLoader(json).load(requireNotNull(it))
        }
        val run = application.execute(dataset, SimulationConfig.mvp())

        val rows = JdbcTemplate(dataSource).queryForList("""
            select state, close_fee_bps, close_transaction_cost, close_price_impact_bps
            from positions where run_id = ? order by target_slot""".trimIndent(), run.id)

        assertEquals(listOf("CLOSED", "CLOSED", "OPEN", "OPEN", "OPEN"), rows.map { it["state"] })
        val (closed, open) = rows.partition { it["state"] == "CLOSED" }
        assertEquals(listOf(125, 125), closed.map { it["close_fee_bps"] })
        assertEquals(List(2) { "0.001000000000" }, closed.map { (it["close_transaction_cost"] as BigDecimal).toPlainString() })
        assertEquals(listOf(-22, -16), closed.map { it["close_price_impact_bps"] })
        open.forEach { row ->
            assertNull(row["close_fee_bps"])
            assertNull(row["close_transaction_cost"])
            assertNull(row["close_price_impact_bps"])
        }
        assertEquals(positions.positions(run.id).mapNotNull { it.close },
            ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.mapNotNull { it.position?.close })
    }

    @Test
    fun `a timed run stores its clocks and a standing-curve time exit and reads them back exactly`() {
        val horizon = DipSample.pair(DipSample.HORIZON)
        val dataset = DipSampleV6.dataset(mapOf(horizon to Instant.parse("2026-10-01T00:04:00Z")), mapOf(horizon to 9000))
        val report = ReplayRunner().run(dataset, TIMED)
        val expected = assertNotNull(report.scenarios.single { it.intent.pair == horizon }.position)
        val exitStart = assertNotNull(expected.exitStart)

        val run = application.execute(dataset, TIMED)

        val row = JdbcTemplate(dataSource).queryForMap("""
            select i.t0, p.exit_start, p.exit_trigger_time, p.exit_trigger_reason, p.close_reason, p.close_price_assumption
            from positions p join trade_intents i on i.id = p.intent_id
            where p.run_id = ? and p.base_mint = ?""".trimIndent(), run.id, horizon.base.value)
        assertEquals(report.scenarios.single { it.intent.pair == horizon }.intent.t0, (row["t0"] as Timestamp).toInstant())
        assertEquals(exitStart, (row["exit_start"] as Timestamp).toInstant())
        assertEquals(exitStart, (row["exit_trigger_time"] as Timestamp).toInstant())
        assertEquals(listOf("TIME_EXIT", "TIME_EXIT", "STANDING_CURVE"),
            listOf(row["exit_trigger_reason"], row["close_reason"], row["close_price_assumption"]))
        assertEquals(CloseReason.TIME_EXIT, assertNotNull(expected.close).reason)
        assertEquals(report.scenarios.map { it.intent }, intents.intents(run.id))
        assertEquals(report.scenarios.mapNotNull { it.position }, positions.positions(run.id))
    }

    @Test
    fun `rows stored before the timed clocks read with no times and a next-trade sale`() {
        val dataset = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
            ReplayDatasetLoader(json).load(requireNotNull(it))
        }
        // Its own configuration, so the rows aged here belong to no run another test reads.
        val run = application.execute(dataset, SimulationConfig.of(BigDecimal("0.104"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1,
            BigDecimal("0.001")))
        val jdbc = JdbcTemplate(dataSource)
        jdbc.update("update positions set exit_start = null, exit_trigger_time = null, close_price_assumption = null where run_id = ?", run.id)
        jdbc.update("update trade_intents set t0 = null where run_id = ?", run.id)

        val stored = positions.positions(run.id)

        assertTrue(stored.mapNotNull { it.close }.isNotEmpty())
        assertTrue(stored.mapNotNull { it.close }.all { it.priceAssumption == ExitPriceAssumption.NEXT_TRADE })
        assertTrue(stored.mapNotNull { it.exitTrigger }.all { it.time == null })
        assertTrue(stored.all { it.exitStart == null })
        assertTrue(intents.intents(run.id).all { it.t0 == null })
    }

    @Test
    fun `optimistic version rejects a stale position writer`() {
        val dataset = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
            ReplayDatasetLoader(json).load(requireNotNull(it))
        }
        val run = application.execute(dataset, SimulationConfig.of(BigDecimal("0.103"), 2, 1500, 3000, 1500, java.math.BigDecimal("10000"), 4500, 1, BigDecimal("0.001")))
        val id = positions.positions(run.id).first().id
        factory.createEntityManager().use { first ->
            factory.createEntityManager().use { stale ->
                first.transaction.begin()
                stale.transaction.begin()
                val current = first.find(PositionEntity::class.java, id)
                val outdated = stale.find(PositionEntity::class.java, id)
                val originalVersion = requireNotNull(current.version)
                first.lock(current, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
                first.transaction.commit()
                assertFailsWith<PersistenceException> {
                    stale.lock(outdated, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
                    stale.transaction.commit()
                }
                if (stale.transaction.isActive) stale.transaction.rollback()
                first.clear()
                assertEquals(originalVersion + 1, first.find(PositionEntity::class.java, id).version)
            }
        }
    }

    private companion object {
        val TIMED: SimulationConfig = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1,
            BigDecimal("0.001"), maxHoldMs = 180_000, hardExitStartMs = 240_000)
    }
}
