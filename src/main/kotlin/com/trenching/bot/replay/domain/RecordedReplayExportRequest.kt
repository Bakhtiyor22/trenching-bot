package com.trenching.bot.replay.domain

import java.nio.file.Path

data class RecordedReplayExportRequest(
    val walletCaptureDirectory: Path,
    val marketCaptureDirectory: Path,
    val ratesCaptureDirectory: Path,
    val output: Path,
)
