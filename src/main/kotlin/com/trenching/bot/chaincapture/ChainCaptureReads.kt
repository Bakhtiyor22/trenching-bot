package com.trenching.bot.chaincapture

import com.trenching.bot.common.Base58
import java.time.Duration
import tools.jackson.databind.JsonNode

/** The read requests captures make, with the shared commitment and transaction-version parameters. */
object ChainCaptureReads {
    private const val PERFORMANCE_RESPONSE = "PERFORMANCE_RESPONSE"

    /** Returns the endpoint's genesis hash after checking that it is mainnet. */
    fun requireMainnet(rpc: SolanaReadRpc, budget: CaptureBudget): String {
        val genesis = rpc.call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget)
        if (!genesis.isString || genesis.asString() != ChainCaptureFormat.MAINNET_GENESIS) {
            throw CaptureValidationException("MAINNET_REQUIRED")
        }
        return genesis.asString()
    }

    /** One newest-first page of up to [limit] signatures older than [before] and newer than [until]. */
    fun signaturePage(
        rpc: SolanaReadRpc,
        address: String,
        before: String?,
        until: String?,
        budget: CaptureBudget,
        limit: Int = ChainCaptureFormat.SIGNATURE_PAGE_SIZE,
    ): JsonNode {
        require(limit in 1..ChainCaptureFormat.SIGNATURE_PAGE_SIZE) { "Signature page limit out of range limit=$limit" }
        val options = mutableMapOf<String, Any>("commitment" to ChainCaptureFormat.COMMITMENT, "limit" to limit)
        before?.let { options["before"] = it }
        until?.let { options["until"] = it }
        return rpc.call(ChainCaptureFormat.GET_SIGNATURES, listOf(address, options), budget)
    }

    /** The newest slot the endpoint has at the capture commitment; every signature through it is listed from then on. */
    fun confirmedTip(rpc: SolanaReadRpc, budget: CaptureBudget): Long {
        val slot = rpc.call(ChainCaptureFormat.GET_SLOT, listOf(mapOf("commitment" to ChainCaptureFormat.COMMITMENT)), budget)
        if (!slot.isIntegralNumber || !slot.canConvertToLong() || slot.asLong() < 0) throw CaptureValidationException("SLOT_RESPONSE")
        return slot.asLong()
    }

    /**
     * How long a slot has recently taken, from the endpoint's last one-minute performance samples; null when none holds a
     * slot. Mainnet's slots run faster than the nominal 400 ms, so a wait in slots is converted with this.
     */
    fun recentSlotDuration(rpc: SolanaReadRpc, budget: CaptureBudget): Duration? {
        val samples = rpc.call(ChainCaptureFormat.GET_PERFORMANCE_SAMPLES, listOf(ChainCaptureFormat.PERFORMANCE_SAMPLES), budget)
        if (!samples.isArray) throw CaptureValidationException(PERFORMANCE_RESPONSE)
        var slots = 0L
        var seconds = 0L
        for (sample in samples) {
            slots += count(sample, "numSlots")
            seconds += count(sample, "samplePeriodSecs")
        }
        return if (slots == 0L) null else Duration.ofSeconds(seconds).dividedBy(slots)
    }

    private fun count(sample: JsonNode, field: String): Long {
        val value = sample.path(field)
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() < 0) throw CaptureValidationException(PERFORMANCE_RESPONSE)
        return value.asLong()
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
