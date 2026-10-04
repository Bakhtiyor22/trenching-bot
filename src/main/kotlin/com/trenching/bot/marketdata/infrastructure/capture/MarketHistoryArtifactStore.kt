package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureArtifact
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
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.domain.MarketWindowRules
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

internal class MarketHistoryArtifactStore(private val mapper: ObjectMapper) {
    private val files = CaptureArtifactStore(mapper)
    private val evidence = MarketTransactionEvidence()

    fun inspect(directory: Path): MarketHistoryCaptureResult =
        MarketHistoryCaptureResult(directory.toAbsolutePath().normalize(), loadIfPresent(directory)
            ?: throw CaptureValidationException("MANIFEST_MISSING"))

    /** Verifies the manifest and every artifact; [onArtifact] receives each artifact only after its hash and identity check. */
    fun loadIfPresent(directory: Path, onArtifact: (MarketTransactionRecord, JsonNode) -> Unit = { _, _ -> }): MarketHistoryManifest? {
        val root = files.root(directory, create = false)
        val inner = files.readManifest(root, MarketCaptureFormat.MANIFEST) ?: return null
        ManifestFields.requireVersion(inner, MarketCaptureFormat.VERSION)
        val manifest = parse(inner)
        validate(manifest)
        manifest.records.forEach { record ->
            val artifact = record.artifact ?: return@forEach
            val sha256 = checkNotNull(record.sha256) { "Validated record has an artifact without a hash signature=${record.signature}" }
            val node = files.readArtifact(root, artifact, record.bytes, sha256)
            if (evidence.classify(node, record.signature, record.slot) != record.outcome) fail("ARTIFACT_IDENTITY")
            onArtifact(record, node)
        }
        return manifest
    }

    fun save(directory: Path, manifest: MarketHistoryManifest) {
        files.writeManifest(files.root(directory, create = true), MarketCaptureFormat.MANIFEST, mapper.valueToTree(manifest))
    }

    fun lock(directory: Path): AutoCloseable = files.lock(directory)

    fun writeTransaction(directory: Path, signature: String, raw: JsonNode): CaptureArtifact =
        files.writeTransaction(directory, signature, raw)

    private fun parse(node: JsonNode): MarketHistoryManifest = try {
        val parameters = node.path("windowParameters").let { value ->
            MarketCaptureWindowParameters(integer(value, "firstEntryOffset"), integer(value, "lastEntryOffset"),
                integer(value, "maxWaitSlots"), integer(value, "maxAgeSlots"), integer(value, "holdingSlots"))
        }
        val windows = array(node, "windows", MarketCaptureFormat.MAX_WINDOWS).map(::window)
        val records = array(node, "records", MarketCaptureFormat.MAX_TRANSACTIONS).map { value -> MarketTransactionRecord(
            string(value, "signature"), integer(value, "slot"), MarketTransactionOutcome.valueOf(string(value, "outcome")),
            optionalString(value, "artifact"), optionalString(value, "sha256"), integer(value, "bytes"),
        ) }
        MarketHistoryManifest(smallInteger(node, "schemaVersion"), string(node, "genesisHash"), string(node, "commitment"),
            string(node, "capturedAt"), parameters, smallInteger(node, "scanPageLimit"), smallInteger(node, "maxTransactions"),
            windows, records, boolean(node, "acquisitionComplete"), boolean(node, "complete"),
            smallInteger(node, "requestsUsed"), integer(node, "bytesUsed"))
    } catch (_: IllegalArgumentException) { fail("MANIFEST_FIELDS") }

    private fun window(node: JsonNode): MarketWindowRecord = MarketWindowRecord(
        string(node, "baseMint"), string(node, "quoteMint"), integer(node, "fromSlot"), integer(node, "toSlot"),
        string(node, "untilSignature"), MarketWindowCoverage.valueOf(string(node, "coverage")),
        smallInteger(node, "pagesScanned"), smallInteger(node, "scanned"), smallInteger(node, "skippedFailed"),
        smallInteger(node, "skippedNewer"), smallInteger(node, "selected"),
        array(node, "selection", MarketCaptureFormat.MAX_TRANSACTIONS).map(::listed),
        node.path("scanCursor").takeUnless { it.isNull || it.isMissingNode }?.let(::listed),
    )

    private fun listed(node: JsonNode): MarketListedSignature = MarketListedSignature(string(node, "signature"), integer(node, "slot"))

