package com.trenching.bot.portfolio

import com.trenching.bot.portfolio.infrastructure.persistence.PositionEntity
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceException
import java.math.BigDecimal
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
        assertEquals(listOf(125, 125), rows.take(2).map { it["close_fee_bps"] })
        assertEquals(listOf("0.001000000000", "0.001000000000"), rows.take(2).map { (it["close_transaction_cost"] as BigDecimal).toPlainString() })
        assertEquals(listOf(-22, -16), rows.take(2).map { it["close_price_impact_bps"] })
        rows.drop(2).forEach { row ->
            assertNull(row["close_fee_bps"])
            assertNull(row["close_transaction_cost"])
            assertNull(row["close_price_impact_bps"])
        }
        assertEquals(positions.positions(run.id).mapNotNull { it.close },
            ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.mapNotNull { it.position?.close })
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
}
