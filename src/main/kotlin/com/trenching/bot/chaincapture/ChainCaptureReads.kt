package com.trenching.bot.chaincapture

import com.trenching.bot.common.Base58
import tools.jackson.databind.JsonNode

/** The read requests captures make, with the shared commitment and transaction-version parameters. */
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

    /** The program that owns [mint]'s account, from a `getAccountInfo` that asks for none of its data. */
    fun mintOwner(rpc: SolanaReadRpc, mint: String, budget: CaptureBudget): String {
        val result = rpc.call(ChainCaptureFormat.GET_ACCOUNT_INFO, listOf(mint, mapOf(
            "encoding" to "base64",
            "commitment" to ChainCaptureFormat.COMMITMENT,
            "dataSlice" to mapOf("offset" to 0, "length" to 0),
        )), budget)
        val value = result.path("value")
        if (!result.isObject || value.isMissingNode) throw CaptureValidationException("ACCOUNT_INFO")
        if (value.isNull) throw CaptureValidationException("MINT_NOT_FOUND")
        val owner = value.path("owner")
        if (!value.isObject || !owner.isString) throw CaptureValidationException("ACCOUNT_INFO")
        if (Base58.decode(owner.asString())?.size != PUBLIC_KEY_BYTES) throw CaptureValidationException("ACCOUNT_OWNER_ENCODING")
        return owner.asString()
    }

    private const val PUBLIC_KEY_BYTES = 32
}
