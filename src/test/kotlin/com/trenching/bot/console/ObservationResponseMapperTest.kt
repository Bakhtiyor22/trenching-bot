package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.TRADER_A
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.ChartFixtures.tradeObservation
import com.trenching.bot.console.mapper.ObservationResponseMapperImpl
import com.trenching.bot.console.mapper.PairResponseMapperImpl
import com.trenching.bot.marketdata.domain.TradeSide
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

class ObservationResponseMapperTest {
    private val mapper = ObservationResponseMapperImpl().also { ReflectionTestUtils.setField(it, "pairResponseMapper", PairResponseMapperImpl()) }

    @Test
    fun `an observation without a trade keeps every field and has a null trade`() {
        val response = mapper.toResponse(observation("o1", at("2026-10-01T00:00:00Z"), "0.0000001", slot = 7))

        assertNull(response.trade)
        assertEquals("o1", response.id)
        assertEquals("0.0000001", response.price)
        assertEquals(7L, response.slot)
        assertEquals("pumpfun-bonding-curve-trade/v1", response.source)
        assertEquals("CONFIRMED", response.commitment)
        assertEquals("LAST_TRADE", response.quality)
    }

    @Test
    fun `a recorded trade is exposed with its raw amounts as strings`() {
        val trade = mapper.toResponse(tradeObservation("o2", at("2026-10-01T00:00:00Z"), TRADER_A, TradeSide.SELL, lamports = 1_500_000_000L, tokens = 7L)).trade

        assertEquals(TRADER_A, trade?.trader)
        assertEquals("SELL", trade?.side)
        assertEquals("1500000000", trade?.solLamports)
        assertEquals("7", trade?.tokenRaw)
    }
}
