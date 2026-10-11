package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureArtifactStore
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ManifestFields
import com.trenching.bot.chaincapture.ManifestFields.boolean
import com.trenching.bot.chaincapture.ManifestFields.integer
import com.trenching.bot.chaincapture.ManifestFields.optionalString
import com.trenching.bot.chaincapture.ManifestFields.smallInteger
import com.trenching.bot.chaincapture.ManifestFields.string
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.AccumulatorScan
import com.trenching.bot.walletactivity.CreationSearch
import com.trenching.bot.walletactivity.FirstBuyGap
import com.trenching.bot.walletactivity.VerificationLimit
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.VerificationOutcome
import com.trenching.bot.walletactivity.VerificationRecord
import com.trenching.bot.walletactivity.VerificationResult
import com.trenching.bot.walletactivity.VerificationResultKind
import com.trenching.bot.walletactivity.VerifiedFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.WalletTokenVerification
import tools.jackson.databind.JsonNode

/**
 * The manifest's `verification` section: a strict reader and the structural checks inspection can make without decoding.
 * Whether the stored verdict follows from the evidence is checked where artifacts are decoded.
 */
internal object VerificationSection {
    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val PAGE_SIZE = ChainCaptureFormat.SIGNATURE_PAGE_SIZE
    private const val MAX_PAGES = WalletHistoryCaptureLimits.VERIFICATION_LISTING_PAGES
    private const val TOKEN_ACCOUNT_DIRECTORY = CaptureArtifactStore.TRANSACTIONS_DIRECTORY
    private val FETCHED_WITHOUT_ARTIFACT = setOf(VerificationOutcome.UNAVAILABLE, VerificationOutcome.UNSUPPORTED_VERSION, VerificationOutcome.WITHHELD_UNSAFE_TEXT)

    /** Throws [IllegalArgumentException] for a field of the wrong kind or an unknown enum name. */
    fun parse(node: JsonNode): WalletTokenVerification = WalletTokenVerification(
        firstBuy = optionalObject(node, "firstBuy")?.let { VerifiedFirstBuy(string(it, "signature"), integer(it, "slot"), smallInteger(it, "instructionIndex")) },
        creation = creation(requiredObject(node, "creation")),
        pumpAccumulator = scan(requiredObject(node, "pumpAccumulator")),
        pumpSwapAccumulator = scan(requiredObject(node, "pumpSwapAccumulator")),
        records = ManifestFields.array(node, "records", WalletCaptureFormat.MAX_VERIFICATION_RECORDS).map(::record),
        fetches = smallInteger(node, "fetches"),
        result = optionalObject(node, "result")?.let(::result),
    )

    /** The address whose listing returned a record of [listing]. */
    fun listingAddress(verification: WalletTokenVerification, target: TokenTarget, listing: VerificationListing): String = when (listing) {
        VerificationListing.MINT -> target.mint.value
        VerificationListing.PUMP_ACCUMULATOR -> verification.pumpAccumulator.address
        VerificationListing.PUMPSWAP_ACCUMULATOR -> verification.pumpSwapAccumulator.address
    }

    /** [transactionPath] gives a signature's artifact path in a directory, as the artifact store names it. */
    fun validate(manifest: WalletHistoryManifest, verification: WalletTokenVerification, transactionPath: (String, String) -> String) {
        if (!WalletCaptureFormat.recordsResolved(manifest)) fail("VERIFICATION_STATE")
        val wallet = WalletAddress.of(manifest.wallet)
        val firstBuy = verification.firstBuy
        if (firstBuy == null) {
            if (verification != VerificationStart.of(wallet, null)) fail("VERIFICATION_STATE")
            return
        }
        if (WalletHistorySelection(firstBuy.signature, firstBuy.slot) !in manifest.selection) fail("VERIFICATION_TARGET")
        val expected = VerificationStart.of(wallet, firstBuy)
        listOf(verification.pumpAccumulator to expected.pumpAccumulator, verification.pumpSwapAccumulator to expected.pumpSwapAccumulator).forEach { (scan, start) ->
            if (scan.address != start.address) fail("VERIFICATION_ADDRESS")
            if (!consistentScan(scan, firstBuy)) fail("VERIFICATION_LISTING")
        }
        if (!consistentCreation(verification.creation, firstBuy)) fail("VERIFICATION_LISTING")
        validateRecords(manifest, verification, firstBuy, transactionPath)
        verification.result?.blocking?.forEach(::signature)
    }