    private fun validate(manifest: MarketHistoryManifest) {
        if (manifest.genesisHash != ChainCaptureFormat.MAINNET_GENESIS || manifest.commitment != ChainCaptureFormat.COMMITMENT ||
            manifest.scanPageLimit !in 1..MarketCaptureFormat.MAX_SCAN_PAGES ||
            manifest.maxTransactions !in 1..MarketCaptureFormat.MAX_TRANSACTIONS || manifest.records.size > manifest.maxTransactions ||
            manifest.windows.isEmpty() || !MarketWindowRules.isValid(manifest.windowParameters) ||
            manifest.requestsUsed < 0 || manifest.bytesUsed < 0) fail("MANIFEST_BOUNDS")
        try { Instant.parse(manifest.capturedAt) } catch (_: DateTimeParseException) { fail("MANIFEST_TIME") }
        val windows = try { manifest.windows.map(MarketWindowRecords::window) } catch (_: IllegalArgumentException) { fail("WINDOW_IDENTITY") }
        if (windows != MarketWindowRules.canonical(windows) || !MarketWindowRules.isDisjoint(windows)) fail("WINDOW_ORDER")
        manifest.windows.forEach { if (!consistentWindow(it, manifest.scanPageLimit)) fail("WINDOW_STATE") }
        if (!sequentialDiscovery(manifest.windows)) fail("WINDOW_STATE")
        validateRecords(manifest)
        val acquired = manifest.windows.none { it.coverage == MarketWindowCoverage.PENDING } &&
            manifest.records.none { it.outcome in MarketCaptureFormat.UNRESOLVED_OUTCOMES }
        if (manifest.acquisitionComplete != acquired ||
            manifest.complete != (acquired && manifest.windows.all { it.coverage == MarketWindowCoverage.COMPLETE })) fail("COMPLETE_STATUS")
    }

    private fun consistentWindow(window: MarketWindowRecord, scanPageLimit: Int): Boolean {
        val cursor = window.scanCursor
        val selection = window.selection
        val counts = window.scanned <= window.pagesScanned * ChainCaptureFormat.SIGNATURE_PAGE_SIZE &&
            window.skippedFailed.toLong() + window.skippedNewer + window.selected <= window.scanned &&
            window.pagesScanned <= scanPageLimit && (cursor != null) == (window.scanned > 0) &&
            (cursor == null || (validSignature(cursor.signature) && cursor.slot >= window.fromSlot))
        val listed = selection.map { it.signature }.distinct().size == selection.size &&
            selection.all { validSignature(it.signature) && it.slot in window.fromSlot..window.toSlot && it.slot >= (cursor?.slot ?: it.slot) } &&
            selection.zipWithNext().none { (newer, older) -> older.slot > newer.slot }
        val coverage = when (window.coverage) {
            MarketWindowCoverage.PENDING -> window.pagesScanned < scanPageLimit && selection.size == window.selected
            MarketWindowCoverage.COMPLETE -> window.pagesScanned >= 1 && selection.size == window.selected
            MarketWindowCoverage.COVERAGE_INCOMPLETE -> window.pagesScanned == scanPageLimit && selection.size == window.selected
            MarketWindowCoverage.OVER_CAP -> window.pagesScanned >= 1 && selection.isEmpty() && window.selected > 0
        }
        return counts && listed && coverage
    }

    /**
     * Windows start one at a time in order, so the windows not yet started form a suffix. An extended scan can reopen a
     * started window before later finished ones, so a pending window with pages may precede them.
     */
    private fun sequentialDiscovery(windows: List<MarketWindowRecord>): Boolean =
        windows.dropWhile { it.pagesScanned > 0 }.all { it.coverage == MarketWindowCoverage.PENDING && it.pagesScanned == 0 }

    private fun validateRecords(manifest: MarketHistoryManifest) {
        if (manifest.records.map { MarketListedSignature(it.signature, it.slot) } != MarketWindowRecords.admitted(manifest.windows)) fail("RECORD_SELECTION")
        manifest.records.forEach { record ->
            val requiresArtifact = record.outcome == MarketTransactionOutcome.CAPTURED
            if (requiresArtifact != (record.artifact != null) || requiresArtifact != (record.sha256 != null)) fail("RECORD_ARTIFACT")
            if (record.artifact != null && record.artifact != files.transactionPath(record.signature)) fail("ARTIFACT_PATH")
            if (record.sha256 != null && !record.sha256.matches(SHA256)) fail("ARTIFACT_HASH")
            if (!requiresArtifact && record.bytes != 0L) fail("RECORD_BYTES")
        }
    }

    private fun validSignature(value: String): Boolean = try {
        TransactionSignature.of(value)
        true
    } catch (_: IllegalArgumentException) { false }

    private fun array(node: JsonNode, field: String, maxSize: Int): List<JsonNode> = ManifestFields.array(node, field, maxSize)

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
