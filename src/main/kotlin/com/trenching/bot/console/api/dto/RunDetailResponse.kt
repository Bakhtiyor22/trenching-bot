package com.trenching.bot.console.api.dto

data class RunDetailResponse(
    val run: RunSummaryResponse,
    val config: ConfigResponse,
    val scenarios: List<ScenarioResponse>,
)

/**
 * One dip-entry cycle: the tracked wallet's first buy of a mint at [sourceSlot] with price [referencePrice] (P0). [watch]
 * says whether a trade reached [dipPrice] in time; [trigger] is that trade. [status] is `FILLED` or `REJECTED`; a rejected
 * scenario has [rejectionReason] and no [fill], [selectedObservation] or [position].
 */
data class ScenarioResponse(
    val intentId: String,
    val sourceEventId: String,
    val sourceSlot: Long,
    val entryOffset: Int,
    val targetSlot: Long,
    val pair: PairResponse,
    val notional: String,
    val referencePrice: String,
    val dipPrice: String,
    val horizonEndSlot: Long,
    val watch: String,
    val trigger: DipTriggerResponse?,
    val status: String,
    val rejectionReason: String?,
    val fill: FillResponse?,
    val selectedObservation: ObservationResponse?,
    val position: ScenarioPositionResponse?,
    val exitUnavailableReason: String?,
)

data class DipTriggerResponse(
    val observationId: String,
    val slot: Long,
    val price: String,
    val marketCapUsd: String,
    val rate: String,
)

data class ScenarioPositionResponse(
    val id: String,
    val state: String,
    val close: CloseResponse?,
    val takeProfitPrice: String,
    val stopLossPrice: String,
    val exitTrigger: ExitTriggerResponse?,
)

/** The trade that crossed take profit or stop loss; the exit fills at a later slot's trade, if any. */
data class ExitTriggerResponse(
    val observationId: String,
    val slot: Long,
    val reason: String,
)
