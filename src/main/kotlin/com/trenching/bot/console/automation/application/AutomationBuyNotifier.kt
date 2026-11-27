package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.BuyResultMessage
import com.trenching.bot.console.automation.domain.BuySubject
import com.trenching.bot.console.automation.domain.BuyVariationResult
import com.trenching.bot.console.automation.domain.VariationNames
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.notification.NotificationPublisher
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * Publishes the result message of a buy that has just become `SIMULATED` (notification plan Key Decision 3). It is called inside the
 * transaction that stores that state and joins it, so a rolled-back transition publishes nothing and a stored one has its message;
 * the publisher is idempotent on the buy's key. It reads the buy's sweep rows and the plan's variations (which give their names) with one query each, and
 * reads nothing for a backfilled buy. A message that cannot be built is logged and skipped, so the buy still becomes `SIMULATED`; a
 * failure of the read or of the publish itself is not caught and rolls the transition back.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationBuyNotifier(
    private val sweepRuns: AutomationSweepRunRepository,
    private val variations: AutomationVariationViews,
    private val publisher: NotificationPublisher,
    private val properties: AutomationProperties,
) {
    private val log = LoggerFactory.getLogger(AutomationBuyNotifier::class.java)
    private val message = BuyResultMessage(properties.tradingCalendar())

    fun publishResult(item: AutomationItemEntity) {
        if (item.backfillId != null) return
        val planId = properties.sweepPlan
        val planVariations = variations.variations(planId)
        val rows = sweepRuns.findAllByItemIdOrderByPlanIdAscLabelAsc(item.id, ROWS).filter { it.planId == planId }
        val notification = try {
            val names = VariationNames.of(planVariations)
            val results = rows.map { row ->
                val name = checkNotNull(names[row.label]) { "A sweep row names no variation of the plan itemId=${item.id} label=${row.label}" }
                BuyVariationResult(name, row.runId, row.outcome, row.positionState, row.realizedPnl)
            }
            message.of(BuySubject(item.id, item.mint, item.blockTime ?: item.createdAt, item.backfillId), results)
        } catch (e: RuntimeException) {
            LogLine("The result message of a buy could not be built and is skipped; the buy's state is still stored.").field("itemId", item.id).error(log, e)
            return
        }
        notification?.let(publisher::publish)
    }

    private companion object {
        val ROWS: PageRequest = PageRequest.of(0, AutomationLimits.MAX_VARIATIONS_PER_PLAN)
    }
}
