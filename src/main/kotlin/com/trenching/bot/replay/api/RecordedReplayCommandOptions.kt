package com.trenching.bot.replay.api

internal object RecordedReplayCommandOptions {
    const val WALLET_CAPTURE_DIRECTORY = "--wallet-capture-directory"
    const val MARKET_CAPTURE_DIRECTORY = "--market-capture-directory"
    const val RATES_CAPTURE_DIRECTORY = "--rates-capture-directory"
    const val OUTPUT = "--output"
    const val USAGE = "Usage: exportRecordedReplay --wallet-capture-directory <path> --market-capture-directory <path> " +
        "--rates-capture-directory <path> --output <file>"
    const val NOTE = "note=Paper trades against the recorded bonding-curve states, with their fees and a modelled transaction cost: " +
        "modelled paper results, not the wallet's actual profit or executable quotes."
    val OPTIONS = setOf(WALLET_CAPTURE_DIRECTORY, MARKET_CAPTURE_DIRECTORY, RATES_CAPTURE_DIRECTORY, OUTPUT)
}
