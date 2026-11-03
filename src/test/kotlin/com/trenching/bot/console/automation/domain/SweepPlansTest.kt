package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class SweepPlansTest {
    private val agreed = SimulationConfig.agreed()
    private val entries = SweepPlans.DECU_SWEEP_1.entries.associate { it.label to it.config }

    @Test
    fun `decu-sweep-1 lists V00 to V09 in order`() {
        assertEquals("decu-sweep-1", SweepPlans.DECU_SWEEP_1.id)
        assertEquals((0..9).map { "V0$it" }, SweepPlans.DECU_SWEEP_1.entries.map { it.label })
    }

    @Test
    fun `V00 is the agreed configuration itself`() {
        assertEquals(agreed, entries.getValue("V00"))
    }

    @Test
    fun `every other entry changes exactly one factor of the agreed configuration`() {
        assertEquals(mapOf(
            "V01" to mapOf("dipBps" to 1500),
            "V02" to mapOf("dipBps" to 2500),
            "V03" to mapOf("takeProfitBps" to 3500),
            "V04" to mapOf("takeProfitBps" to 8000),
            "V05" to mapOf("stopLossBps" to 2500),
            "V06" to mapOf("maxHoldMs" to 300_000L, "hardExitStartMs" to 360_000L),
            "V07" to mapOf("dipWindowMs" to 60_000L),
            "V08" to mapOf("minSignalSol" to BigDecimal("1.500000000000")),
            "V09" to mapOf("minMarketCapUsd" to BigDecimal("20000.000000000000")),
        ), entries.filterKeys { it != "V00" }.mapValues { (_, config) -> changesFromAgreed(config) })
    }

    @Test
    fun `a plan is found by its id and an unknown id finds none`() {
        assertSame(SweepPlans.DECU_SWEEP_1, SweepPlans.find("decu-sweep-1"))
        assertNull(SweepPlans.find("decu-sweep-0"))
    }

    private fun changesFromAgreed(config: SimulationConfig): Map<String, Any?> =
        SimulationConfig::class.memberProperties
            .filter { it.get(config) != it.get(agreed) }
            .associate { it.name to it.get(config) }
}
