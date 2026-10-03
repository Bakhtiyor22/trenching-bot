package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import java.math.BigDecimal

/**
 * Stored totals over every run. [runsSince] counts runs created at or after the requested instant;
 * realized P&L stays per quote mint because amounts in different mints cannot be added.
 */
data class RunAggregates(
    val totalRuns: Long,
    val runsSince: Long,
    val filledCount: Long,
    val rejectedCount: Long,
    val openCount: Long,
    val closedCount: Long,
    val realizedPnlByQuoteMint: Map<MintAddress, BigDecimal>,
)
