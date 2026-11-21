package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemDetail
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationRequests
import com.trenching.bot.console.automation.domain.AutomationRetryRefusedException
import com.trenching.bot.console.automation.domain.AutomationRetryResult
import com.trenching.bot.console.automation.domain.DayRetryResult
import com.trenching.bot.console.automation.domain.TradingDay
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

/**
 * Sends failed buys back through capture or the sweep (see [AutomationRetryTransaction] and [com.trenching.bot.console.automation.domain.AutomationRetryRule]):
 * one item, or every `FAILED` item of a trading day, oldest buy first, each in its own transaction so a refused or contended item
 * never undoes the others. A day retry gives each item the request id `<requestId>-<item>` (see [AutomationRequests.dayItem]), so
 * repeating the request retries nothing twice and reports the items it had already retried as unchanged. An item changed by the
 * pipeline, a manual close or its ending backfill while its retry committed is read again once; it is then refused, never
 * overwritten, and a second loss is answered from a fresh read, never with a conflict. The log line of a retry is written here, after
 * its transaction committed, so a retry that lost logs nothing. The times stored are at the database's microsecond precision.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationRetryService(
    private val transaction: AutomationRetryTransaction,
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val query: AutomationQueryService,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationRetryService::class.java)
    private val calendar = properties.tradingCalendar()

    fun retry(itemId: String, requestId: String?): AutomationItemDetail {
        retryOnce(itemId, AutomationRequests.requireRequestId(requestId))
        return query.item(itemId)
    }

    fun retryDay(date: LocalDate, requestId: String?): DayRetryResult {
        val validRequestId = AutomationRequests.requireRequestId(requestId)
        val range = calendar.range(TradingDay(date))
        val wallet = properties.wallet
        val sessionIds = wallet?.let { sessions.findStartedIn(it, range.from, range.to) }.orEmpty().map { it.id }
        val failed = if (sessionIds.isEmpty()) emptyList() else items.findIdsBySessionIdInAndState(sessionIds, AutomationItemState.FAILED)
        val retried = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val refused = linkedMapOf<String, String>()
        failed.forEach { itemId ->
            try {
                when (retryOnce(itemId, AutomationRequests.dayItem(validRequestId, itemId))) {
                    is AutomationRetryResult.Retried -> retried += itemId
                    AutomationRetryResult.Unchanged -> unchanged += itemId
                }
            } catch (e: AutomationRetryRefusedException) {
                refused[itemId] = e.refusal.code
            }
        }
        return DayRetryResult(date, retried, unchanged, refused)
    }

    private fun retryOnce(itemId: String, requestId: String): AutomationRetryResult {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return try {
            transaction.retry(itemId, requestId, properties.captureEnabled, now)
        } catch (e: OptimisticLockingFailureException) {
            retryAgain(itemId, requestId, now)
        }
    }

    private fun retryAgain(itemId: String, requestId: String, now: Instant): AutomationRetryResult =
        try {
            transaction.retry(itemId, requestId, properties.captureEnabled, now)
        } catch (e: OptimisticLockingFailureException) {
            LogLine("Buy retry lost to concurrent changes twice; the item is read again to say why.").field("itemId", itemId).warn(log)
            transaction.afterConflict(itemId, requestId, properties.captureEnabled)
        }
}
