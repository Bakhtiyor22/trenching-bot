package com.trenching.bot.console.solana

/** The only JSON-RPC methods the probe may call. All are reads (strict-rules B6). */
object SolanaRpc {
    const val GET_HEALTH = "getHealth"
    const val GET_VERSION = "getVersion"
    const val GET_SLOT = "getSlot"
    const val GET_GENESIS_HASH = "getGenesisHash"
    const val GET_SIGNATURES_FOR_ADDRESS = "getSignaturesForAddress"
    const val GET_TRANSACTION = "getTransaction"

    const val COMMITMENT = "commitment"
    const val CONFIRMED = "confirmed"
    const val HEALTHY = "ok"
    const val SIGNATURE_LIMIT = 5
    const val MAX_SUPPORTED_TRANSACTION_VERSION = 1
}
