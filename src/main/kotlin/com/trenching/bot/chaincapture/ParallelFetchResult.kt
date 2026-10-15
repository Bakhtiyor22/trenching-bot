package com.trenching.bot.chaincapture

/** One item's result with the position it had in the input list. */
data class FetchedItem<I, R>(val index: Int, val item: I, val result: R)

/**
 * What a [BoundedParallelFetch] run achieved. [completed] is in input order and may skip items that were never started or
 * whose fetch failed, so a caller can checkpoint it and retry the rest. [failure] is the fatal error of the lowest-numbered
 * item that failed; [stopped] means the stop request ended the run before every item was started.
 */
data class ParallelFetchResult<I, R>(
    val completed: List<FetchedItem<I, R>>,
    val failure: Exception?,
    val stopped: Boolean,
    val measurements: CaptureMeasurements,
    private val itemCount: Int,
) {
    val finished: Boolean get() = failure == null && !stopped && completed.size == itemCount
}
