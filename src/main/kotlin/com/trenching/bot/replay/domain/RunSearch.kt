package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import java.time.Instant

/**
 * Filters of [com.trenching.bot.replay.SimulationRuns.search], combined with AND. [statuses] empty means any status. Ranges are
 * half-open `[from, to)`; a run without an anchor never matches an anchor bound. Only runs created at or before [asOf] are
 * visible, so every page of one [asOf] is cut from the same list. [text] matches a run id, dataset id or configuration hash by
 * prefix (at least [MIN_PREFIX_LENGTH] characters) or a dataset name as a case-insensitive substring.
 */
data class RunSearchQuery(
    val statuses: Set<RunStatus>,
    val mint: MintAddress?,
    val anchorFrom: Instant?,
    val anchorTo: Instant?,
    val createdFrom: Instant?,
    val createdTo: Instant?,
    val datasetId: String?,
    val text: String?,
    val asOf: Instant,
    val page: Int,
    val size: Int,
) {
    companion object {
        const val MIN_PREFIX_LENGTH = 4
    }
}

/** [statusCounts] has every [RunStatus] and applies every filter of the query except its statuses. */
data class RunSearchResult(val page: PageResult<RunSummary>, val statusCounts: Map<RunStatus, Long>)
