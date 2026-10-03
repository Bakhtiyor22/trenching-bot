package com.trenching.bot.papertrading

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.papertrading.application.ObservationSelector
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ObservationSelectorTest {
    private val observation = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
        ReplayDatasetLoader(JsonMapper.builder().build()).load(requireNotNull(it)).observations.first()
    }
    private val selector = ObservationSelector()
    private val config = SimulationConfig.mvp()

    @Test
    fun `wait and age limits are inclusive and equal slot ties use identity`() {
        val later = observation.copy(id = "b", slot = Slot.of(103), priceSlot = Slot.of(101))
        val earlierId = later.copy(id = "a")
        val result = selector.select(OrderedMarketData(listOf(later, earlierId)), observation.pair, Slot.of(101), config, BigDecimal("100"))
        assertEquals("a", (result as ObservationSelection.Selected).observation.id)
        val outside = selector.select(OrderedMarketData(listOf(later.copy(slot = Slot.of(104)))), observation.pair, Slot.of(101), config, BigDecimal("100"))
        assertEquals(RejectionReason.MISSING_OBSERVATION, (outside as ObservationSelection.Unavailable).reason)
    }

    @Test
    fun `stale invalid and illiquid data have explicit reasons`() {
        listOf(
            observation.copy(priceSlot = Slot.of(98)) to RejectionReason.STALE_OBSERVATION,
            observation.copy(quality = ObservationQuality.INVALID) to RejectionReason.INVALID_QUALITY,
            observation.copy(price = BigDecimal.ZERO) to RejectionReason.INVALID_QUALITY,
            observation.copy(commitment = Commitment.PROCESSED) to RejectionReason.INVALID_QUALITY,
            observation.copy(priceSlot = Slot.of(102)) to RejectionReason.INVALID_QUALITY,
            observation.copy(liquidityQuote = BigDecimal("99.99")) to RejectionReason.INSUFFICIENT_LIQUIDITY,
        ).forEach { (input, reason) ->
            val selected = selector.select(OrderedMarketData(listOf(input)), observation.pair, Slot.of(101), config, BigDecimal("100"))
            assertEquals(reason, (selected as ObservationSelection.Unavailable).reason)
        }
    }

    @Test
    fun `invalid data does not mask the first eligible later observation`() {
        val valid = observation.copy(id = "later", slot = Slot.of(102), priceSlot = Slot.of(102))
        val selected = selector.select(OrderedMarketData(listOf(valid, observation.copy(quality = ObservationQuality.INVALID))),
            observation.pair, Slot.of(101), config, BigDecimal("100"))
        assertEquals("later", (selected as ObservationSelection.Selected).observation.id)
    }

    @Test
    fun `conflicting observation identity is rejected rather than overwritten`() {
        assertFailsWith<IllegalArgumentException> {
            OrderedMarketData(listOf(observation, observation.copy(price = BigDecimal("12"))))
        }
    }
}
