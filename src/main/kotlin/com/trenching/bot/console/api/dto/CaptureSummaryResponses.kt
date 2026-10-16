package com.trenching.bot.console.api.dto

import java.time.Instant

/** One capture's overall state; every code is an enum name the console's copy table explains. */
data class CaptureSummaryResponse(
    val status: String,
    val reasonCode: String?,
    val currentStep: CaptureCurrentStepResponse,
    val steps: List<CaptureStepResponse>,
    val progress: CaptureSummaryProgressResponse?,
    val nextAction: CaptureNextActionResponse,
    val cases: CaptureCaseCountsResponse,
    val lastProgressAt: Instant?,
)

data class CaptureCurrentStepResponse(val index: Int, val stage: String)

data class CaptureStepResponse(val stage: String, val state: String)

data class CaptureSummaryProgressResponse(val processed: Int, val selected: Int, val percent: String)

data class CaptureNextActionResponse(val kind: String, val stage: String?, val attemptId: String?, val caseId: String?, val maxRequests: Int?)

data class CaptureCaseCountsResponse(val total: Int, val ready: Int, val blocked: Int, val downloading: Int)
