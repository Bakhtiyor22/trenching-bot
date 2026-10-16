package com.trenching.bot.apiusage.domain

import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.apiusage.plus
import java.time.Instant

/** Counts added to one bucket since its last flush; [lastCallAt] is the latest request sent, null when only cache hits. */
data class ApiUsageDelta(val totals: ApiUsageTotals, val lastCallAt: Instant?) {
    val requests: Long get() = totals.requests

    operator fun plus(other: ApiUsageDelta) = ApiUsageDelta(totals + other.totals, latest(lastCallAt, other.lastCallAt))

    companion object {
        fun of(outcome: ApiCallOutcome, responseBytes: Long, at: Instant): ApiUsageDelta = when (outcome) {
            ApiCallOutcome.OK -> ApiUsageDelta(ApiUsageTotals(1, 0, 0, responseBytes, 0), at)
            ApiCallOutcome.FAILED -> ApiUsageDelta(ApiUsageTotals(1, 1, 0, responseBytes, 0), at)
            ApiCallOutcome.RATE_LIMITED -> ApiUsageDelta(ApiUsageTotals(1, 0, 1, responseBytes, 0), at)
            ApiCallOutcome.CACHE_HIT -> ApiUsageDelta(ApiUsageTotals(0, 0, 0, responseBytes, 1), null)
        }

        fun latest(first: Instant?, second: Instant?): Instant? = listOfNotNull(first, second).maxOrNull()
    }
}
