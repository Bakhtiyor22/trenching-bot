package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureCheckpointObserver

interface CaptureWorkflow {
    fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE): CaptureStepResult
    fun inspect(paths: CapturePaths): CaptureInspection
}
