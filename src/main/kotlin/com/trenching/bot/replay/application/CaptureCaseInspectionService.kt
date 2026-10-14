package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketCaseProgress
import com.trenching.bot.marketdata.MarketCaseStage as MarketStage
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.replay.CaptureCaseInspection
import com.trenching.bot.replay.CaptureCaseInventory
import com.trenching.bot.replay.CaptureCaseStage
import java.nio.file.Path

internal class CaptureCaseInspectionService(
    private val planning: RecordedWalletPlanning,
    private val markets: RecordedMarketHistory,
) {
    fun inspectCases(walletDirectory: Path, marketDirectory: Path): CaptureCaseInventory {
        val planned = planning.plan(walletDirectory)
        val cases = progressOrEmpty(marketDirectory)?.cases.orEmpty()
        val plannedIds = planned.plan.windows.mapTo(LinkedHashSet(), MarketCaseId::from)
        if (cases.any { it.caseId !in plannedIds }) throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        return CaptureCaseInventory(planned.plan.windows.map { window ->
            fromProgress(planned, window, cases.singleOrNull { it.caseId == MarketCaseId.from(window) })
        })
    }

    fun inspectCase(walletDirectory: Path, marketDirectory: Path, caseId: MarketCaseId): CaptureCaseInspection {
        val planned = planning.plan(walletDirectory)
        val window = planned.plan.windows.singleOrNull { MarketCaseId.from(it) == caseId }
            ?: throw CaptureValidationException("MARKET_CASE_NOT_FOUND")
        val cases = progressOrEmpty(marketDirectory)?.cases.orEmpty()
        if (cases.any { it.caseId !in planned.plan.windows.mapTo(HashSet(), MarketCaseId::from) }) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        return inspectSelected(planned, marketDirectory, window, cases.singleOrNull { it.caseId == caseId })
    }

    private fun progressOrEmpty(directory: Path) = try {
        markets.inspectCases(directory)
    } catch (exception: CaptureValidationException) {
        if (exception.category == "MANIFEST_MISSING") null else throw exception
    }

    private fun fromProgress(
        planned: com.trenching.bot.replay.domain.PlannedWalletHistory,
        window: MarketCaptureWindow,
        progress: MarketCaseProgress?,
    ): CaptureCaseInspection {
        val anchor = planning.anchor(planned, window)
        val caseId = MarketCaseId.from(window)
        val stage = when {
            progress == null -> CaptureCaseStage.DISCOVERY_PENDING
            progress.stage == MarketStage.DISCOVERY_PENDING -> CaptureCaseStage.DISCOVERY_PENDING
            progress.stage == MarketStage.DISCOVERY_INCOMPLETE -> CaptureCaseStage.DISCOVERY_INCOMPLETE
            progress.stage == MarketStage.DETAILS_PENDING -> CaptureCaseStage.DETAILS_PENDING
            progress.stage == MarketStage.DETAILS_INCOMPLETE -> CaptureCaseStage.DETAILS_INCOMPLETE
            else -> CaptureCaseStage.READY_TO_VALIDATE
        }
        val blocker = progress?.blocker ?: if (progress == null) "MARKET_CASE_NOT_CAPTURED" else "MARKET_CASE_NOT_VALIDATED"
        return CaptureCaseInspection(
            caseId, planned.history.wallet, window.baseMint, anchor.signature, anchor.slot.value, anchor.instructionIndex,
            window, planned.plan.parameters, progress?.coverage ?: MarketWindowCoverage.PENDING,
            progress?.selected ?: 0, progress?.captured ?: 0, progress?.unresolved ?: 0,
            stage, false, blocker, 0,
        )
    }

    private fun inspectSelected(
        planned: com.trenching.bot.replay.domain.PlannedWalletHistory,
        marketDirectory: Path,
        window: MarketCaptureWindow,
        progress: MarketCaseProgress?,
    ): CaptureCaseInspection {
        val summary = fromProgress(planned, window, progress)
        if (progress == null) return summary
        val decoded = try {
            markets.decodeCase(marketDirectory, summary.caseId)
        } catch (exception: CaptureValidationException) {
            return summary.copy(stage = CaptureCaseStage.INVALID, ready = false, blocker = exception.category)
        }
        if (decoded.windowParameters != planned.plan.parameters) {
            return summary.copy(stage = CaptureCaseStage.INVALID, ready = false, blocker = "MARKET_CAPTURE_MISMATCH",
                observations = decoded.observations.size)
        }
        val stage = when {
            decoded.ready -> CaptureCaseStage.READY
            progress.stage == MarketStage.DISCOVERY_PENDING -> CaptureCaseStage.DISCOVERY_PENDING
            progress.stage == MarketStage.DISCOVERY_INCOMPLETE -> CaptureCaseStage.DISCOVERY_INCOMPLETE
            progress.stage == MarketStage.DETAILS_PENDING -> CaptureCaseStage.DETAILS_PENDING
            progress.stage == MarketStage.DETAILS_INCOMPLETE -> CaptureCaseStage.DETAILS_INCOMPLETE
            else -> CaptureCaseStage.INVALID
        }
        return summary.copy(stage = stage, ready = decoded.ready, blocker = decoded.blocker, observations = decoded.observations.size)
    }

}
