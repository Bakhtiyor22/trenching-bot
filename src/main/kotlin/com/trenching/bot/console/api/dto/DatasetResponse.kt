package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/** [source] is set on the dataset detail only and left out of lists and registrations. */
data class DatasetResponse(
    val id: String,
    val name: String,
    val trackedWallet: String,
    val schemaVersion: Int,
    val transactionCount: Int,
    val observationCount: Int,
    val firstSlot: Long?,
    val lastSlot: Long?,
    val pairs: List<PairResponse>,
    val createdAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val source: DatasetSourceResponse? = null,
)

/** The capture fields are null when no capture on disk exported the dataset. */
data class DatasetSourceResponse(
    val datasetId: String,
    val datasetName: String?,
    val captureId: String?,
    val captureDisplayName: String?,
    val caseId: String?,
)

data class DatasetRegistrationResponse(
    val dataset: DatasetResponse,
    val created: Boolean,
)

data class DatasetPageResponse(
    val items: List<DatasetResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)
