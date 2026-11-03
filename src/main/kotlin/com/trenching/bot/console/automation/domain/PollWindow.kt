package com.trenching.bot.console.automation.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Local times in [zone] when discovery runs, from [from] inclusive to [to] exclusive; it crosses midnight when [to] is earlier. */
data class PollWindow(val from: LocalTime, val to: LocalTime, val zone: ZoneId) {
    init {
        require(from != to) { "The polling window must not start and end at the same time from=$from" }
    }

    fun contains(instant: Instant): Boolean {
        val local = instant.atZone(zone).toLocalTime()
        return if (from < to) local >= from && local < to else local >= from || local < to
    }

    /** The most recent time the window closed, [to] in [zone], that is not after [instant]; [instant] itself when it is that time. */
    fun lastEndAtOrBefore(instant: Instant): Instant {
        val today = instant.atZone(zone).toLocalDate()
        val endToday = today.atTime(to).atZone(zone).toInstant()
        return if (endToday.isAfter(instant)) today.minusDays(1).atTime(to).atZone(zone).toInstant() else endToday
    }
}
