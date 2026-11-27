package com.trenching.bot.console.domain

import com.trenching.bot.replay.domain.RunSearchResult
import java.time.Instant

/** The `GET /api/runs` parameters as received, before they are validated. */
data class RunSearchParams(
    val page: Int,
    val size: Int,
    val datasetId: String?,
    val statuses: List<String>,
    val mint: String?,
    val anchorFrom: Instant?,
    val anchorTo: Instant?,
    val createdFrom: Instant?,
    val createdTo: Instant?,
    val text: String?,
    val asOf: Instant?,
    val variation: String? = null,
)

/**
 * [asOf] is the snapshot the result was cut from: the one asked for, or else the time of the request. [variations] and [outcomes]
 * have an entry, possibly empty, for each run on the page.
 */
data class RunSearchPage(
    val result: RunSearchResult,
    val asOf: Instant,
    val variations: Map<String, List<String>>,
    val outcomes: Map<String, List<RunOutcome>>,
)
