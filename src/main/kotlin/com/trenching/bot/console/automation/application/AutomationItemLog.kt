package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationActivityCounts
import com.trenching.bot.console.automation.domain.AutomationActivityKind
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemLines
import com.trenching.bot.console.automation.domain.AutomationItemLogLine
import com.trenching.bot.console.automation.domain.AutomationItemSubject
import com.trenching.bot.console.automation.domain.AutomationLogLevel
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Says what happened to each buy, in one place under one logger name (plan Key Decision 7): [announce] turns an item's stored change
 * into its lines (see [AutomationItemLines]) and writes them once the transaction that stored it has committed, so a conflict or
 * rollback logs nothing that did not happen. What it writes it also counts, for the hourly summary to read with [drain].
 * Console actions run on HTTP threads and ticks on the scheduler, so the counts are kept under a lock.
 */
@Component
@ConditionalOnAutomationConsole
class AutomationItemLog(
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationItemLog::class.java)
    private val lock = Any()
    private var since: Instant = clock.instant()
    private var tally = Tally()

    /** Logs the lines of each change in [changes] after commit; an item without a subject, which the transaction no longer holds, says nothing. */
    fun announce(subjects: Map<String, AutomationItemSubject>, changes: Map<String, AutomationItemChange>) {
        val calendar = properties.tradingCalendar()
        val lines = changes.flatMap { (itemId, change) ->
            subjects[itemId]?.let { AutomationItemLines.of(it, change, calendar, properties.maxStepAttempts) }.orEmpty()
        }
        if (lines.isEmpty()) return
        AfterCommit.run { lines.forEach(::write) }
    }

    /** Counts one live discovery scan that ran, found or not, for the hourly summary to show that discovery is alive. */
    fun discoveryScanRan() {
        synchronized(lock) { tally.scans++ }
    }

    /** The counts of what was logged since the previous call, or since start for the first; they start again from zero. */
    fun drain(now: Instant): AutomationActivityCounts = synchronized(lock) {
        val counts = tally.toCounts(since, now)
        since = now
        tally = Tally()
        counts
    }

    private fun write(line: AutomationItemLogLine) {
        val message = line.fields.fold(LogLine(line.format, *line.arguments.toTypedArray())) { message, (key, value) -> message.field(key, value) }
        when (line.level) {
            AutomationLogLevel.DEBUG -> message.debug(log)
            AutomationLogLevel.INFO -> message.info(log)
            AutomationLogLevel.WARN -> message.warn(log)
        }
        line.activity?.let { activity -> synchronized(lock) { tally.add(activity, line.code) } }
    }

    private class Tally {
        private val counts = mutableMapOf<AutomationActivityKind, Int>()
        private val failed = mutableMapOf<String, Int>()
        var scans = 0

        fun add(activity: AutomationActivityKind, code: String?) {
            counts.merge(activity, 1, Int::plus)
            if (activity == AutomationActivityKind.FAILED && code != null) failed.merge(code, 1, Int::plus)
        }

        fun toCounts(since: Instant, until: Instant) = AutomationActivityCounts(
            since = since, until = until,
            liveBuysFound = count(AutomationActivityKind.LIVE_FOUND), backfillBuysFound = count(AutomationActivityKind.BACKFILL_FOUND),
            captured = count(AutomationActivityKind.CAPTURED), skipped = count(AutomationActivityKind.SKIPPED), simulated = count(AutomationActivityKind.SIMULATED),
            retriedAttempts = count(AutomationActivityKind.RETRIED_ATTEMPT), failedByCode = failed.toMap(),
            abandoned = count(AutomationActivityKind.ABANDONED), retriedByPerson = count(AutomationActivityKind.RETRIED_BY_PERSON), discoveryScans = scans,
        )

        private fun count(activity: AutomationActivityKind): Int = counts[activity] ?: 0
    }
}
