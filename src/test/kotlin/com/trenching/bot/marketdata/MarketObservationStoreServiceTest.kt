package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.application.MarketObservationStoreService
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationEntity
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationRepository
import com.trenching.bot.marketdata.mapper.MarketObservationMapper
import com.trenching.bot.marketdata.mapper.MarketObservationMapperImpl
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MarketObservationStoreServiceTest {
    private val real = MarketObservationMapperImpl()
    private val saved = mutableListOf<MarketObservationEntity>()
    private val repository = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketObservationRepository::class.java)) { _, method, args ->
        check(method.name == "saveAllAndFlush") { "Unexpected repository call ${method.name}" }
        @Suppress("UNCHECKED_CAST")
        (args.single() as List<MarketObservationEntity>).also(saved::addAll)
    } as MarketObservationRepository
    private val observation = DipSample.load().observations.first()

    @Test
    fun `a trade is stored in its columns`() {
        MarketObservationStoreService(repository, real).record(RUN_ID, listOf(observation.copy(trade = DipSampleV4.trade(0))))

        val trade = requireNotNull(DipSampleV4.trade(0))
        assertEquals(listOf(trade.trader, trade.side, trade.solLamports, trade.tokenRaw),
            saved.single().let { listOf(it.tradeTrader, it.tradeSide, it.tradeSolLamports, it.tradeTokenRaw) })
    }

    @Test
    fun `an observation without a trade is stored with null trade columns`() {
        MarketObservationStoreService(repository, real).record(RUN_ID, listOf(observation))

        assertNull(saved.single().tradeTrader)
        assertNull(saved.single().tradeSide)
    }

    @Test
    fun `a partially set trade is refused and nothing is stored`() {
        val partial = object : MarketObservationMapper() {
            override fun toEntity(runId: String, observation: MarketObservation): MarketObservationEntity =
                real.toEntity(runId, observation).apply { tradeTokenRaw = null }

            override fun toObservation(entity: MarketObservationEntity): MarketObservation = real.toObservation(entity)
        }

        val error = assertFailsWith<IllegalArgumentException> {
            MarketObservationStoreService(repository, partial).record(RUN_ID, listOf(observation.copy(trade = DipSampleV4.trade(0))))
        }

        assertTrue(error.message.orEmpty().contains("partial trade id=${observation.id}"), error.message)
        assertTrue(saved.isEmpty())
    }

    private companion object {
        val RUN_ID = "a".repeat(64)
    }
}