    private fun validateRecords(
        manifest: WalletHistoryManifest,
        verification: WalletTokenVerification,
        firstBuy: VerifiedFirstBuy,
        transactionPath: (String, String) -> String,
    ) {
        val records = verification.records
        if (records.distinctBy { it.listing to it.signature }.size != records.size) fail("VERIFICATION_RECORDS")
        val tokenAccountArtifacts = manifest.records.filter { it.artifact != null }.associateBy { it.artifact }
        records.forEach { record ->
            signature(record.signature)
            if (record.slot < 0) fail("SLOT")
            val captured = record.outcome == VerificationOutcome.CAPTURED
            if (captured != (record.artifact != null) || captured != (record.sha256 != null)) fail("RECORD_ARTIFACT")
            if (!captured && record.bytes != 0L) fail("RECORD_BYTES")
            if (record.sha256 != null && !record.sha256.matches(SHA256)) fail("ARTIFACT_HASH")
            record.artifact?.let { artifact ->
                val own = artifact == transactionPath(record.signature, WalletCaptureFormat.VERIFICATION_DIRECTORY)
                val reused = tokenAccountArtifacts[artifact]?.takeIf { artifact == transactionPath(record.signature, TOKEN_ACCOUNT_DIRECTORY) }
                if (!own && reused == null) fail("ARTIFACT_PATH")
                if (reused != null && (reused.sha256 != record.sha256 || reused.bytes != record.bytes)) fail("ARTIFACT_HASH")
            }
        }
        if (records.filter { it.artifact != null }.groupBy { it.artifact }.values.any { shared -> shared.distinctBy { it.sha256 to it.bytes }.size != 1 }) {
            fail("ARTIFACT_HASH")
        }
        val creation = verification.creation
        val candidates = records.filter { it.listing == VerificationListing.MINT }.map { WalletHistorySelection(it.signature, it.slot) }
        if (candidates != creation.candidates.reversed().take(candidates.size)) fail("VERIFICATION_RECORDS")
        val creationSlot = creation.slot
        if (records.any { it.listing != VerificationListing.MINT &&
                (creationSlot == null || it.slot < creationSlot || it.slot > firstBuy.slot || it.signature == firstBuy.signature) }) {
            fail("VERIFICATION_RECORDS")
        }
        val fetched = records.mapNotNull { it.artifact }.filter { it.startsWith(WalletCaptureFormat.VERIFICATION_DIRECTORY + "/") }.distinct().size +
            records.count { it.outcome in FETCHED_WITHOUT_ARTIFACT }
        if (verification.fetches !in fetched..WalletHistoryCaptureLimits.VERIFICATION_FETCHES) fail("VERIFICATION_FETCHES")
    }

    private fun consistentScan(scan: AccumulatorScan, firstBuy: VerifiedFirstBuy): Boolean =
        consistentListing(scan.cursor, scan.pages, scan.entries, scan.oldestSlot) && scan.skippedFailed <= scan.entries &&
            (scan.complete || scan.entries == scan.pages * PAGE_SIZE) &&
            (!scan.listedFirstBuy || (scan.oldestSlot ?: Long.MAX_VALUE) <= firstBuy.slot)

