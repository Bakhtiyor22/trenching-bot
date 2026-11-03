package com.trenching.bot.console.automation.domain

/**
 * The time a wallet's buys have been discovered without gaps (plan Rule 32), as half-open ranges merged where they touch or
 * overlap. Live discovery supplies one range; a finished capturing backfill adds its own, which is where the backfill's coverage
 * joins in.
 */
class CoverageRanges(ranges: List<TimeRange>) {
    val ranges: List<TimeRange> = merged(ranges)

    /** Whether the whole of [range] lies inside one covered range; an empty range is covered where a covered range stands. */
    fun covers(range: TimeRange): Boolean = ranges.any { !range.from.isBefore(it.from) && !range.to.isAfter(it.to) }

    companion object {
        val NONE = CoverageRanges(emptyList())

        private fun merged(ranges: List<TimeRange>): List<TimeRange> {
            val merged = mutableListOf<TimeRange>()
            ranges.sortedBy { it.from }.forEach { next ->
                val last = merged.lastOrNull()
                if (last != null && !next.from.isAfter(last.to)) {
                    if (next.to.isAfter(last.to)) merged[merged.lastIndex] = TimeRange(last.from, next.to)
                } else {
                    merged += next
                }
            }
            return merged
        }
    }
}
