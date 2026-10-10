package com.trenching.bot.chaincapture

import java.time.Instant

fun interface CaptureCheckpointObserver {
    fun checkpoint(checkpoint: CaptureCheckpoint)

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
