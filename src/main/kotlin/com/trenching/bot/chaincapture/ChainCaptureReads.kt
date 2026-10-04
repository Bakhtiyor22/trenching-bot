package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/** The three read requests every capture makes, with the shared commitment and transaction-version parameters. */
object ChainCaptureReads {
    /** Returns the endpoint's genesis hash after checking that it is mainnet. */
    fun requireMainnet(rpc: SolanaReadRpc, budget: CaptureBudget): String {
        val genesis = rpc.call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget)
        if (!genesis.isString || genesis.asString() != ChainCaptureFormat.MAINNET_GENESIS) {
            throw CaptureValidationException("MAINNET_REQUIRED")
        }
        return genesis.asString()
    }

    /** One newest-first page of up to [ChainCaptureFormat.SIGNATURE_PAGE_SIZE] signatures older than [before] and newer than [until]. */
    fun signaturePage(rpc: SolanaReadRpc, address: String, before: String?, until: String?, budget: CaptureBudget): JsonNode {
        val options = mutableMapOf<String, Any>("commitment" to ChainCaptureFormat.COMMITMENT, "limit" to ChainCaptureFormat.SIGNATURE_PAGE_SIZE)
        before?.let { options["before"] = it }
        until?.let { options["until"] = it }
        return rpc.call(ChainCaptureFormat.GET_SIGNATURES, listOf(address, options), budget)
    }

    fun transaction(rpc: SolanaReadRpc, signature: String, budget: CaptureBudget): JsonNode =
        rpc.call(ChainCaptureFormat.GET_TRANSACTION, listOf(signature, mapOf(
            "encoding" to "jsonParsed",
            "commitment" to ChainCaptureFormat.COMMITMENT,
            "maxSupportedTransactionVersion" to ChainCaptureFormat.MAX_SUPPORTED_TRANSACTION_VERSION,
        )), budget)
}
