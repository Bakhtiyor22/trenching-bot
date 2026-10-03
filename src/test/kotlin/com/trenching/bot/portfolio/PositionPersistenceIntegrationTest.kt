package com.trenching.bot.portfolio

import com.trenching.bot.portfolio.infrastructure.persistence.PositionEntity
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceException
import java.math.BigDecimal
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
class PositionPersistenceIntegrationTest {
    @Autowired private lateinit var application: PersistedReplayService
    @Autowired private lateinit var positions: PositionStore
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var factory: EntityManagerFactory

    @Test
    fun `optimistic version rejects a stale position writer`() {
        val dataset = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
            ReplayDatasetLoader(json).load(requireNotNull(it))
        }
        val run = application.execute(dataset, SimulationConfig.of(BigDecimal("103"), 10, 50, 2, 2, 5))
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
