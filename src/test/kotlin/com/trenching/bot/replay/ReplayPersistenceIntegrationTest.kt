package com.trenching.bot.replay

import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.application.SimulationRunLedger
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
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
class ReplayPersistenceIntegrationTest {
    @Autowired private lateinit var ledger: SimulationRunLedger
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource

    private fun dataset() = javaClass.getResourceAsStream("/fixtures/weekly-buy-v1.json").use {
        ReplayDatasetLoader(mapper).load(requireNotNull(it))
    }

    @Test
    fun `run metadata and report survive storage and repeated delivery`() {
        val input = dataset()
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        val first = ledger.persist(input, report)
        val second = ledger.persist(input, report)
        assertEquals(first, second)
        assertEquals(first, ledger.find(report.runId))
        assertEquals(report.resultChecksum, first.resultChecksum)
        assertEquals(report.datasetChecksum, first.datasetChecksum)
        assertEquals(report.configurationHash, first.configurationHash)
        val stored = mapper.readTree(first.reportSnapshot)
        assertEquals(3, stored.path("scenarios").size())
        assertEquals(0, java.math.BigDecimal("8.696567164180").compareTo(java.math.BigDecimal(
            stored.path("scenarios")[0].path("position").path("close").path("realizedPnl").asString())))
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from simulation_runs where id = ?").use { statement ->
                statement.setString(1, report.runId)
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun `concurrent duplicate writers converge on one durable run`() {
        val input = dataset()
        val config = SimulationConfig.of(java.math.BigDecimal("101"), 10, 50, 2, 2, 5)
        val report = ReplayRunner().run(input, config)
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val pending = (1..2).map { pool.submit<com.trenching.bot.replay.domain.SimulationRunRecord> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                ledger.persist(input, report)
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = pending.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(results.first(), results.last())
        } finally {
            pool.shutdownNow()
        }
    }
}
