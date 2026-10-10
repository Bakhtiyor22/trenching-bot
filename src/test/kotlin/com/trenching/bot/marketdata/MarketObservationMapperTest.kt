package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationEntity
import com.trenching.bot.marketdata.mapper.MarketObservationMapperImpl
import com.trenching.bot.support.DipSample
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
