package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.domain.RunVariationLookup
import org.springframework.stereotype.Service

/** Names the variations of the configured sweep plan that produced each run, so the run views can link to them. */
@Service
@ConditionalOnAutomationConsole
class AutomationRunVariationLookup(
    private val sweepRuns: AutomationSweepRunRepository,
    properties: AutomationProperties,
) : RunVariationLookup {
    private val planId = properties.sweepPlan

    override fun labels(runIds: Collection<String>): Map<String, List<String>> {
        if (runIds.isEmpty()) return emptyMap()
        return sweepRuns.findRunLabels(runIds, planId)
            .groupBy({ it.runId }, { it.label })
            .mapValues { (_, labels) -> labels.distinct().sortedWith(BY_NUMBER) }
    }

    private companion object {
        val BY_NUMBER: Comparator<String> = compareBy<String> { VariationEditRules.seqOf(it) ?: Int.MAX_VALUE }.thenBy { it }
    }
}
