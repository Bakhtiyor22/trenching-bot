package com.trenching.bot.papertrading

import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.application.FillModel
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class FillModelTest {
    private val dataset = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
        ReplayDatasetLoader(JsonMapper.builder().build()).load(requireNotNull(it))
    }
    private val config = SimulationConfig.mvp()
    private val intent = ReplayRunner().run(dataset, config).scenarios.first().intent

    private fun fillAt(price: BigDecimal) =
        FillModel().fill(intent, OrderedMarketData(listOf(dataset.observations.first().copy(price = price))), config)

    @Test
    fun `prices and quantities that round to zero cannot create a fill`() {
        listOf(
            BigDecimal("1E-40") to RejectionReason.INVALID_QUALITY,
            BigDecimal("1000000000000000") to RejectionReason.ZERO_QUANTITY,
        ).forEach { (price, reason) ->
            assertEquals(reason, (fillAt(price) as FillOutcome.Rejected).reason)
        }
    }

    @Test
    fun `memecoin scale prices keep every significant digit`() {
        val fill = (fillAt(BigDecimal("0.000000000012345")) as FillOutcome.Filled).fill
        assertEquals(0, BigDecimal("0.000000000012406725").compareTo(fill.executionPrice))
        assertEquals(BigDecimal("8060144800501.341006591183"), fill.quantity)
    }
}
