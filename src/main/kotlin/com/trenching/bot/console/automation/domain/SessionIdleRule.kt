package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant

/** Plan Rule 10: a session is a run of buys with no gap longer than [idle]. */
data class SessionIdleRule(val idle: Duration) {
    /**
     * A buy at [blockTime] belongs to a session spanning [firstBuyAt] to [lastBuyAt] when it leaves no gap longer than [idle]:
     * from [idle] before the first buy to [idle] after the last, both ends included. A catch-up delivers buys older than the
     * sessions it already opened, so both sides count.
     */
    fun fits(firstBuyAt: Instant, lastBuyAt: Instant, blockTime: Instant): Boolean =
        !blockTime.isBefore(firstBuyAt.minus(idle)) && !blockTime.isAfter(lastBuyAt.plus(idle))

    /** [buys], in time order by [at], split into runs wherever two consecutive buys are more than [idle] apart. */
    fun <T> runs(buys: List<T>, at: (T) -> Instant): List<List<T>> {
        val runs = mutableListOf<MutableList<T>>()
        buys.forEach { buy ->
            val run = runs.lastOrNull()?.takeIf { !at(buy).isAfter(at(it.last()).plus(idle)) }
            if (run == null) runs += mutableListOf(buy) else run += buy
        }
        return runs
    }

    /**
     * A session whose last buy is before this instant is idle: every buy up to [discoveredThrough] has been discovered, so none
     * within [idle] after its last buy is still to come.
     */
    fun idleBefore(discoveredThrough: Instant): Instant = discoveredThrough.minus(idle)
}
