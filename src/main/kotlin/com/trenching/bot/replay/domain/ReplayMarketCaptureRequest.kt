package com.trenching.bot.replay.domain

import java.nio.file.Path
import java.time.Duration

data class ReplayMarketCaptureRequest(
    val walletCaptureDirectory: Path,
    val directory: Path,
    val scanPages: Int,
    val maxTransactions: Int,
    val requestBudget: Int,
    val byteBudget: Long,
    /** This invocation's deadline, or null for the transport default. */
    val operationTimeout: Duration? = null,
)
