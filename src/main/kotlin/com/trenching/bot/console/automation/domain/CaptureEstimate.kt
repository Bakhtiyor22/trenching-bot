package com.trenching.bot.console.automation.domain

/**
 * Plan Rule 42: a dry run estimates the capture it would cost as the median provider requests of the wallet's captured items,
 * rounded up to whole requests, times the buys passing the filter; with no captured item there is no estimate.
 */
object CaptureEstimate {
    /**
     * The median of [count] values in ascending order, [valueAt] reading the one at an index; the mean of the two middle values of
     * an even count, rounded up. Null for no value.
     */
    fun median(count: Long, valueAt: (Long) -> Long): Long? {
        if (count <= 0) return null
        val low = valueAt((count - 1) / 2)
        val high = valueAt(count / 2)
        return (low + high + 1) / 2
    }

    fun requests(median: Long?, passingBuys: Long): Long? = median?.times(passingBuys)
}
