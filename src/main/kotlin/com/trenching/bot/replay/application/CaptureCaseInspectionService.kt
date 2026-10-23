package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketCaseProgress
import com.trenching.bot.marketdata.MarketCaseStage as MarketStage
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.replay.CaptureCaseInspection
import com.trenching.bot.replay.CaptureCaseInventory
import com.trenching.bot.replay.CaptureCaseStage
import com.trenching.bot.replay.domain.PlannedWalletHistory
import java.nio.file.Path

/**
 * Lists and inspects the market cases of a wallet capture from the market capture's manifest alone, planned with the lead
 * that manifest records (the default lead when nothing was captured yet), so a capture from before the lead keeps its
 * `S + 1` cases. Only [inspectCase] verifies and decodes the one case's artifacts.
 */
internal class CaptureCaseInspectionService(
    private val planning: RecordedWalletPlanning,
    private val markets: RecordedMarketHistory,
) {
    fun inspectCases(walletDirectory: Path, marketDirectory: Path): CaptureCaseInventory {
        val history = planning.history(walletDirectory)
        val saved = markets.windowParameters(marketDirectory)
        val planned = planning.planForMarketCapture(history, saved)
        val cases = savedCases(planned, marketDirectory, saved)
        return CaptureCaseInventory(planned.plan.windows.map { window ->
            fromProgress(planned, saved, window, cases.singleOrNull { it.caseId == MarketCaseId.from(window) })
        })
    }

    fun inspectCase(walletDirectory: Path, marketDirectory: Path, caseId: MarketCaseId): CaptureCaseInspection {
        val history = planning.history(walletDirectory)
        val saved = markets.windowParameters(marketDirectory)
        val planned = planning.planForMarketCapture(history, saved)
        val window = planned.plan.windows.singleOrNull { MarketCaseId.from(it) == caseId }
            ?: throw CaptureValidationException("MARKET_CASE_NOT_FOUND")
        val cases = savedCases(planned, marketDirectory, saved)
        return inspectSelected(planned, saved, marketDirectory, window, cases.singleOrNull { it.caseId == caseId })
    }

    /** The saved capture's case progress, none without a manifest; every saved case must be one of the planned windows. */
    private fun savedCases(planned: PlannedWalletHistory, directory: Path, saved: MarketCaptureWindowParameters?): List<MarketCaseProgress> {
        if (saved == null) return emptyList()
        val cases = markets.inspectCases(directory).cases
        val plannedIds = planned.plan.windows.mapTo(HashSet(), MarketCaseId::from)
        if (cases.any { it.caseId !in plannedIds }) throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        return cases
    }

    /** A case carries the parameters its capture's manifest records, or those a new capture would record. */
    private fun fromProgress(
        planned: PlannedWalletHistory,
        saved: MarketCaptureWindowParameters?,
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
            window, saved ?: planned.plan.parameters, progress?.coverage ?: MarketWindowCoverage.PENDING,
            progress?.selected ?: 0, progress?.captured ?: 0, progress?.unresolved ?: 0,
            stage, false, blocker, 0, progress?.observedThrough?.value,
        )
    }

    private fun inspectSelected(
        planned: PlannedWalletHistory,
        saved: MarketCaptureWindowParameters?,
        marketDirectory: Path,
        window: MarketCaptureWindow,
        progress: MarketCaseProgress?,
    ): CaptureCaseInspection {
        val summary = fromProgress(planned, saved, window, progress)
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
