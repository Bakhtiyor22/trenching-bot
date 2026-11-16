package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.RunSearchIndex

/**
 * The search index of the datasets that dataset-referenced runs point at, kept for one backfill pass so a dataset many runs share
 * is parsed once, whatever page its runs fall on. Bounded to the [capacity] datasets used last; a dataset that is missing or that
 * cannot be read is kept like any other, so each run that points at it is told the same without parsing it again. One pass uses it
 * on one thread.
 */
class ReferenceIndexCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = object : LinkedHashMap<String, Result>(INITIAL_SIZE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>): Boolean = size > capacity
    }

    /** The index [load] computed for [datasetId], null for a missing dataset; throws what [load] threw, again, for one that failed. */
    fun indexOf(datasetId: String, load: () -> RunSearchIndex?): RunSearchIndex? {
        val cached = entries[datasetId] ?: compute(load).also { entries[datasetId] = it }
        return when (cached) {
            is Result.Indexed -> cached.index
            is Result.Failed -> throw cached.exception
        }
    }

    private fun compute(load: () -> RunSearchIndex?): Result = try {
        Result.Indexed(load())
    } catch (exception: RuntimeException) {
        if (!RunIndexReadFailure.isUnreadable(exception)) throw exception
        Result.Failed(exception)
    }

    private sealed interface Result {
        data class Indexed(val index: RunSearchIndex?) : Result
        data class Failed(val exception: RuntimeException) : Result
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        private const val INITIAL_SIZE = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
