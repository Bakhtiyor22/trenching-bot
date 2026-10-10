package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.capture.*
import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.replay.CaptureStageSummary
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface CaptureResponseMapper {
    fun session(value: CaptureSessionView): CaptureSessionResponse
    fun attempt(value: CaptureAttemptRecord): CaptureAttemptResponse
    fun operation(value: CaptureOperationRecord): CaptureOperationResponse
    fun progress(value: CaptureCheckpoint): CaptureProgressResponse
    fun stage(value: CaptureStageSummary): CaptureStageResponse
    fun sessions(value: CapturePage<CaptureSessionView>): CaptureSessionPageResponse
    fun attempts(value: CapturePage<CaptureAttemptRecord>): CaptureAttemptPageResponse
    fun inspection(wallet: CaptureStageResponse, market: CaptureStageResponse, rates: CaptureStageResponse, canCalibrate: Boolean, canExport: Boolean, walletSummary: Map<String, String>, marketSummary: Map<String, String>, ratesSummary: Map<String, String>, marketWindows: List<CaptureWindowResponse>): CaptureInspectionResponse
    fun window(mint: String, fromSlot: Long, toSlot: Long, coverage: String, scanned: Int, selected: Int, detailGaps: Int?, observations: Int?): CaptureWindowResponse
    fun config(rpcConfigured: Boolean, ratesConfigured: Boolean, walletDefaults: CaptureWalletSettings, marketDefaults: CaptureMarketSettings, budgetDefaults: CaptureBudget, bounds: Map<String, Long>, windowSlots: Long): CaptureConfigResponse
}

