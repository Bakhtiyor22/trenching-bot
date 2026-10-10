package com.trenching.bot.marketdata

import java.nio.file.Path

/**
 * User-run capture of the SOL/USDT minute rates a market capture's trades need, so an export can carry them in the
 * replay dataset. Only the user runs it; the build and tests never call the rate source.
 */
interface ReferenceRateCapture {
    fun capture(request: ReferenceRateCaptureRequest): ReferenceRateCaptureResult
}

/** Verified, offline access to a rate capture. */
interface RecordedReferenceRates {
    fun inspect(directory: Path): ReferenceRateCaptureResult

    /** Every recorded rate, refusing `REFERENCE_RATES_INCOMPLETE` while any minute still has none. */
    fun rates(directory: Path): List<ReferenceRate>
}

data class ReferenceRateCaptureRequest(
    val marketCaptureDirectory: Path,
    val directory: Path,
)

data class ReferenceRateCaptureResult(
    val directory: Path,
    val manifest: ReferenceRateManifest,
)

/** [records] holds one entry per minute of an in-window trade, in minute order; [complete] when each has a rate. */
data class ReferenceRateManifest(
    val schemaVersion: Int,
    val capturedAt: String,
    val records: List<ReferenceRateRecord>,
    val complete: Boolean,
)

/** A minute's rate and its source, or the reason the source gave none (retried on the next run). */
data class ReferenceRateRecord(
    val minute: String,
    val rate: String?,
    val source: String?,
    val unavailableReason: String?,
)
