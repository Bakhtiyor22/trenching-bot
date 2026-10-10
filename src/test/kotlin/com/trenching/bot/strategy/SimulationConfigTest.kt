package com.trenching.bot.strategy

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class SimulationConfigTest {
    private fun config(
        notional: String = "0.1", maxAgeSlots: Long = 2, dipBps: Int = 1500, takeProfitBps: Int = 3000, stopLossBps: Int = 1500,
        minMarketCapUsd: String = "10000", horizonSlots: Long = 4500, latencySlots: Long = 1, transactionCostSol: String = "0.001",
    ) = SimulationConfig.of(BigDecimal(notional), maxAgeSlots, dipBps, takeProfitBps, stopLossBps, BigDecimal(minMarketCapUsd),
        horizonSlots, latencySlots, BigDecimal(transactionCostSol))

    @Test
    fun `the default is the user's dip strategy with one slot of latency and a 0,001 SOL transaction cost`() {
        val mvp = SimulationConfig.mvp()
        assertEquals(config(), mvp)
        assertEquals("0.100000000000", mvp.notional.toPlainString())
        assertEquals(listOf(1500, 3000, 1500), listOf(mvp.dipBps, mvp.takeProfitBps, mvp.stopLossBps))
        assertEquals("10000.000000000000", mvp.minMarketCapUsd.toPlainString())
        assertEquals(4500L, mvp.horizonSlots)
        assertEquals(1L, mvp.latencySlots)
        assertEquals("0.001000000000", mvp.transactionCostSol.toPlainString())
        assertEquals("dip-entry", mvp.strategyName)
        assertEquals("1", mvp.strategyVersion)
    }

    @Test
    fun `notional must be a whole number of lamports`() {
        assertEquals("0.000000001000", config(notional = "0.000000001").notional.toPlainString())
        assertEquals("1.000000000000", config(notional = "1.0000000000000").notional.toPlainString())
        listOf("0.0000000001", "1.0000000005", "0.0000000000001", "0", "-0.1")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { config(notional = it) } }
    }

    @Test
    fun `latency must lie between 1 and 150 slots`() {
        config(latencySlots = 1)
        config(latencySlots = 150)
        assertFailsWith<IllegalArgumentException> { config(latencySlots = 0) }
        assertFailsWith<IllegalArgumentException> { config(latencySlots = 151) }
    }

    @Test
    fun `the transaction cost lies between 0 and 0,1 SOL at scale 12`() {
        assertEquals("0.000000000000", config(transactionCostSol = "0").transactionCostSol.toPlainString())
        assertEquals("0.100000000000", config(transactionCostSol = "0.1").transactionCostSol.toPlainString())
        assertFailsWith<IllegalArgumentException> { config(transactionCostSol = "-0.000000000001") }
        assertFailsWith<IllegalArgumentException> { config(transactionCostSol = "0.100000000001") }
    }

    @Test
    fun `levels, market cap and slot bounds must be valid`() {
        listOf<() -> SimulationConfig>(
            { config(maxAgeSlots = -1) }, { config(dipBps = 0) }, { config(takeProfitBps = 10000) }, { config(stopLossBps = 0) },
            { config(minMarketCapUsd = "-1") }, { config(minMarketCapUsd = "1000000000.000000000001") },
            { config(horizonSlots = 0) }, { config(horizonSlots = 4501) },
        ).forEach { assertFailsWith<IllegalArgumentException> { it() } }
    }
}
