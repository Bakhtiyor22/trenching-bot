package com.trenching.bot.console.domain

/**
 * The sweep variation labels of runs, found from the automation's sweep rows; a run no variation produced has no entry. The
 * lookup returns each run's labels ordered by number.
 */
fun interface RunVariationLookup {
    fun labels(runIds: Collection<String>): Map<String, List<String>>

    companion object {
        /** For profiles without the automation console, where no run has a variation. */
        val ABSENT = RunVariationLookup { emptyMap() }
    }
}
