package com.trenching.bot.walletactivity.infrastructure.capture

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
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.*
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

internal class WalletHistoryArtifactStore(private val mapper: ObjectMapper) {
    private val files = CaptureArtifactStore(mapper)

    fun inspect(directory: Path): WalletHistoryCaptureResult =
        WalletHistoryCaptureResult(directory.toAbsolutePath().normalize(), loadIfPresent(directory)
            ?: throw CaptureValidationException("MANIFEST_MISSING"))

    /** Verifies the manifest and every artifact; [onArtifact] receives each artifact only after its hash and identity check. */
    fun loadIfPresent(directory: Path, onArtifact: (WalletHistoryRecord, JsonNode) -> Unit = { _, _ -> }): WalletHistoryManifest? {
        val root = files.root(directory, create = false)
        val inner = files.readManifest(root, WalletCaptureFormat.MANIFEST) ?: return null
        ManifestFields.requireVersion(inner, WalletCaptureFormat.VERSION)
        val manifest = parse(inner)
        validate(manifest)
        manifest.records.forEach { record ->
            val artifact = record.artifact
            if (artifact != null) {
                val sha256 = checkNotNull(record.sha256) { "Validated record has an artifact without a hash signature=${record.signature}" }
                val node = files.readArtifact(root, artifact, record.bytes, sha256)
                val selection = manifest.selection.single { it.signature == record.signature }
                if (WalletTransactionEvidence().classify(node, selection, manifest.wallet) != record.outcome) fail("ARTIFACT_IDENTITY")
                onArtifact(record, node)
            }
        }
        return manifest
    }

    fun save(directory: Path, manifest: WalletHistoryManifest) {
        files.writeManifest(files.root(directory, create = true), WalletCaptureFormat.MANIFEST, mapper.valueToTree(manifest))
    }

    fun lock(directory: Path): AutoCloseable = files.lock(directory)

    fun writeTransaction(directory: Path, signature: String, raw: JsonNode): CaptureArtifact =
        files.writeTransaction(directory, signature, raw)

    private fun parse(node: JsonNode): WalletHistoryManifest = try {
        val selection = array(node, "selection").map { value -> WalletHistorySelection(
            string(value, "signature"), integer(value, "slot"),
        ) }
        val records = array(node, "records").map { value -> WalletHistoryRecord(
            string(value, "signature"), integer(value, "slot"), WalletHistoryOutcome.valueOf(string(value, "outcome")),
            optionalString(value, "artifact"), optionalString(value, "sha256"), integer(value, "bytes"),
        ) }
        val cursor = node.path("scanCursor").takeUnless { it.isNull || it.isMissingNode }
            ?.let { WalletHistoryScanCursor(string(it, "signature"), integer(it, "slot")) }
        WalletHistoryManifest(smallInteger(node, "schemaVersion"), string(node, "wallet"), string(node, "genesisHash"),
            string(node, "commitment"), string(node, "capturedAt"), smallInteger(node, "limit"), smallInteger(node, "scanPageLimit"),
            selection, records, boolean(node, "complete"), boolean(node, "discoveryComplete"),
            optionalString(node, "discoveryStop")?.let(WalletHistoryDiscoveryStop::valueOf),
            smallInteger(node, "scannedSignatures"), smallInteger(node, "skippedFailed"), cursor,
            smallInteger(node, "requestsUsed"), integer(node, "bytesUsed"))
    } catch (_: IllegalArgumentException) { fail("MANIFEST_FIELDS") }

    private fun array(node: JsonNode, field: String): List<JsonNode> = ManifestFields.array(node, field, WalletCaptureFormat.MAX_SELECTION)

    private fun validate(manifest: WalletHistoryManifest) {
        if (manifest.genesisHash != ChainCaptureFormat.MAINNET_GENESIS ||
            manifest.commitment != ChainCaptureFormat.COMMITMENT || manifest.limit !in 1..WalletCaptureFormat.MAX_SELECTION ||
            manifest.selection.size > manifest.limit || manifest.records.size != manifest.selection.size ||
            manifest.selection.map { it.signature }.distinct().size != manifest.selection.size ||
            manifest.requestsUsed < 0 || manifest.bytesUsed < 0) fail("MANIFEST_BOUNDS")
        try {
            WalletAddress.of(manifest.wallet)
            Instant.parse(manifest.capturedAt)
            manifest.selection.forEach { TransactionSignature.of(it.signature); if (it.slot < 0) fail("SLOT") }
            manifest.scanCursor?.let { TransactionSignature.of(it.signature) }
        } catch (_: IllegalArgumentException) { fail("MANIFEST_IDENTITY") }
        catch (_: DateTimeParseException) { fail("MANIFEST_TIME") }
        manifest.records.zip(manifest.selection).forEach { (record, selection) ->
            if (record.signature != selection.signature || record.slot != selection.slot) fail("RECORD_IDENTITY")
            val requiresArtifact = record.outcome in WalletCaptureFormat.ARTIFACT_OUTCOMES
            if (requiresArtifact != (record.artifact != null) || requiresArtifact != (record.sha256 != null)) fail("RECORD_ARTIFACT")
            if (record.artifact != null && record.artifact != files.transactionPath(record.signature)) fail("ARTIFACT_PATH")
            if (record.sha256 != null && !record.sha256.matches(Regex("[0-9a-f]{64}"))) fail("ARTIFACT_HASH")
            if (!requiresArtifact && record.bytes != 0L) fail("RECORD_BYTES")
        }
        if (!consistentDiscovery(manifest)) fail("DISCOVERY_STATE")
        val resolved = manifest.discoveryComplete && manifest.records.none { it.outcome in WalletCaptureFormat.UNRESOLVED_OUTCOMES }
        if (manifest.complete != resolved) fail("COMPLETE_STATUS")
    }

    private fun consistentDiscovery(manifest: WalletHistoryManifest): Boolean {
        val pageSize = ChainCaptureFormat.SIGNATURE_PAGE_SIZE
        val cursor = manifest.scanCursor
        val stop = manifest.discoveryStop
        return manifest.scanPageLimit in 1..WalletCaptureFormat.MAX_SCAN_PAGES &&
            manifest.scannedSignatures <= manifest.scanPageLimit * pageSize &&
            manifest.selection.size + manifest.skippedFailed <= manifest.scannedSignatures &&
            (cursor != null) == (manifest.scannedSignatures > 0) &&
            (cursor == null || (cursor.slot >= 0 && manifest.selection.all { it.slot >= cursor.slot })) &&
            (stop != null) == manifest.discoveryComplete &&
            (stop == WalletHistoryDiscoveryStop.LIMIT_REACHED) == (manifest.selection.size == manifest.limit) &&
            (stop != WalletHistoryDiscoveryStop.SCAN_BOUND || manifest.scannedSignatures == manifest.scanPageLimit * pageSize) &&
            (manifest.discoveryComplete || (manifest.scannedSignatures % pageSize == 0 && manifest.scannedSignatures / pageSize < manifest.scanPageLimit))
    }

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)
}
