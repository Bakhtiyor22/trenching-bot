package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.domain.StoredReportFacts
import com.trenching.bot.console.report.RunReportReader
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletEventStore
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ConsoleServiceRunObservationsTest {
    private val runs: SimulationRuns = Mockito.mock(SimulationRuns::class.java)
    private val reports: RunReportReader = Mockito.mock(RunReportReader::class.java)
    private val observations: MarketObservationStore = Mockito.mock(MarketObservationStore::class.java)
    private val service = ConsoleService(runs, reports, Mockito.mock(WalletEventStore::class.java), observations,
        Mockito.mock(TradeIntentStore::class.java), Mockito.mock(PaperOrderStore::class.java), Mockito.mock(PositionStore::class.java))
    private val run = RunSummary("run-1", "dataset-1", "engine", "config", "result", MintAddress.WRAPPED_SOL, 0, 0, 0, 0, 0, BigDecimal.ZERO,
        at("2026-10-08T00:00:00Z"))
    private val stored = listOf(observation("a", at("2026-10-01T00:00:10Z"), "1"))

    @Test
    fun `a run's detail and observations come from a single read of its observations`() {
        Mockito.`when`(runs.find("run-1")).thenReturn(run)
        Mockito.`when`(runs.report("run-1")).thenReturn("{}")
        Mockito.`when`(reports.read("{}")).thenReturn(StoredReportFacts(SimulationConfig.mvp()))
        Mockito.`when`(observations.observations("run-1")).thenReturn(stored)

        val read = service.detailWithObservations("run-1")

        assertEquals(run, read.detail.run)
        assertEquals(stored, read.observations)
        Mockito.verify(observations, Mockito.times(1)).observations("run-1")
    }

    @Test
    fun `an unknown run is not found before anything is read`() {
        assertFailsWith<SimulationRunNotFoundException> { service.detailWithObservations("missing") }
        Mockito.verifyNoInteractions(observations)
    }
}
