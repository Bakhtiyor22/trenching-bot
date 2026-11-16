package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.replay.domain.RunStorage

/** What the search-index backfill needs to find a run without an index; a copy run's snapshot is read separately, one at a time. */
interface SimulationRunIndexSource {
    val id: String
    val datasetChecksum: String
    val storage: RunStorage?
}
