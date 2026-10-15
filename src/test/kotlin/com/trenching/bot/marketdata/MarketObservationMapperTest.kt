package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationEntity
import com.trenching.bot.marketdata.mapper.MarketObservationMapperImpl
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketObservationMapperTest {
    private val mapper = MarketObservationMapperImpl()
    private val observation = DipSample.load().observations.first()

    @Test
    fun `the curve state is written to its columns and read back`() {
        val entity = mapper.toEntity(RUN_ID, observation)

        assertEquals(observation.curve.reserves.virtualSol, entity.virtualSolReserves)
        assertEquals(observation.curve.reserves.realToken, entity.realTokenReserves)
        assertEquals(observation.curve.creatorFeeBps, entity.creatorFeeBps)
        assertEquals(observation, mapper.toObservation(entity))
    }

    @Test
    fun `an observation without a trade writes four null trade columns and reads back without one`() {
        val entity = mapper.toEntity(RUN_ID, observation)

        assertEquals(listOf(null, null, null, null), tradeColumns(entity))
        assertNull(mapper.toObservation(entity).trade)
    }

    @Test
    fun `a trade is written to its four columns and read back equal`() {
        val traded = observation.copy(trade = DipSampleV4.trade(0))
        val trade = requireNotNull(traded.trade)

        val entity = mapper.toEntity(RUN_ID, traded)

        assertEquals(listOf(trade.trader, trade.side, trade.solLamports, trade.tokenRaw), tradeColumns(entity))
        assertEquals(traded, mapper.toObservation(entity))
    }

    @Test
    fun `both sides and the largest u64 amounts round trip`() {
        val largest = BigInteger("18446744073709551615")
        TradeSide.entries.forEach { side ->
            val trade = ObservedTrade.of(DipSampleV4.trade(0).trader, side, largest, BigInteger.ONE)
            val traded = observation.copy(trade = trade)

            assertEquals(trade, mapper.toObservation(mapper.toEntity(RUN_ID, traded)).trade)
        }
    }

    @Test
    fun `a row with only some trade columns set is refused`() {
        val clears = listOf<(MarketObservationEntity) -> Unit>(
            { it.tradeTrader = null }, { it.tradeSide = null }, { it.tradeSolLamports = null }, { it.tradeTokenRaw = null })

        clears.forEach { clear ->
            val entity = mapper.toEntity(RUN_ID, observation.copy(trade = DipSampleV4.trade(0))).also(clear)
            val error = assertFailsWith<IllegalArgumentException> { mapper.toObservation(entity) }
            assertTrue(error.message.orEmpty().contains("partial trade id=${observation.id}"), error.message)
        }
        val onlyOne = mapper.toEntity(RUN_ID, observation).apply { tradeSide = TradeSide.BUY }
        assertFailsWith<IllegalArgumentException> { mapper.toObservation(onlyOne) }
    }

    private fun tradeColumns(entity: MarketObservationEntity) =
        listOf(entity.tradeTrader, entity.tradeSide, entity.tradeSolLamports, entity.tradeTokenRaw)

    @Test
    fun `a row missing any curve column is refused`() {
        val clears = listOf<(MarketObservationEntity) -> Unit>(
            { it.virtualSolReserves = null },
            { it.virtualTokenReserves = null },
            { it.realSolReserves = null },
            { it.realTokenReserves = null },
            { it.feeBps = null },
            { it.creatorFeeBps = null },
            { it.curveComplete = null },
        )

        clears.forEach { clear ->
            val entity = mapper.toEntity(RUN_ID, observation).also(clear)
            val error = assertFailsWith<IllegalArgumentException> { mapper.toObservation(entity) }
            assertTrue(error.message.orEmpty().contains("without curve state id=${observation.id}"), error.message)
        }
    }

    @Test
    fun `a row whose stored reserves break the curve's invariants is refused`() {
        val entity = mapper.toEntity(RUN_ID, observation).apply { realSolReserves = virtualSolReserves?.add(BigInteger.ONE) }

        assertFailsWith<IllegalArgumentException> { mapper.toObservation(entity) }
    }

    private companion object {
        val RUN_ID = "a".repeat(64)
    }
}
