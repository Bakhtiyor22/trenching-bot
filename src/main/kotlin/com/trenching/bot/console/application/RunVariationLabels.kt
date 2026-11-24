package com.trenching.bot.console.application

import com.trenching.bot.console.domain.RunVariationLookup

/** The variation labels of the runs a response shows, from one lookup per call. */
class RunVariationLabels(private val lookup: RunVariationLookup) {
    /** An entry for every distinct id in [runIds], in their order; no lookup when there are none. */
    fun of(runIds: Collection<String>): Map<String, List<String>> {
        val distinct = runIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val found = lookup.labels(distinct)
        return distinct.associateWith { found[it].orEmpty() }
    }

    fun of(runId: String): List<String> = of(listOf(runId)).getValue(runId)
}
