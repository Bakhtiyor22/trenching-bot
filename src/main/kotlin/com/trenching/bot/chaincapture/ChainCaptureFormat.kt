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
    val READ_METHODS = setOf(GET_GENESIS, GET_SIGNATURES, GET_TRANSACTION, GET_ACCOUNT_INFO, GET_SLOT)
}
