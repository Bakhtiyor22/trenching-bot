package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.replay.domain.RunStatus
import java.math.BigDecimal

/** The runs of one status and quote mint that a search matches, before the query's statuses are applied. */
data class RunStatusAggregate(val status: RunStatus, val quoteMint: String, val runs: Long, val realizedPnl: BigDecimal)
