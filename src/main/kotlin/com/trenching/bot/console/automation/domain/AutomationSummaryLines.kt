package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.LogDurations
import java.time.Duration
import java.time.Instant

/**
 * The words and keys of the hourly summary (plan Key Decision 9): what the automation did in the period, what waits now, and the
 * requests of the last 24 hours against the cap. It is a WARN while any hold is in force, so a stuck bot shows every hour, and an INFO
 * otherwise. It is also a heartbeat (plan Open Question 5): an hour in which nothing happened and nothing waits still says so, with the
 * discovery scans that ran, so a missing line means the automation is not running.
 */
object AutomationSummaryLines {
    /**
     * [counts] is the period's activity, [waiting] the items still in the pipeline by state, [holds] those in force at [now]. With
     * [captureEnabled] off nothing advances a `DISCOVERED` item, so those wait for capture to be switched on and are not "in capture". The sentence
     * opens with `Since <since>:` instead of `Last hour:` when [sinceStart] is set, the first summary after a start, which covers less.
     */
    fun of(
        counts: AutomationActivityCounts,
        waiting: Map<AutomationItemState, Long>,
        holds: List<AutomationHold>,
        requestsLast24h: Long,
        cap: Long,
        captureEnabled: Boolean,
        sinceStart: Boolean,
        now: Instant,
    ): AutomationSummaryLine {
        val total = AutomationItemState.NON_TERMINAL.sumOf { count(waiting, it) }
        val blocked = count(waiting, AutomationItemState.BLOCKED_BUDGET)
        val awaitingSweep = count(waiting, AutomationItemState.CAPTURED)
        val awaitingCaptureOn = if (captureEnabled) 0L else count(waiting, AutomationItemState.DISCOVERED)
        val inCapture = total - blocked - awaitingSweep - awaitingCaptureOn
        val idle = counts.isEmpty && total == 0L && holds.isEmpty()

        val sentence = Sentence()
        if (sinceStart) sentence.add("Since {}:", counts.since) else sentence.add("Last hour:")
        if (idle) {
            sentence.add(" nothing new; {} {}.", counts.discoveryScans, if (counts.discoveryScans == 1) "discovery scan" else "discovery scans")
        } else {
            if (holds.isNotEmpty()) {
                sentence.add(" " + holds.joinToString(" and ") { "${it.kind.inSummary} for {}" } + ";", *holds.map { LogDurations.compact(heldFor(it, now)) }.toTypedArray())
            }
            val done = doneClauses(counts, blocked)
            sentence.add(" " + done.joinToString(", ") { it.first }, *done.map { it.second }.toTypedArray())
            val pending = listOf(
                "{} in capture" to inCapture, "{} awaiting the sweep" to awaitingSweep, "{} waiting for capture to be switched on" to awaitingCaptureOn,
            ).filter { it.second > 0 }
            if (pending.isNotEmpty()) sentence.add("; " + pending.joinToString(", ") { it.first }, *pending.map { it.second }.toTypedArray())
            sentence.add(".")
        }

        return AutomationSummaryLine(
            level = if (holds.isEmpty()) AutomationLogLevel.INFO else AutomationLogLevel.WARN,
            format = sentence.format(),
            arguments = sentence.arguments(),
            fields = listOf(
                "found" to counts.liveBuysFound, "backfillFound" to counts.backfillBuysFound, "captured" to counts.captured, "skipped" to counts.skipped,
                "failed" to counts.failed, "simulated" to counts.simulated, "retried" to counts.retriedAttempts,
                "failedCodes" to counts.failedByCode.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                "waitingForCap" to blocked, "inCapture" to inCapture, "awaitingSweep" to awaitingSweep, "awaitingCaptureOn" to awaitingCaptureOn.takeIf { !captureEnabled }, "discoveryScans" to counts.discoveryScans,
                "requestsLast24h" to requestsLast24h, "cap" to cap, "since" to counts.since,
            ),
        )
    }

    /** What was done in the period: buys found and captured always, the rest when there is any, and the buys waiting for the cap. */
    private fun doneClauses(counts: AutomationActivityCounts, blocked: Long): List<Pair<String, Number>> {
        val found = counts.liveBuysFound + counts.backfillBuysFound
        return listOfNotNull(
            (if (found == 1) "{} buy found" else "{} buys found") to found,
            "{} captured" to counts.captured,
            ("{} skipped" to counts.skipped).takeIf { counts.skipped > 0 },
            ("{} simulated" to counts.simulated).takeIf { counts.simulated > 0 },
            ("{} failed" to counts.failed).takeIf { counts.failed > 0 },
            ("{} retried" to counts.retriedAttempts).takeIf { counts.retriedAttempts > 0 },
            ("{} waiting" to blocked).takeIf { blocked > 0 },
        )
    }

    private fun heldFor(hold: AutomationHold, now: Instant): Duration = Duration.between(hold.since, now).coerceAtLeast(Duration.ZERO)

    private fun count(waiting: Map<AutomationItemState, Long>, state: AutomationItemState): Long = waiting[state] ?: 0L

    private class Sentence {
        private val format = StringBuilder()
        private val arguments = mutableListOf<Any>()

        fun add(text: String, vararg values: Any) {
            format.append(text)
            arguments.addAll(values)
        }

        fun format(): String = format.toString()

        fun arguments(): List<Any> = arguments.toList()
    }
}
