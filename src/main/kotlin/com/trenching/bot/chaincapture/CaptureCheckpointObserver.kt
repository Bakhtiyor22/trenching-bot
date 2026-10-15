package com.trenching.bot.chaincapture

import java.time.Instant

fun interface CaptureCheckpointObserver {
    fun checkpoint(checkpoint: CaptureCheckpoint)

    /**
     * Whether the operation was asked to stop. A capture consults it between detail requests, so no new request starts once it
     * is true; the next checkpoint then raises the stop as before. It runs on the capturing thread and must be cheap.
     */
    fun stopRequested(): Boolean = false

    companion object {
        val NONE = CaptureCheckpointObserver { }
    }
}

data class CaptureCheckpoint(
    val phase: String,
    val at: Instant,
    val operationRequests: Int? = null,
    val operationBytes: Long? = null,
    val cumulativeRequests: Int? = null,
    val cumulativeBytes: Long? = null,
    val processed: Int? = null,
    val selected: Int? = null,
)
