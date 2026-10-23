package com.trenching.bot.strategy

import com.trenching.bot.common.MintAddress
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SimulationConfigTest {
    private fun config(
        notional: String = "0.1", maxAgeSlots: Long = 2, dipBps: Int = 1500, takeProfitBps: Int = 3000, stopLossBps: Int = 1500,
        minMarketCapUsd: String = "10000", horizonSlots: Long = 4500, latencySlots: Long = 1, transactionCostSol: String = "0.001",
        dipWindowMs: Long? = null, maxHoldMs: Long? = null, hardExitStartMs: Long? = null,
    ) = SimulationConfig.of(BigDecimal(notional), maxAgeSlots, dipBps, takeProfitBps, stopLossBps, BigDecimal(minMarketCapUsd),
        horizonSlots, latencySlots, BigDecimal(transactionCostSol), dipWindowMs = dipWindowMs, maxHoldMs = maxHoldMs,
        hardExitStartMs = hardExitStartMs)

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
        assertNull(mvp.dipWindowMs)
        assertNull(mvp.maxHoldMs)
        assertNull(mvp.hardExitStartMs)
    }

    @Test
    fun `the agreed configuration holds the spec's values with its 30 second dip window and timed exit`() {
        val agreed = SimulationConfig.agreed()

        assertEquals(config(dipBps = 2000, takeProfitBps = 5500, stopLossBps = 4000, dipWindowMs = 30_000, maxHoldMs = 180_000,
            hardExitStartMs = 240_000), agreed)
        assertEquals(SimulationConfig.mvp().maxAgeSlots, agreed.maxAgeSlots)
        assertEquals(MintAddress.WRAPPED_SOL, agreed.quoteMint)
    }

    @Test
    fun `the dip window lies between 1 and 600 seconds`() {
        assertEquals(1_000L, config(dipWindowMs = 1_000).dipWindowMs)
        assertEquals(600_000L, config(dipWindowMs = 600_000).dipWindowMs)
        listOf(999L, 600_001L, 0L, -1L).forEach { assertFailsWith<IllegalArgumentException>("$it") { config(dipWindowMs = it) } }
    }

    @Test
    fun `the maximum hold and the hard exit start lie between 1 second and 1 hour and are set together`() {
        config(maxHoldMs = 1_000, hardExitStartMs = 3_600_000)
        config(maxHoldMs = 3_600_000, hardExitStartMs = 1_000)
        listOf<() -> SimulationConfig>(
            { config(maxHoldMs = 999, hardExitStartMs = 240_000) }, { config(maxHoldMs = 180_000, hardExitStartMs = 3_600_001) },
            { config(maxHoldMs = 180_000) }, { config(hardExitStartMs = 240_000) },
        ).forEach { assertFailsWith<IllegalArgumentException> { it() } }
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
