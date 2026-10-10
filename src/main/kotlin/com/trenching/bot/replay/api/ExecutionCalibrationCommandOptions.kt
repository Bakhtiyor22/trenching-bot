package com.trenching.bot.replay.api

internal object ExecutionCalibrationCommandOptions {
    const val WALLET_CAPTURE_DIRECTORY = "--wallet-capture-directory"
    const val MARKET_CAPTURE_DIRECTORY = "--market-capture-directory"
    const val REPORT = "--report"
    const val USAGE = "Usage: calibrateExecution --wallet-capture-directory <path> --market-capture-directory <path> --report <file>"
    const val NONE = "none"
    val OPTIONS = setOf(WALLET_CAPTURE_DIRECTORY, MARKET_CAPTURE_DIRECTORY, REPORT)
}
