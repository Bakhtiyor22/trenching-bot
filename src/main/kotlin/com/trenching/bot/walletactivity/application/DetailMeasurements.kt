package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureMeasurements
import java.time.Duration

/** The measurements of every batch of one detail phase, summed so the phase is logged once. */
internal class DetailMeasurements {
    var batches = 0
        private set
    var total = CaptureMeasurements(requestsSent = 0, cacheHits = 0, rateLimitedResponses = 0, retries = 0, elapsed = Duration.ZERO)
        private set

    fun add(batch: CaptureMeasurements) {
        batches++
        total = CaptureMeasurements(
            requestsSent = total.requestsSent + batch.requestsSent, cacheHits = total.cacheHits + batch.cacheHits,
            rateLimitedResponses = total.rateLimitedResponses + batch.rateLimitedResponses, retries = total.retries + batch.retries,
            elapsed = total.elapsed.plus(batch.elapsed),
        )
    }
}
