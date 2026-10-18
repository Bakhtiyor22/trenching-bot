package com.trenching.bot.console.domain

import com.trenching.bot.replay.domain.DatasetSummary

/**
 * Where a dataset came from. [datasetName] is null once the dataset left the catalog; [capture] is null when no capture on disk
 * exported it: an uploaded dataset, a deleted capture, or no capture console in this profile.
 */
data class DatasetSource(val datasetId: String, val datasetName: String?, val capture: CaptureSource?)

/** The capture session that exported a dataset, by its display name; [caseId] is set when the export was one of its cases. */
data class CaptureSource(val captureId: String, val displayName: String, val caseId: String?)

data class DatasetDetail(val dataset: DatasetSummary, val source: DatasetSource)
