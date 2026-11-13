package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemView
import com.trenching.bot.console.automation.domain.AutomationRetryRule
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import org.springframework.stereotype.Service

/** Item rows to the views the read API returns, each with whether a person could retry it now ([AutomationRetryRule]). */
@Service
@ConditionalOnAutomationConsole
class AutomationItemViews(
    private val backfills: AutomationBackfillRepository,
    private val mapper: AutomationRecordMapper,
    private val properties: AutomationProperties,
) {
    /** One query for the backfills of all [items], however many there are. */
    fun of(items: List<AutomationItemEntity>): List<AutomationItemView> {
        val backfillStates = backfills.findAllById(items.mapNotNull { it.backfillId }.distinct()).associate { it.id to it.state }
        return items.map { item ->
            val refusal = AutomationRetryRule.refusal(mapper.toProgress(item), item.backfillId, backfillStates[item.backfillId], properties.captureEnabled)
            mapper.toItem(item).copy(retryRefusal = refusal?.code)
        }
    }

    fun of(item: AutomationItemEntity): AutomationItemView = of(listOf(item)).single()
}
