package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal

/** The sweep plans automation can run, by id. Each entry changes one factor of [SimulationConfig.agreed]. */
object SweepPlans {
    private val AGREED: SimulationConfig = SimulationConfig.agreed()

    val DECU_SWEEP_1: SweepPlan = SweepPlan("decu-sweep-1", listOf(
        SweepEntry("V00", AGREED),
        SweepEntry("V01", agreedWith(dipBps = 1500)),
        SweepEntry("V02", agreedWith(dipBps = 2500)),
        SweepEntry("V03", agreedWith(takeProfitBps = 3500)),
        SweepEntry("V04", agreedWith(takeProfitBps = 8000)),
        SweepEntry("V05", agreedWith(stopLossBps = 2500)),
        SweepEntry("V06", agreedWith(maxHoldMs = 300_000, hardExitStartMs = 360_000)),
        SweepEntry("V07", agreedWith(dipWindowMs = 60_000)),
        SweepEntry("V08", agreedWith(minSignalSol = BigDecimal("1.5"))),
        SweepEntry("V09", agreedWith(minMarketCapUsd = BigDecimal("20000"))),
    ))

    private val PLANS: Map<String, SweepPlan> = listOf(DECU_SWEEP_1).associateBy { it.id }

    fun find(id: String): SweepPlan? = PLANS[id]

    private fun agreedWith(
        dipBps: Int = AGREED.dipBps,
        takeProfitBps: Int = AGREED.takeProfitBps,
        stopLossBps: Int = AGREED.stopLossBps,
        minMarketCapUsd: BigDecimal = AGREED.minMarketCapUsd,
        dipWindowMs: Long? = AGREED.dipWindowMs,
        maxHoldMs: Long? = AGREED.maxHoldMs,
        hardExitStartMs: Long? = AGREED.hardExitStartMs,
        minSignalSol: BigDecimal? = AGREED.minSignalSol,
    ): SimulationConfig = SimulationConfig.of(
        AGREED.notional, AGREED.maxAgeSlots, dipBps, takeProfitBps, stopLossBps, minMarketCapUsd, AGREED.horizonSlots,
        AGREED.latencySlots, AGREED.transactionCostSol, AGREED.strategyName, AGREED.strategyVersion, AGREED.quoteMint,
        dipWindowMs, maxHoldMs, hardExitStartMs, AGREED.priceMeasure, AGREED.followWalletSells, minSignalSol,
    )
}
