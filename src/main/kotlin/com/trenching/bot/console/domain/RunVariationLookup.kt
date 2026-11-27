package com.trenching.bot.console.domain

/**
 * The sweep variations of runs, found from the automation's sweep rows; a run no variation produced has no entry. The lookup
 * returns each run's labels ordered by name.
 */
interface RunVariationLookup {
    fun labels(runIds: Collection<String>): Map<String, List<String>>

    /**
     * The distinct configuration hashes [label]'s sweep runs ran with, at most a bounded number: null when the plan has no variation
     * with that label, archived ones included, and empty when it has one that has not run.
     */
    fun configurationHashes(label: String): Set<String>?

    companion object {
        /** For profiles without the automation console, where no run has a variation. */
        val ABSENT: RunVariationLookup = object : RunVariationLookup {
            override fun labels(runIds: Collection<String>): Map<String, List<String>> = emptyMap()

            override fun configurationHashes(label: String): Set<String>? = null
        }
    }
}
