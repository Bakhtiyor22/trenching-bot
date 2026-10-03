package com.trenching.bot.replay.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.math.RoundingMode

/** Summary numbers of one run; [realizedPnl] is in units of [quoteMint]. */
data class RunTotals(
    val quoteMint: MintAddress,
    val scenarioCount: Int,
    val filledCount: Int,
    val rejectedCount: Int,
    val openCount: Int,
    val closedCount: Int,
    val realizedPnl: BigDecimal,
) {
    companion object {
        fun of(report: SimulationReport): RunTotals {
            val outcomes = report.scenarios.map { it.outcome }
            val positions = report.scenarios.mapNotNull { it.position }
            val closed = positions.filter { position ->
                when (position.state) {
                    PositionState.CLOSED -> true
                    PositionState.OPEN -> false
                }
            }
            val realizedPnl = closed.map { checkNotNull(it.close) { "Closed position without close positionId=${it.id}" }.realizedPnl }
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .setScale(DecimalPolicy.SCALE, RoundingMode.UNNECESSARY)
            return RunTotals(
                quoteMint = report.configuration.quoteMint,
                scenarioCount = report.scenarios.size,
                filledCount = outcomes.count { it is FillOutcome.Filled },
                rejectedCount = outcomes.count { it is FillOutcome.Rejected },
                openCount = positions.size - closed.size,
                closedCount = closed.size,
                realizedPnl = realizedPnl,
            )
        }
    }
}
