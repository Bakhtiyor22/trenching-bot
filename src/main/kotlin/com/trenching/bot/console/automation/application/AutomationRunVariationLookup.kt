package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.VariationName
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.domain.RunVariationLookup
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service

/** Names the variations of the configured sweep plan that produced each run, ordered by name, so the run views can link to them, and the configuration hashes a variation ran with, so a run search can filter by it. */
@Service
@ConditionalOnAutomationConsole
class AutomationRunVariationLookup(
    private val sweepRuns: AutomationSweepRunRepository,
    private val variations: AutomationVariationRepository,
    private val views: AutomationVariationViews,
    properties: AutomationProperties,
) : RunVariationLookup {
    private val planId = properties.sweepPlan

    override fun labels(runIds: Collection<String>): Map<String, List<String>> {
        if (runIds.isEmpty()) return emptyMap()
        val rows = sweepRuns.findRunLabels(runIds, planId)
        if (rows.isEmpty()) return emptyMap()
        val names = views.names(planId)
        val byName = compareBy<String, VariationName?>(nullsLast(VariationName.ORDER)) { names[it] }.thenBy { it }
        return rows
            .groupBy({ it.runId }, { it.label })
            .mapValues { (_, labels) -> labels.distinct().sortedWith(byName) }
    }

    override fun configurationHashes(label: String): Set<String>? {
        if (variations.findOneByPlanIdAndLabel(planId, label) == null) return null
        val hashes = sweepRuns.findConfigurationHashes(label, planId, HASH_LIMIT).toSet()
        check(hashes.size <= AutomationLimits.MAX_HASHES_PER_VARIATION) {
            "A variation has more configuration hashes than a run filter reads planId=$planId label=$label limit=${AutomationLimits.MAX_HASHES_PER_VARIATION}"
        }
        return hashes
    }

    private companion object {
        val HASH_LIMIT: Limit = Limit.of(AutomationLimits.MAX_HASHES_PER_VARIATION + 1)
    }
}
