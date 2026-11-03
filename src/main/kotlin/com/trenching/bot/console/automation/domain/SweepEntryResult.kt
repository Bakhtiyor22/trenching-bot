package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.domain.RunSummary
import java.math.BigDecimal

/**
 * One sweep entry replayed on one item (plan Rules 21–23). [outcome] is the cycle's close reason, the reason its position stayed
 * open, the reason it bought nothing, [SweepOutcomes.NO_CYCLE], or, without a [runId], the code the entry was refused with.
 * P&L is SOL at scale 12: [realizedPnl] is the closed position's, else 0; [worstCasePnl] is the same, except that a position
 * still open counts at minus its entry's whole cost (notional, entry fee and transaction cost).
 */
data class SweepEntryResult(
    val runId: String?,
    val configurationHash: String?,
    val engineVersion: String?,
    val outcome: String,
    val positionState: PositionState?,
    val signal: Boolean,
    val realizedPnl: BigDecimal,
    val worstCasePnl: BigDecimal,
) {
    companion object {
        private val ZERO: BigDecimal = DecimalPolicy.scale(BigDecimal.ZERO)

        fun refused(code: String): SweepEntryResult = SweepEntryResult(null, null, null, code, null, false, ZERO, ZERO)

        /** [cycle] is the item's cycle in [run], or null when the run holds none. */
        fun ran(run: RunSummary, cycle: SweepCycle?): SweepEntryResult {
            val counted = when (cycle) {
                null -> Counted(SweepOutcomes.NO_CYCLE, null, ZERO, ZERO)
                is SweepCycle.Skipped -> Counted(cycle.reason.name, null, ZERO, ZERO)
                is SweepCycle.Entered -> entered(cycle)
            }
            return SweepEntryResult(run.id, run.configurationHash, run.engineVersion, counted.outcome, counted.positionState,
                SweepOutcomes.signal(counted.outcome), counted.realizedPnl, counted.worstCasePnl)
        }

        private fun entered(cycle: SweepCycle.Entered): Counted {
            val position = cycle.position
            return when (position.state) {
                PositionState.CLOSED -> {
                    val close = checkNotNull(position.close) { "Closed position without a close positionId=${position.id}" }
                    val realized = DecimalPolicy.scale(close.realizedPnl)
                    Counted(close.reason.name, PositionState.CLOSED, realized, realized)
                }
                PositionState.OPEN -> {
                    val reason = checkNotNull(cycle.exitUnavailable) { "Open position without its reason positionId=${position.id}" }
                    Counted(reason.name, PositionState.OPEN, ZERO, DecimalPolicy.scale(position.fill.costBasis.negate()))
                }
            }
        }
    }

    private data class Counted(val outcome: String, val positionState: PositionState?, val realizedPnl: BigDecimal, val worstCasePnl: BigDecimal)
}
