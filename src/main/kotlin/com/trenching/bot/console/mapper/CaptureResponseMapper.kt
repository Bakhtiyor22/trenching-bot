package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.capture.*
import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.replay.CaptureStageSummary
import java.time.Instant
import org.mapstruct.BeanMapping
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.NullValueMappingStrategy
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface CaptureResponseMapper {
    fun session(value: CaptureSessionView): CaptureSessionResponse
    fun attempt(value: CaptureAttemptRecord): CaptureAttemptResponse
    fun operation(value: CaptureOperationRecord): CaptureOperationResponse
    fun captureCase(value: CaptureCaseView): CaptureCaseResponse
    fun cases(value: CaptureCasesView): CaptureCasesResponse
    fun caseProgress(value: CaptureProgressResponse): CaptureCaseProgressResponse
    fun recovery(value: CaptureRecoveryRecord): CaptureRecoveryResponse = CaptureRecoveryResponse(
        enabled = true,
        totalRequestBudget = value.totalRequestBudget,
        totalByteBudget = value.totalByteBudget,
        maxAttempts = value.maxAttempts,
        attemptsUsed = value.attemptsUsed,
        requestsUsed = value.requestsUsed,
        bytesUsed = value.bytesUsed,
        reservedRequests = value.reservedRequests,
        reservedBytes = value.reservedBytes,
        nextRetryAt = value.nextRetryAt,
    )
    fun progress(value: CaptureCheckpoint): CaptureProgressResponse
    fun stage(value: CaptureStageSummary): CaptureStageResponse
    fun sessions(value: CapturePage<CaptureSessionView>): CaptureSessionPageResponse
    fun attempts(value: CapturePage<CaptureAttemptRecord>): CaptureAttemptPageResponse
    fun inspection(wallet: CaptureStageResponse, market: CaptureStageResponse, rates: CaptureStageResponse, canCalibrate: Boolean, canExport: Boolean, walletSummary: Map<String, String>, marketSummary: Map<String, String>, ratesSummary: Map<String, String>, marketWindows: List<CaptureWindowResponse>, targetToken: CaptureTargetTokenResponse?): CaptureInspectionResponse
    fun window(mint: String, fromSlot: Long, toSlot: Long, coverage: String, scanned: Int, selected: Int, detailGaps: Int?, observations: Int?): CaptureWindowResponse
    fun config(rpcConfigured: Boolean, ratesConfigured: Boolean, walletDefaults: CaptureWalletSettings, walletTokenDefaults: CaptureWalletSettings, walletTokenBudgetDefaults: CaptureBudget, marketDefaults: CaptureMarketSettings, budgetDefaults: CaptureBudget, bounds: Map<String, Long>, windowSlots: Long): CaptureConfigResponse
    fun targetToken(mint: String, tokenProgram: String, tokenAccount: String, pumpAccumulator: String, pumpSwapAccumulator: String, tokenAccountFirstBuy: CaptureTokenAccountFirstBuyResponse, actualFirstBuy: CaptureActualFirstBuyResponse): CaptureTargetTokenResponse
    fun tokenAccountFirstBuy(state: String, signature: String?, slot: Long?, reason: String?, blocking: List<String>): CaptureTokenAccountFirstBuyResponse
    fun actualFirstBuy(state: String, signature: String?, slot: Long?, creationSignature: String?, creationSlot: Long?, creationTime: Instant?, reason: String?, blocking: List<String>, claim: String?, bound: CaptureVerificationBoundResponse?): CaptureActualFirstBuyResponse
    fun verificationBound(limit: String, pages: Map<String, Int>, entries: Map<String, Int>, fetches: Int, oldestSlot: Map<String, Long?>): CaptureVerificationBoundResponse
    fun qualification(classification: String, capture: CaptureVerdictCaptureResponse, dip: CaptureDipVerdictResponse, entry: CaptureEntryVerdictResponse?, exit: CaptureExitVerdictResponse?, replay: CaptureReplayVerdictResponse, provenance: CaptureVerdictProvenanceResponse): CaptureQualificationResponse
    fun verdictCapture(state: String, stage: String?, reason: String?): CaptureVerdictCaptureResponse
    fun verdictDip(state: String, reason: String?, triggerSlot: Long?, triggerPrice: String?, marketCapUsd: String?, rate: String?, observationId: String?): CaptureDipVerdictResponse
    fun verdictEntry(state: String, reason: String?, slot: Long?, executionPrice: String?, observationId: String?): CaptureEntryVerdictResponse
    fun verdictExit(state: String, reason: String, slot: Long?, realizedPnl: String?): CaptureExitVerdictResponse
    fun verdictReplay(state: String, category: String?): CaptureReplayVerdictResponse
    /** An unrun verdict has no provenance values, yet still answers with the object. */
    @BeanMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
    fun verdictProvenance(datasetChecksum: String?, runId: String?, configurationHash: String?, engineVersion: String?, referencePrice: String?, firstBuySignature: String?, firstBuySlot: Long?): CaptureVerdictProvenanceResponse
}

