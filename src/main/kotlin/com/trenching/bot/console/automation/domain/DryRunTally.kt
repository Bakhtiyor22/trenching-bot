package com.trenching.bot.console.automation.domain

import java.time.LocalDate
import java.util.SortedMap

/** A dry run's result as the console shows it (plan Rule 42): [distinctMints] bought and the buys passing the filter per trading day. */
data class DryRunSummary(val distinctMints: Int, val buysPerDay: Map<LocalDate, Int>)

/**
 * What a dry run keeps between scan ticks, since it writes no item to count from: the buys passing the filter per trading day and
 * the distinct mints they bought. Never more than one entry per day of the range, at most 62.
 */
data class DryRunTally(val buysPerDay: SortedMap<LocalDate, Int>, val mints: Set<String>) {
    /** This tally with [buys] (trading day and mint of each) added. */
    fun plus(buys: List<Pair<LocalDate, String>>): DryRunTally {
        val perDay = buysPerDay.toSortedMap()
        buys.forEach { (day, _) -> perDay.merge(day, 1, Int::plus) }
        return DryRunTally(perDay, mints + buys.map { it.second })
    }

    fun summary(): DryRunSummary = DryRunSummary(mints.size, buysPerDay.toMap())

    companion object {
        val EMPTY = DryRunTally(sortedMapOf(), emptySet())
    }
}
