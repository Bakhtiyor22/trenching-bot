package com.trenching.bot.replay.api

internal object RecordedReplayCommandOptions {
    const val WALLET_CAPTURE_DIRECTORY = "--wallet-capture-directory"
    const val MARKET_CAPTURE_DIRECTORY = "--market-capture-directory"
    const val OUTPUT = "--output"
    const val USAGE = "Usage: exportRecordedReplay --wallet-capture-directory <path> --market-capture-directory <path> --output <file>"
    const val NOTE = "note=Observed bonding-curve trade prices with the simulator's modelled fees and slippage: modelled paper " +
        "results, not the wallet's actual profit or executable quotes."
    val OPTIONS = setOf(WALLET_CAPTURE_DIRECTORY, MARKET_CAPTURE_DIRECTORY, OUTPUT)
}
