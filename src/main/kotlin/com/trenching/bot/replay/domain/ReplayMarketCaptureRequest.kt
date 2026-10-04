package com.trenching.bot.replay.domain

import java.nio.file.Path

data class ReplayMarketCaptureRequest(
    val walletCaptureDirectory: Path,
    val directory: Path,
    val scanPages: Int,
    val maxTransactions: Int,
    val requestBudget: Int,
    val byteBudget: Long,
)
