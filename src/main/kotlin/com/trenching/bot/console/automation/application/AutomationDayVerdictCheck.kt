package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.DayVerdict
import com.trenching.bot.console.automation.domain.DayVerdictMessage
import com.trenching.bot.console.automation.domain.EvaluationSort
import com.trenching.bot.notification.NotificationPublisher
import java.time.Clock
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * The hourly check that publishes each scored trading day once (notification plan Key Decision 4 and Rule 5). It reads the verdict
 * rows of the last `evaluation-window-sessions` trading days, today's included, through [AutomationDayService] (one read for the
 * whole span, never one per day) and publishes a day whose rows are all decided, oldest day first; the publisher stores a day's message once, so a
 * later verdict that changes is not sent again. It holds no transaction while it publishes, and a failure is logged and left for
 * the next hour, so it never breaks the scheduling thread.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationDayVerdictCheck(
    private val days: AutomationDayService,
    private val publisher: NotificationPublisher,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationDayVerdictCheck::class.java)
    private val calendar = properties.tradingCalendar()

    fun publishDecidedDays() {
        try {
            val today = calendar.dayOf(clock.instant())
            val first = today.minusDays(properties.evaluationWindowSessions - 1L)
            verdicts(first.date, today.date).groupBy { it.day }.toSortedMap()
                .forEach { (day, rows) -> DayVerdictMessage.of(day, rows)?.let(publisher::publish) }
        } catch (e: Exception) {
            LogLine("Unexpected error while checking the trading days for a verdict to publish; the next hourly check tries again.").error(log, e)
        }
    }

    /** Every row of the span, read a page at a time; a span of a week holds a page unless the plan has more than 14 variations alive. */
    private fun verdicts(from: LocalDate, to: LocalDate): List<DayVerdict> {
        val rows = mutableListOf<DayVerdict>()
        var page = 0
        do {
            val read = days.evaluations(from, to, null, null, EvaluationSort.DAY_DESC.value, page, AutomationPaging.MAX_PAGE_SIZE)
            rows += read.items
            page += 1
        } while (rows.size < read.total && read.items.isNotEmpty())
        return rows
    }
}
