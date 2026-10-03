package com.trenching.bot.portfolio

import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.CloseOutcome
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class PositionServiceTest {
    private fun dataset() = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
        ReplayDatasetLoader(JsonMapper.builder().build()).load(requireNotNull(it))
    }

    @Test
    fun `exit liquidity must cover the actual modeled sale proceeds`() {
        val dataset = dataset()
        val completed = requireNotNull(ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.first().position)
        val open = completed.copy(state = com.trenching.bot.portfolio.domain.PositionState.OPEN, close = null)
        val observations = dataset.observations.filter { it.slot.value >= 106 }.map {
            it.copy(price = java.math.BigDecimal("20"), liquidityQuote = java.math.BigDecimal("150"))
        }
        val outcome = PositionService().close(open, OrderedMarketData(observations), SimulationConfig.mvp())
        assertEquals(com.trenching.bot.papertrading.domain.RejectionReason.INSUFFICIENT_LIQUIDITY,
            (outcome as CloseOutcome.Unavailable).reason)
    }

    @Test
    fun `inconsistent recovered position state is rejected`() {
        val completed = requireNotNull(ReplayRunner().run(dataset(), SimulationConfig.mvp()).scenarios.first().position)
        assertFailsWith<IllegalArgumentException> {
            PositionService().close(completed.copy(close = null), OrderedMarketData(emptyList()), SimulationConfig.mvp())
        }
    }

    @Test
    fun `a closed position cannot realize pnl a second time`() {
        val dataset = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
            ReplayDatasetLoader(JsonMapper.builder().build()).load(requireNotNull(it))
        }
        val position = requireNotNull(ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.first().position)
        val outcome = PositionService().close(position, OrderedMarketData(emptyList()), SimulationConfig.mvp())
        assertEquals(position, (outcome as CloseOutcome.AlreadyClosed).position)
    }
}
