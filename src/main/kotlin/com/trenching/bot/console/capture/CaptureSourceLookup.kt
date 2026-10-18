package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource

/** The capture that exported a dataset, found from the captures' forward `datasetId` links; null when none did. */
fun interface CaptureSourceLookup {
    fun find(datasetId: String): CaptureSource?

    companion object {
        /** For profiles without the capture console, where no capture can be found. */
        val ABSENT = CaptureSourceLookup { null }
    }
}
