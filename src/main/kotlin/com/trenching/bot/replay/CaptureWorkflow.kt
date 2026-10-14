package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.replay.domain.TargetTokenVerdict
import java.nio.file.Path

interface CaptureWorkflow {
    fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE): CaptureStepResult
    fun inspect(paths: CapturePaths): CaptureInspection

    fun inspectCases(walletDirectory: Path, marketDirectory: Path): CaptureCaseInventory =
        throw UnsupportedOperationException("Case inspection is not available in this workflow")

    fun inspectCase(walletDirectory: Path, marketDirectory: Path, caseId: MarketCaseId): CaptureCaseInspection =
        throw UnsupportedOperationException("Case inspection is not available in this workflow")

    /**
     * The wallet + token verdict an [inspection] gives without a replay. [datasetExported] is whether a dataset of the current
     * capture was exported, so only its qualification is pending.
     */
    fun verdict(inspection: CaptureInspection, datasetExported: Boolean): TargetTokenVerdict
}
