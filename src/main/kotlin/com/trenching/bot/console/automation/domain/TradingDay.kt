package com.trenching.bot.console.automation.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A half-open span of time, [from] included and [to] excluded. */
data class TimeRange(val from: Instant, val to: Instant) {
    init {
        require(!to.isBefore(from)) { "A time range must not end before it starts from=$from to=$to" }
    }

    fun contains(instant: Instant): Boolean = !instant.isBefore(from) && instant.isBefore(to)

    fun isEmpty(): Boolean = from == to
}

/**
 * One trading day (plan Rule 30), named by the date it starts on: the day of `2026-11-03` runs from that date at the day start
 * to the next date at the day start, both in the calendar's zone. Only `java.time`, so the zone's own rules decide the length.
 */
data class TradingDay(val date: LocalDate) : Comparable<TradingDay> {
    fun range(start: LocalTime, zone: ZoneId): TimeRange =
        TimeRange(date.atTime(start).atZone(zone).toInstant(), date.plusDays(1).atTime(start).atZone(zone).toInstant())

    fun minusDays(days: Long): TradingDay = TradingDay(date.minusDays(days))

    fun plusDays(days: Long): TradingDay = TradingDay(date.plusDays(days))

    /** This day and every day after it up to and including [last]; empty when [last] is earlier. */
    fun through(last: TradingDay): List<TradingDay> = generateSequence(this) { it.plusDays(1) }.takeWhile { it <= last }.toList()

    override fun compareTo(other: TradingDay): Int = date.compareTo(other.date)

    companion object {
        /** The day [instant] falls on: its local date after the day start is taken off the clock, so a buy exactly at the start is the new day's. */
        fun of(instant: Instant, start: LocalTime, zone: ZoneId): TradingDay =
            TradingDay(instant.atZone(zone).toLocalDateTime().minusNanos(start.toNanoOfDay()).toLocalDate())
    }
}

/** The day start and zone that name and bound trading days; what the services hold instead of passing both around. */
data class TradingCalendar(val start: LocalTime, val zone: ZoneId) {
    fun dayOf(instant: Instant): TradingDay = TradingDay.of(instant, start, zone)

    fun range(day: TradingDay): TimeRange = day.range(start, zone)
}
