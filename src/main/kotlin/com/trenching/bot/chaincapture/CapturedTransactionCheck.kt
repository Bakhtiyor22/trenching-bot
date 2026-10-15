package com.trenching.bot.chaincapture

import java.math.BigInteger
import tools.jackson.databind.JsonNode

enum class CapturedTransactionShape {
    /** The provider returned null: the transaction is not retained. */
    UNAVAILABLE,
    UNSUPPORTED_VERSION,

    /** Wrong identity, malformed `jsonParsed` structure, or an error that contradicts the successful listing. */
    INVALID,
    VALID,
}

/** Checks a `getTransaction` result against the successful listing entry it was requested for. */
object CapturedTransactionCheck {
    private const val LEGACY_VERSION = "legacy"
    private val MAX_SUPPORTED_VERSION: BigInteger = BigInteger.valueOf(ChainCaptureFormat.MAX_SUPPORTED_TRANSACTION_VERSION.toLong())

    fun shape(node: JsonNode, signature: String, slot: Long): CapturedTransactionShape = classify(node, signature, slot)

    /** Whether [node] is a valid artifact for [signature] at whatever slot its listing gave; the only results worth keeping in a cache. */
    fun isValidFor(node: JsonNode, signature: String): Boolean = classify(node, signature, null) == CapturedTransactionShape.VALID

    private fun classify(node: JsonNode, signature: String, slot: Long?): CapturedTransactionShape {
        if (node.isNull) return CapturedTransactionShape.UNAVAILABLE
        if (!node.isObject || !matchesListing(node, signature, slot)) return CapturedTransactionShape.INVALID
        val version = node.path("version")
        if (version.isIntegralNumber && version.bigIntegerValue() > MAX_SUPPORTED_VERSION) return CapturedTransactionShape.UNSUPPORTED_VERSION
        val supportedVersion = (version.isString && version.asString() == LEGACY_VERSION) || (version.isIntegralNumber && version.bigIntegerValue().signum() >= 0)
        if (!supportedVersion || !ParsedTransactionStructure.isValid(node) || contradictsSuccessfulListing(node)) return CapturedTransactionShape.INVALID
        return CapturedTransactionShape.VALID
    }

    private fun matchesListing(node: JsonNode, signature: String, slot: Long?): Boolean {
        val nodeSlot = node.path("slot")
        val firstSignature = node.path("transaction").path("signatures").path(0)
        return nodeSlot.isIntegralNumber && nodeSlot.canConvertToLong() && (slot == null || nodeSlot.asLong() == slot) &&
            firstSignature.isString && firstSignature.asString() == signature
    }

    private fun contradictsSuccessfulListing(node: JsonNode): Boolean = !node.path("meta").path("err").isNull
}
