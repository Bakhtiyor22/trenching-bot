package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant

/**
 * Plan Rules 10 and 31: a session is a run of buys with no gap longer than [idle], inside one trading day of [calendar]. Buys
 * 20 minutes apart across the day boundary are two sessions.
 */
data class SessionIdleRule(val idle: Duration, val calendar: TradingCalendar) {
    /**
     * A buy at [blockTime] belongs to a session spanning [firstBuyAt] to [lastBuyAt] when it leaves no gap longer than [idle]:
     * from [idle] before the first buy to [idle] after the last, both ends included, and it falls on the session's trading day.
     * A catch-up delivers buys older than the sessions it already opened, so both sides count.
     */
    fun fits(firstBuyAt: Instant, lastBuyAt: Instant, blockTime: Instant): Boolean =
        !blockTime.isBefore(firstBuyAt.minus(idle)) && !blockTime.isAfter(lastBuyAt.plus(idle)) && sameDay(firstBuyAt, blockTime)

    /** [buys], in time order by [at], split into runs wherever two consecutive buys are more than [idle] apart or on different trading days. */
    fun <T> runs(buys: List<T>, at: (T) -> Instant): List<List<T>> {
        val runs = mutableListOf<MutableList<T>>()
        buys.forEach { buy ->
            val run = runs.lastOrNull()?.takeIf { !at(buy).isAfter(at(it.last()).plus(idle)) && sameDay(at(it.last()), at(buy)) }
            if (run == null) runs += mutableListOf(buy) else run += buy
        }
        return runs
    }

    /**
     * A session whose last buy is before this instant is idle: every buy up to [discoveredThrough] has been discovered, so none
     * within [idle] after its last buy is still to come.
     */
    fun idleBefore(discoveredThrough: Instant): Instant = discoveredThrough.minus(idle)

    private fun sameDay(a: Instant, b: Instant): Boolean = calendar.dayOf(a) == calendar.dayOf(b)
}
