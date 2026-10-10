package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureArtifactStore
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ManifestFields
import com.trenching.bot.chaincapture.ManifestFields.boolean
import com.trenching.bot.chaincapture.ManifestFields.optionalString
import com.trenching.bot.chaincapture.ManifestFields.string
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.marketdata.ReferenceRateManifest
import com.trenching.bot.marketdata.ReferenceRateRecord
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The hash-enveloped rate manifest of a rate capture directory, verified on every load. */
internal class ReferenceRateArtifactStore(private val mapper: ObjectMapper) {
    private val files = CaptureArtifactStore(mapper)

    fun inspect(directory: Path): ReferenceRateCaptureResult =
        ReferenceRateCaptureResult(directory.toAbsolutePath().normalize(),
            loadIfPresent(directory) ?: throw CaptureValidationException("MANIFEST_MISSING"))

    fun loadIfPresent(directory: Path): ReferenceRateManifest? {
        val inner = files.readManifest(files.root(directory, create = false), MANIFEST) ?: return null
        ManifestFields.requireVersion(inner, VERSION)
        val manifest = parse(inner)
        validate(manifest)
        return manifest
    }

    fun save(directory: Path, manifest: ReferenceRateManifest) {
        files.writeManifest(files.root(directory, create = true), MANIFEST, mapper.valueToTree(manifest))
    }

    fun lock(directory: Path): AutoCloseable = files.lock(directory)

    /** The rates as values, refusing a manifest with any minute still unresolved. */
    fun rates(manifest: ReferenceRateManifest): List<ReferenceRate> {
        if (!manifest.complete) throw CaptureValidationException("REFERENCE_RATES_INCOMPLETE")
        return manifest.records.map { record ->
            ReferenceRate(Instant.parse(record.minute), BigDecimal(requireNotNull(record.rate)), requireNotNull(record.source))
        }
    }

    private fun parse(node: JsonNode): ReferenceRateManifest = try {
        val records = ManifestFields.array(node, "records", MAX_RECORDS).map { value ->
            ReferenceRateRecord(string(value, "minute"), optionalString(value, "rate"), optionalString(value, "source"),
                optionalString(value, "unavailableReason"))
        }
        ReferenceRateManifest(ManifestFields.smallInteger(node, "schemaVersion"), string(node, "capturedAt"), records, boolean(node, "complete"))
    } catch (_: IllegalArgumentException) { fail("MANIFEST_FIELDS") }

    private fun validate(manifest: ReferenceRateManifest) {
        try {
            Instant.parse(manifest.capturedAt)
            val minutes = manifest.records.map { Instant.parse(it.minute) }
            if (minutes.zipWithNext().any { (earlier, later) -> earlier >= later }) fail("RECORD_ORDER")
            manifest.records.zip(minutes).forEach { (record, minute) ->
                val resolved = record.rate != null
                if (resolved != (record.source != null) || resolved == (record.unavailableReason != null)) fail("RECORD_STATE")
                if (resolved) ReferenceRate(minute, BigDecimal(record.rate), requireNotNull(record.source))
                else if (minute != ReferenceRate.minuteOf(minute)) fail("RECORD_MINUTE")
            }
        } catch (_: DateTimeParseException) { fail("MANIFEST_TIME") }
        catch (_: IllegalArgumentException) { fail("RECORD_VALUE") }
        if (manifest.complete != manifest.records.all { it.rate != null }) fail("COMPLETE_STATUS")
    }

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)

    companion object {
        const val VERSION = 1
        const val MANIFEST = "rates-manifest.json"

        /** Every minute of up to 100 windows of 30 minutes, with room to spare. */
        const val MAX_RECORDS = 100_000
    }
}
