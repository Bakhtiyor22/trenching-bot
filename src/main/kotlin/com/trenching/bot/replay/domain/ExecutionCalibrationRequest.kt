package com.trenching.bot.replay.domain

import java.nio.file.Path

/** Absolute, normalized paths: the wallet and market captures to calibrate against and the report file to write. */
data class ExecutionCalibrationRequest(
    val walletCaptureDirectory: Path,
    val marketCaptureDirectory: Path,
    val report: Path,
)
