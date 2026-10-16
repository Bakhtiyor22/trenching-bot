package com.trenching.bot.console.domain

import com.trenching.bot.replay.domain.DatasetSummary

/**
 * Where a dataset came from. [datasetName] is null once the dataset left the catalog; the capture fields are null when no
 * capture on this machine registered it, or the capture console is off. [caseId] names the capture case it was exported from.
 */
data class DatasetSource(
    val datasetId: String,
    val datasetName: String?,
    val captureId: String?,
    val captureDisplayName: String?,
    val caseId: String?,
)

/** The capture, and the case within it when there is one, that registered a dataset. */
data class CaptureSource(val captureId: String, val displayName: String, val caseId: String?)

/** Finds the capture that registered a dataset; implemented by the local capture console. */
fun interface CaptureSources {
    fun forDataset(datasetId: String): CaptureSource?
}

/** A dataset with its [source], for the dataset page. */
data class DatasetDetail(val dataset: DatasetSummary, val source: DatasetSource)
