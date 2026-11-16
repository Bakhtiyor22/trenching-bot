package com.trenching.bot.replay.domain

import java.math.BigDecimal

/**
 * How a stored run ended, derived only from its stored totals. A run with no filled order is [NO_ENTRY] whatever else it
 * holds; otherwise any open position makes it [OPEN]; only a fully closed run is judged by the sign of its realized P&L.
 */
enum class RunStatus {
    PROFIT,
    LOSS,
    FLAT,
    NO_ENTRY,
    OPEN;

    companion object {
        fun of(filledCount: Int, openCount: Int, realizedPnl: BigDecimal): RunStatus = when {
            filledCount == 0 -> NO_ENTRY
            openCount > 0 -> OPEN
            realizedPnl.signum() > 0 -> PROFIT
            realizedPnl.signum() < 0 -> LOSS
            else -> FLAT
        }
    }
}
