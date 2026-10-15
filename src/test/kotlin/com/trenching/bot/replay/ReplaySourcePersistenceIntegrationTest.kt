package com.trenching.bot.replay

import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.application.SimulationRunLedger
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import java.math.BigInteger
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
class ReplaySourcePersistenceIntegrationTest {
    @Autowired private lateinit var ledger: SimulationRunLedger
    @Autowired private lateinit var wallets: WalletEventStore
    @Autowired private lateinit var markets: MarketObservationStore
    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `raw u64 events and exact slot observations with their curve state round trip with ignored outcomes`() {
        val original = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
            ReplayDatasetLoader(mapper).load(requireNotNull(it))
        }
        val u64Max = BigInteger("18446744073709551615")
        val input = original.copy(transactions = original.transactions.map {
            if (it.instructionIndex == 0) it.copy(baseDelta = u64Max) else it
        }, observations = original.observations.mapIndexed { index, observation ->
            if (index == 0) observation.copy(curve = observation.curve.copy(reserves = observation.curve.reserves.copy(virtualSol = u64Max, virtualToken = u64Max)))
            else observation
        })
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        ledger.persist(input, report)
        assertEquals(input.transactions, wallets.transactions(report.runId))
        assertEquals(report.ingestion.filterIsInstance<NormalizationOutcome.Accepted>().map { it.trade }, wallets.acceptedTrades(report.runId))
        assertEquals(report.ingestion.filterIsInstance<NormalizationOutcome.Ignored>(), wallets.ignored(report.runId))
        assertEquals(OrderedMarketData.canonical(input.observations), markets.observations(report.runId))
    }

    @Test
    fun `a v4 dataset's trades round trip and a v3 dataset's observations read back without one`() {
        val v4 = ReplayDatasetLoader(mapper).load(DipSampleV4.json().byteInputStream())
        val v3 = DipSample.load()
        val config = SimulationConfig.mvp()
        val v4Report = ReplayRunner().run(v4, config)
        val v3Report = ReplayRunner().run(v3, config)

        ledger.persist(v4, v4Report)
        ledger.persist(v3, v3Report)

        val stored = markets.observations(v4Report.runId)
        assertEquals(OrderedMarketData.canonical(v4.observations), stored)
        assertTrue(stored.all { it.trade != null })
        assertTrue(markets.observations(v3Report.runId).all { it.trade == null })
    }
}
