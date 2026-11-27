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

    /** [names] holds the name of every variation of the plan by label; a verdict whose label is missing fails the build of the message. */
    fun of(day: LocalDate, verdicts: List<DayVerdict>, names: Map<String, VariationName>): OutboundNotification? {
        if (verdicts.isEmpty() || verdicts.any { it.standing is DayStanding.Pending }) return null
        val nameOf = { verdict: DayVerdict -> checkNotNull(names[verdict.label]) { "A verdict names no variation of the plan planId=${verdict.planId} label=${verdict.label}" } }
        val successes = verdicts.filter { it.status == EvaluationStatus.SUCCESS.name }
            .sortedWith(compareByDescending<DayVerdict> { it.tally.worstCasePnl }.then(compareBy(VariationName.ORDER, nameOf)))
        val others = EvaluationStatus.entries.filter { it != EvaluationStatus.SUCCESS }.mapNotNull { status ->
            verdicts.count { it.status == status.name }.takeIf { it > 0 }?.let { "$it ${status.name}" }
        }
        val lines = successes.map { successLine(it, nameOf(it)) } + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { "Others: ${it.joinToString(", ")}" })
        val title = "Day $day: ${successes.size} of ${verdicts.size} variations ${EvaluationStatus.SUCCESS.name}"
        return OutboundNotification(key(verdicts.first().planId, day), NotificationKind.DAY_VERDICT, title, lines, listOf(NotificationLink(DAY_LINK, "/automation/days/$day")))
    }

    fun key(planId: String, day: LocalDate): String = "${NotificationKind.DAY_VERDICT.name}|$planId|$day"

    private fun successLine(verdict: DayVerdict, name: VariationName): String =
        "${name.text} ${EvaluationStatus.SUCCESS.name}: worst case ${NotificationText.signedSol(verdict.tally.worstCasePnl)} over ${verdict.tally.signals} signals"
}
