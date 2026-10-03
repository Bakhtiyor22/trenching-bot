package com.trenching.bot.console.api.dto

data class RunDetailResponse(
    val run: RunSummaryResponse,
    val config: ConfigResponse,
    val scenarios: List<ScenarioResponse>,
)

/**
 * One entry offset of one source buy. [status] is `FILLED` or `REJECTED`; a rejected scenario has
 * [rejectionReason] and no [fill], [selectedObservation] or [position].
 */
data class ScenarioResponse(
    val intentId: String,
    val sourceEventId: String,
    val sourceSlot: Long,
    val entryOffset: Int,
    val targetSlot: Long,
    val pair: PairResponse,
    val notional: String,
    val status: String,
    val rejectionReason: String?,
    val fill: FillResponse?,
    val selectedObservation: ObservationResponse?,
    val position: ScenarioPositionResponse?,
    val exitUnavailableReason: String?,
)

data class ScenarioPositionResponse(
    val id: String,
    val state: String,
    val close: CloseResponse?,
)
