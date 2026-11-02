package com.trenching.bot.console.api.dto

import java.time.Instant

data class RunDetailResponse(
    val run: RunSummaryResponse,
    val config: ConfigResponse,
    val scenarios: List<ScenarioResponse>,
    val source: DatasetSourceResponse,
    /** Milliseconds per slot while the run's market was recorded, to convert its horizon to time; null when too short to measure. */
    val millisPerSlot: Double?,
)

/**
 * One dip-entry cycle: the tracked wallet's first buy of a mint at [sourceSlot] with price [referencePrice] (P0). [watch]
 * says whether a trade reached [dipPrice] in time; [trigger] is that trade. [status] is `FILLED` or `REJECTED`; a rejected
 * scenario has [rejectionReason] and no [fill], [selectedObservation] or [position]. Under the `POST_TRADE` price measure
 * [referenceObservationId] is the market observation of the wallet's buy that [referencePrice] was read from; it is null under
 * `TRADE_AVERAGE` and when [watch] is `REFERENCE_UNAVAILABLE`, whose [referencePrice] and [dipPrice] only hold the buy's
 * average as a placeholder. [walletSell] is set exactly when [watch] is `WALLET_SOLD`.
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
    val timeline: ScenarioTimelineResponse,
    val watch: String,
    val trigger: DipTriggerResponse?,
    val referenceObservationId: String?,
    val walletSell: WalletSellResponse?,
    val status: String,
    val rejectionReason: String?,
    val fill: FillResponse?,
    val selectedObservation: ObservationResponse?,
    val position: ScenarioPositionResponse?,
    val exitUnavailableReason: String?,
)

/** Observation block times of the scenario's stages, each null when its stage did not happen. */
data class ScenarioTimelineResponse(
    val firstBuyAt: Instant?,
    val dipAt: Instant?,
    val entryAt: Instant?,
    val exitTriggerAt: Instant?,
    val exitAt: Instant?,
)

data class DipTriggerResponse(
    val observationId: String,
    val slot: Long,
    val price: String,
    val marketCapUsd: String,
    val rate: String,
)

/** The tracked wallet's sell that ended the dip watch: [eventId] is that sell's market observation id. */
data class WalletSellResponse(
    val eventId: String,
    val slot: Long,
)

/** [exitStart] is when a timed run's time exit starts; null for an untimed run or a buy without a block time. */
data class ScenarioPositionResponse(
    val id: String,
    val state: String,
    val close: CloseResponse?,
    val takeProfitPrice: String,
    val stopLossPrice: String,
    val exitTrigger: ExitTriggerResponse?,
    val exitStart: Instant?,
)

/**
 * What started the exit. A take profit or stop loss is the trade that crossed its level, at its [slot] and block [time]. A
 * `TIME_EXIT` starts at [time], the position's exit start; its [observationId] is the trade whose curve stood then and [slot]
 * the first slot the recording proves at or after it. A `WALLET_SOLD` exit's [observationId] is the tracked wallet's sell; when
 * that sell landed before our fill, [slot] and [time] are the fill's, so [slot] need not be the sell's slot. [time] is null on a
 * trigger stored before engine dip-paper-v4.
 */
data class ExitTriggerResponse(
    val observationId: String,
    val slot: Long,
    val time: Instant?,
    val reason: String,
)