    private fun consistentCreation(creation: CreationSearch, firstBuy: VerifiedFirstBuy): Boolean {
        val candidates = creation.candidates
        val oldest = creation.oldestSlot
        candidates.forEach { signature(it.signature) }
        creation.signature?.let(::signature)
        return consistentListing(creation.cursor, creation.pages, creation.entries, oldest) &&
            creation.historyEnd == (creation.pages > 0 && creation.entries < creation.pages * PAGE_SIZE) &&
            candidates.size <= WalletHistoryCaptureLimits.CREATION_FETCHES && candidates.size <= creation.entries &&
            candidates.distinctBy { it.signature }.size == candidates.size &&
            candidates.zipWithNext().none { (newer, older) -> older.slot > newer.slot } &&
            candidates.all { it.slot <= firstBuy.slot && oldest != null && it.slot >= oldest } &&
            (creation.signature == null) == (creation.slot == null) && (creation.blockTime == null || creation.signature != null) &&
            (creation.cursor?.slot ?: firstBuy.slot) <= firstBuy.slot
    }

    private fun consistentListing(cursor: WalletHistoryScanCursor?, pages: Int, entries: Int, oldestSlot: Long?): Boolean {
        cursor?.let { signature(it.signature) }
        return pages in 0..MAX_PAGES && entries <= pages * PAGE_SIZE && (cursor == null) == (entries == 0) &&
            oldestSlot == cursor?.slot && (oldestSlot ?: 0) >= 0
    }

    private fun creation(node: JsonNode) = CreationSearch(
        cursor = cursor(node), pages = smallInteger(node, "pages"), entries = smallInteger(node, "entries"),
        oldestSlot = optionalInteger(node, "oldestSlot"), historyEnd = boolean(node, "historyEnd"),
        candidates = ManifestFields.array(node, "candidates", WalletHistoryCaptureLimits.CREATION_FETCHES)
            .map { WalletHistorySelection(string(it, "signature"), integer(it, "slot")) },
        signature = optionalString(node, "signature"), slot = optionalInteger(node, "slot"), blockTime = optionalInteger(node, "blockTime"),
    )

    private fun scan(node: JsonNode) = AccumulatorScan(
        address = string(node, "address"), cursor = cursor(node),
        pages = smallInteger(node, "pages"), entries = smallInteger(node, "entries"), skippedFailed = smallInteger(node, "skippedFailed"),
        oldestSlot = optionalInteger(node, "oldestSlot"), listedFirstBuy = boolean(node, "listedFirstBuy"), complete = boolean(node, "complete"),
    )

    private fun record(node: JsonNode) = VerificationRecord(
        signature = string(node, "signature"), slot = integer(node, "slot"), listing = VerificationListing.valueOf(string(node, "listing")),
        outcome = VerificationOutcome.valueOf(string(node, "outcome")), artifact = optionalString(node, "artifact"),
        sha256 = optionalString(node, "sha256"), bytes = integer(node, "bytes"),
    )

    private fun result(node: JsonNode) = VerificationResult(
        kind = VerificationResultKind.valueOf(string(node, "kind")), signature = optionalString(node, "signature"),
        reason = optionalString(node, "reason")?.let(FirstBuyGap::valueOf), limit = optionalString(node, "limit")?.let(VerificationLimit::valueOf),
        blocking = ManifestFields.array(node, "blocking", WalletCaptureFormat.MAX_VERIFICATION_RECORDS).map { value ->
            if (!value.isString) fail("MANIFEST_STRING")
            value.asString()
        },
    )

    private fun cursor(node: JsonNode): WalletHistoryScanCursor? = optionalObject(node, "cursor")?.let(::selectionCursor)

    private fun selectionCursor(node: JsonNode) = WalletHistoryScanCursor(string(node, "signature"), integer(node, "slot"))

    private fun optionalInteger(node: JsonNode, field: String): Long? = if (absent(node.path(field))) null else integer(node, field)

    private fun optionalObject(node: JsonNode, field: String): JsonNode? = if (absent(node.path(field))) null else requiredObject(node, field)

    private fun requiredObject(node: JsonNode, field: String): JsonNode = node.path(field).takeIf { it.isObject } ?: fail("MANIFEST_FIELDS")

    private fun absent(value: JsonNode): Boolean = value.isNull || value.isMissingNode

    private fun signature(value: String) {
        try { TransactionSignature.of(value) } catch (_: IllegalArgumentException) { fail("MANIFEST_IDENTITY") }
    }

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)
}
