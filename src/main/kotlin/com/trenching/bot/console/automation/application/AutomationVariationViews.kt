package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import org.springframework.stereotype.Component

/** Stored variations as [VariationView]s: each is read together with the variation it replaced, which its change list is compared with. */
@Component
@ConditionalOnAutomationConsole
class AutomationVariationViews(
    private val variations: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
) {
    /** The view of [entity], with one read for the variation it replaced. */
    fun of(entity: AutomationVariationEntity): VariationView = all(listOf(entity)).single()

    /** The views of [entities], which are read from the plan's own rows when they hold the replaced variation too; any other costs one read. */
    fun all(entities: List<AutomationVariationEntity>): List<VariationView> {
        val byLabel = entities.associateBy { it.label }
        return entities.map { entity ->
            val replaced = entity.replacesLabel?.let { label -> byLabel[label] ?: variations.findOneByPlanIdAndLabel(entity.planId, label) }
            VariationView.of(mapper.toVariation(entity), replaced?.let(mapper::toVariation))
        }
    }
}
