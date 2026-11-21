package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationLogLevel
import com.trenching.bot.console.automation.domain.AutomationSummaryLines
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The hourly line (plan Key Decision 9): what the automation did since the last one, what waits now, and the requests of the last
 * 24 hours against the cap, as an INFO, or as a WARN while a hold keeps capture back, so a stuck bot shows every hour. It takes the
 * hour's counts from [AutomationItemLog], the items by state from one grouped count, the requests from the usage report that `/status`
 * reads, and the holds in force from [AutomationHolds]. It runs on the scheduling thread, one read-only transaction and no remote call.
 * It is a heartbeat: it is logged every hour while automation is enabled, an idle hour too (`nothing new`, with the discovery scans that
 * ran), so a runbook alert can fire on its absence. The first one after a start covers less than an hour and says so.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationActivitySummary(
    private val activity: AutomationItemLog,
    private val items: AutomationSummaryTransaction,
    private val holds: AutomationHolds,
    private val usage: ApiUsageReports,
    private val properties: AutomationProperties,
) {
    private val log = LoggerFactory.getLogger(AutomationActivitySummary::class.java)
    private var sinceStart = true

    fun log(now: Instant) {
        holds.settle(now)
        val waiting = items.waitingItemsByState()
        val requestsLast24h = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests
        val line = AutomationSummaryLines.of(activity.drain(now), waiting, holds.current(), requestsLast24h, properties.maxRequestsPer24h, properties.captureEnabled, sinceStart, now)
        sinceStart = false
        val message = line.fields.fold(LogLine(line.format, *line.arguments.toTypedArray())) { message, (key, value) -> message.field(key, value) }
        when (line.level) {
            AutomationLogLevel.DEBUG -> message.debug(log)
            AutomationLogLevel.INFO -> message.info(log)
            AutomationLogLevel.WARN -> message.warn(log)
        }
    }
}
