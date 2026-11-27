package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationName
import com.trenching.bot.console.automation.domain.VariationNames
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component

/**
 * A plan's stored variations with their names. A name depends on the replaced rows of its line, so every read here takes all of
 * the plan's rows (at most [AutomationLimits.MAX_VARIATIONS_PER_PLAN]) with one query and derives the names from them.
 */
@Component
@ConditionalOnAutomationConsole
class AutomationVariationViews(
    private val variations: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
) {
    /** The plan's variations in sequence order. */
    fun variations(planId: String): List<Variation> =
        variations.findAllByPlanIdOrderBySeqAsc(planId, LIMIT).map(mapper::toVariation)

    fun names(planId: String): Map<String, VariationName> = VariationNames.of(variations(planId))

    /** The plan's variations as views, in sequence order. */
    fun plan(planId: String): List<VariationView> = of(variations(planId))

    /** The views of [plan], which must be every variation of one plan: each is compared with the variation it replaced, which is among them. */
    private fun of(plan: List<Variation>): List<VariationView> {
        val names = VariationNames.of(plan)
        val byLabel = plan.associateBy { it.label }
        return plan.map { variation ->
            VariationView.of(variation, names.getValue(variation.label), variation.replacesLabel?.let(byLabel::get))
        }
    }

    private companion object {
        val LIMIT: Limit = Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN)
    }
}
