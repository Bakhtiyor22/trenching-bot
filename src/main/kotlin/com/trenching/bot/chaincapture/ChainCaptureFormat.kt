package com.trenching.bot.chaincapture

object ChainCaptureFormat {
    const val MAINNET_GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
    const val COMMITMENT = "confirmed"
    const val SIGNATURE_PAGE_SIZE = 1000
    const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

    /** A market manifest lists up to 50,000 transactions and their window selections. */
    const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
    const val MAX_SUPPORTED_TRANSACTION_VERSION = 1
    const val CAPTURE_LOCK = ".capture.lock"
    const val GET_GENESIS = "getGenesisHash"
    const val GET_SIGNATURES = "getSignaturesForAddress"
    const val GET_TRANSACTION = "getTransaction"
    const val GET_ACCOUNT_INFO = "getAccountInfo"
    const val GET_SLOT = "getSlot"
    const val GET_PERFORMANCE_SAMPLES = "getRecentPerformanceSamples"
    const val GET_BLOCK_TIME = "getBlockTime"
    const val GET_BLOCKS = "getBlocks"

    /**
     * How far below a skipped slot a block time read looks for the nearest produced block: about 40 s of slots, far beyond the
     * few consecutive leader slots mainnet skips, while one `getBlocks` reply stays small.
     */
    const val BLOCK_TIME_LOOKBACK_SLOTS = 100L

    /** One-minute samples averaged into the recent slot duration: enough to smooth a slow minute, recent enough to matter. */
    const val PERFORMANCE_SAMPLES = 5
    val READ_METHODS = setOf(GET_GENESIS, GET_SIGNATURES, GET_TRANSACTION, GET_ACCOUNT_INFO, GET_SLOT, GET_PERFORMANCE_SAMPLES,
        GET_BLOCK_TIME, GET_BLOCKS)
}
