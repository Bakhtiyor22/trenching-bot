package com.trenching.bot.console.automation.infrastructure.persistence

import java.math.BigDecimal

/** The sweep rows of one [status], a [com.trenching.bot.replay.domain.RunStatus] name, with their summed P&L. */
interface AutomationSweepStatusRow {
    val status: String
    val runs: Long
    val realizedPnl: BigDecimal
    val worstCasePnl: BigDecimal
}
