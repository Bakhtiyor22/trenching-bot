package com.trenching.bot.replay

import com.trenching.bot.replay.application.DatasetEntryFactory
import com.trenching.bot.replay.application.SimulationRunLedger
import com.trenching.bot.replay.application.SimulationRunTransaction
import com.trenching.bot.replay.application.SimulationRunsService
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.replay.mapper.SimulationRunMapper
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class ConcurrentRunStartIntegrationTest {
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var transactions: SimulationRunTransaction
    @Autowired private lateinit var mapper: SimulationRunMapper
    @Autowired private lateinit var entries: DatasetEntryFactory
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `simultaneous starts return exactly one created outcome with one record batch`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val barrier = CyclicBarrier(2)
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                barrier.await(10, TimeUnit.SECONDS)
                return Instant.parse("2026-10-03T00:00:00Z")
            }
        }
        val ledger = SimulationRunLedger(transactions, mapper, entries, json, clock)
        val service = SimulationRunsService(catalog, ledger, transactions, mapper)
        val config = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, java.math.BigDecimal("10000"), 4500, 1, BigDecimal("0.0017"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pending = List(2) { pool.submit(Callable { service.start(datasetId, config) }) }
            val results = pending.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.created })
            assertEquals(results.first().run, results.last().run)
            val runId = results.first().run.id
            val counts = com.trenching.bot.support.runRowCounts(dataSource, runId)
            assertEquals(listOf(1, 11, 31, 10, 10, 5, 5), counts)
            val repeated = SimulationRunsService(catalog,
                SimulationRunLedger(transactions, mapper, entries, json, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                transactions, mapper).start(datasetId, config)
            assertFalse(repeated.created)
            assertEquals(counts, com.trenching.bot.support.runRowCounts(dataSource, runId))
        } finally {
            pool.shutdownNow()
        }
    }
}
