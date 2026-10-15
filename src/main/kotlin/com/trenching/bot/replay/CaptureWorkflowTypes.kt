package com.trenching.bot.replay

import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.TransactionSignature
import java.nio.file.Path

data class CapturePaths(val wallet: Path, val market: Path? = null, val rates: Path? = null)

sealed interface CaptureStepCommand {
    data class Wallet(val request: WalletHistoryCaptureRequest) : CaptureStepCommand
    data class Markets(val request: ReplayMarketCaptureRequest) : CaptureStepCommand
    data class Rates(val wallet: Path, val market: Path, val directory: Path) : CaptureStepCommand
    data class CaseRates(val wallet: Path, val market: Path, val caseId: MarketCaseId, val directory: Path) : CaptureStepCommand
    data class Inspect(val paths: CapturePaths) : CaptureStepCommand
    data class Export(val request: RecordedReplayExportRequest) : CaptureStepCommand
    data class ExportCase(val request: CaptureCaseExportRequest) : CaptureStepCommand
    data class Calibrate(val wallet: Path, val market: Path, val report: Path) : CaptureStepCommand

    /** Replays the exported [dataset] offline for a verdict, or answers from inspection when [paths] are not export ready. */
    data class Qualify(val dataset: Path, val paths: CapturePaths) : CaptureStepCommand
}

sealed interface CaptureStepResult {
    data class Wallet(val capture: WalletHistoryCaptureResult) : CaptureStepResult
    data class Markets(val capture: MarketHistoryCaptureResult) : CaptureStepResult
    data class Rates(val capture: ReferenceRateCaptureResult) : CaptureStepResult
    data class CaseRates(val capture: ReferenceRateCaptureResult) : CaptureStepResult
    data class Inspected(val inspection: CaptureInspection) : CaptureStepResult
    data class Exported(val export: RecordedReplayExport) : CaptureStepResult
    data class CaseExported(val export: CaptureCaseExport) : CaptureStepResult
    data class Calibrated(val report: CalibrationReport, val json: String, val output: Path) : CaptureStepResult
    data class Qualified(val verdict: TargetTokenVerdict) : CaptureStepResult
}

enum class CaptureCaseStage { DISCOVERY_PENDING, DISCOVERY_INCOMPLETE, DETAILS_PENDING, DETAILS_INCOMPLETE, READY_TO_VALIDATE, READY, INVALID }

data class CaptureCaseInventory(val cases: List<CaptureCaseInspection>)

data class CaptureCaseInspection(
    val caseId: MarketCaseId,
    val wallet: WalletAddress,
    val mint: MintAddress,
    val anchorSignature: TransactionSignature,
    val anchorSlot: Long,
    val instructionIndex: Int,
    val window: MarketCaptureWindow,
    val windowParameters: MarketCaptureWindowParameters,
    val coverage: MarketWindowCoverage,
    val selected: Int,
    val captured: Int,
    val gaps: Int,
    val stage: CaptureCaseStage,
    val ready: Boolean,
    val blocker: String?,
    val observations: Int,
    /** The window's last observed slot, earlier than its end when its outcome was decided; null before market capture. */
    val observedThrough: Long? = null,
)

enum class CaptureStageState { NOT_STARTED, INCOMPLETE, READY, UNUSABLE, INVALID }

data class CaptureStageSummary(
    val state: CaptureStageState,
    val reason: String? = null,
    val canStart: Boolean = false,
    val canResume: Boolean = false,
    val requiresNewAttempt: Boolean = false,
)

data class CaptureInspection(
    val wallet: CaptureStageSummary,
    val market: CaptureStageSummary,
    val rates: CaptureStageSummary,
    val canCalibrate: Boolean,
    val canExport: Boolean,
    val walletCapture: WalletHistoryCaptureResult? = null,
    val walletDecoded: WalletHistoryDecodeResult? = null,
    val marketCapture: MarketHistoryCaptureResult? = null,
    val marketDecoded: MarketHistoryDecodeResult? = null,
    val ratesCapture: ReferenceRateCaptureResult? = null,
    /** The decoded wallet + token capture's first-buy evidence; null for the other scopes or before the wallet capture decodes. */
    val targetToken: TargetTokenEvidence? = null,
)
