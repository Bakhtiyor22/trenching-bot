package com.trenching.bot.replay.infrastructure.persistence

import java.math.BigDecimal

/** Sums of the stored run summaries sharing one quote mint. */
interface SimulationRunQuoteMintTotalsRow {
    val quoteMint: String
    val runCount: Long
    val filledCount: Long
    val rejectedCount: Long
    val openCount: Long
    val closedCount: Long
    val realizedPnl: BigDecimal
}
