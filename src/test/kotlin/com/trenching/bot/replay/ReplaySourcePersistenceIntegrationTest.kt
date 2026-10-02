package com.trenching.bot.replay

import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.application.SimulationRunLedger
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
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
    fun `raw u64 events and exact slot observations round trip with ignored outcomes`() {
        val original = javaClass.getResourceAsStream("/fixtures/weekly-buy-v1.json").use {
            ReplayDatasetLoader(mapper).load(requireNotNull(it))
        }
        val input = original.copy(transactions = original.transactions.map {
            if (it.instructionIndex == 0) it.copy(baseDelta = BigInteger("18446744073709551615")) else it
        })
        val report = ReplayRunner().run(input, SimulationConfig.mvp())
        ledger.persist(input, report)
        assertEquals(input.transactions, wallets.transactions(report.runId))
        assertEquals(report.ingestion.filterIsInstance<NormalizationOutcome.Accepted>().map { it.trade }, wallets.acceptedTrades(report.runId))
        assertEquals(report.ingestion.filterIsInstance<NormalizationOutcome.Ignored>(), wallets.ignored(report.runId))
        assertEquals(input.observations, markets.observations(report.runId))
    }
}
