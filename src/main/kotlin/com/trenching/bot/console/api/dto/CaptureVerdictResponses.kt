package com.trenching.bot.console.api.dto

import java.time.Instant

/** A wallet + token capture's first-buy evidence; the two first-buy results are separate and never stand in for each other. */
data class CaptureTargetTokenResponse(val mint: String, val tokenProgram: String, val tokenAccount: String, val pumpAccumulator: String, val pumpSwapAccumulator: String, val tokenAccountFirstBuy: CaptureTokenAccountFirstBuyResponse, val actualFirstBuy: CaptureActualFirstBuyResponse)
data class CaptureTokenAccountFirstBuyResponse(val state: String, val signature: String?, val slot: Long?, val reason: String?, val blocking: List<String>)
/** [claim] is the exact coverage claim, set only for a proven first buy; [bound] is set only when a verification cap was reached. */
data class CaptureActualFirstBuyResponse(val state: String, val signature: String?, val slot: Long?, val creationSignature: String?, val creationSlot: Long?, val creationTime: Instant?, val reason: String?, val blocking: List<String>, val claim: String?, val bound: CaptureVerificationBoundResponse?)
data class CaptureVerificationBoundResponse(val limit: String, val pages: Map<String, Int>, val entries: Map<String, Int>, val fetches: Int, val oldestSlot: Map<String, Long?>)

/** A wallet + token capture's verdict; decimals are exact plain strings. */
data class CaptureQualificationResponse(val classification: String, val capture: CaptureVerdictCaptureResponse, val dip: CaptureDipVerdictResponse, val entry: CaptureEntryVerdictResponse?, val exit: CaptureExitVerdictResponse?, val replay: CaptureReplayVerdictResponse, val provenance: CaptureVerdictProvenanceResponse)
data class CaptureVerdictCaptureResponse(val state: String, val stage: String?, val reason: String?)
data class CaptureDipVerdictResponse(val state: String, val reason: String?, val triggerSlot: Long?, val triggerPrice: String?, val marketCapUsd: String?, val rate: String?, val observationId: String?)
data class CaptureEntryVerdictResponse(val state: String, val reason: String?, val slot: Long?, val executionPrice: String?, val observationId: String?)
data class CaptureExitVerdictResponse(val state: String, val reason: String, val slot: Long?, val realizedPnl: String?)
data class CaptureReplayVerdictResponse(val state: String, val category: String?)
data class CaptureVerdictProvenanceResponse(val datasetChecksum: String?, val runId: String?, val configurationHash: String?, val engineVersion: String?, val referencePrice: String?, val firstBuySignature: String?, val firstBuySlot: Long?)
