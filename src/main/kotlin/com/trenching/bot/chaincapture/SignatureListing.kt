package com.trenching.bot.chaincapture

import com.trenching.bot.common.TransactionSignature
import java.time.Instant
import java.util.Objects
import tools.jackson.databind.JsonNode

/**
 * One listed transaction. [blockTime] is the listing's, null when the provider has none for the slot. It is not part of the
 * entry's identity: scans compare an entry they read again with one rebuilt from a stored selection, which keeps no time.
 */
class SignatureListingEntry(val signature: String, val slot: Long, val failed: Boolean, val blockTime: Instant? = null) {
    override fun equals(other: Any?): Boolean =
        other is SignatureListingEntry && signature == other.signature && slot == other.slot && failed == other.failed

    override fun hashCode(): Int = Objects.hash(signature, slot, failed)

    override fun toString(): String = "SignatureListingEntry(signature=$signature, slot=$slot, failed=$failed, blockTime=$blockTime)"
}

/** Parses and checks one newest-first `getSignaturesForAddress` page; memo and other listing text is never kept. */
object SignatureListing {
    fun parse(page: JsonNode): List<SignatureListingEntry> {
        if (!page.isArray || page.size() > ChainCaptureFormat.SIGNATURE_PAGE_SIZE) throw CaptureValidationException("SIGNATURE_PAGE")
        return page.toList().map(::entry)
    }

    /** True when [entries] never get newer, starting at or below the slot of the entry the page continues from. */
    fun isNewestFirst(entries: List<SignatureListingEntry>, cursorSlot: Long?): Boolean =
        (cursorSlot == null || entries.isEmpty() || entries.first().slot <= cursorSlot) &&
            entries.zipWithNext().none { (newer, older) -> older.slot > newer.slot }

    private fun entry(node: JsonNode): SignatureListingEntry {
        val signature = node.path("signature")
        val slot = node.path("slot")
        if (!signature.isString || !slot.isIntegralNumber || !slot.canConvertToLong() || slot.asLong() < 0 || !node.has("err")) {
            throw CaptureValidationException("SIGNATURE_ENTRY")
        }
        try { TransactionSignature.of(signature.asString()) } catch (_: IllegalArgumentException) {
            throw CaptureValidationException("SIGNATURE_ENCODING")
        }
        return SignatureListingEntry(signature.asString(), slot.asLong(), failed = !node.path("err").isNull, blockTime = blockTime(node.path("blockTime")))
    }

    private fun blockTime(node: JsonNode): Instant? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isIntegralNumber || !node.canConvertToLong()) throw CaptureValidationException("SIGNATURE_ENTRY")
        return Instant.ofEpochSecond(node.asLong())
    }
}
