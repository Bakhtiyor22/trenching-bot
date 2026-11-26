package com.trenching.bot.console.automation.domain

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationLink
import com.trenching.bot.notification.OutboundNotification
import java.time.LocalDate

/**
 * The message for a scored trading day (notification plan Rule 5): which variations came out `SUCCESS`, with their worst-case P&L
 * and signal counts, and how many came out each other way. Nothing until the day has a row and none of its rows is pending. A pure
 * function of the day's rows.
 */
object DayVerdictMessage {
    private const val DAY_LINK = "Day"

    fun of(day: LocalDate, verdicts: List<DayVerdict>): OutboundNotification? {
        if (verdicts.isEmpty() || verdicts.any { it.standing is DayStanding.Pending }) return null
        val successes = verdicts.filter { it.status == EvaluationStatus.SUCCESS.name }
            .sortedWith(compareByDescending<DayVerdict> { it.tally.worstCasePnl }.thenBy { it.label })
        val others = EvaluationStatus.entries.filter { it != EvaluationStatus.SUCCESS }.mapNotNull { status ->
            verdicts.count { it.status == status.name }.takeIf { it > 0 }?.let { "$it ${status.name}" }
        }
        val lines = successes.map(::successLine) + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { "Others: ${it.joinToString(", ")}" })
        val title = "Day $day: ${successes.size} of ${verdicts.size} variations ${EvaluationStatus.SUCCESS.name}"
        return OutboundNotification(key(verdicts.first().planId, day), NotificationKind.DAY_VERDICT, title, lines, listOf(NotificationLink(DAY_LINK, "/automation/days/$day")))
    }

    fun key(planId: String, day: LocalDate): String = "${NotificationKind.DAY_VERDICT.name}|$planId|$day"

    private fun successLine(verdict: DayVerdict): String =
        "${verdict.label} ${EvaluationStatus.SUCCESS.name}: worst case ${NotificationText.signedSol(verdict.tally.worstCasePnl)} over ${verdict.tally.signals} signals"
}
