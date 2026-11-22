package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant

enum class VariationState { ACTIVE, ARCHIVED }

/**
 * One stored sweep entry (plan Key Decision 1): a [config] that never changes, replayed on every buy swept while the variation is
 * [VariationState.ACTIVE]. An edit archives it and creates its replacement under the next [seq]; [label]s are never reused.
 * [activeFrom] null means "since the plan began", which is what a seeded variation has.
 */
data class Variation(
    val planId: String,
    val label: String,
    val seq: Int,
    val state: VariationState,
    val config: SimulationConfig,
    val activeFrom: Instant?,
    val archivedAt: Instant?,
    val replacesLabel: String?,
    val replacedByLabel: String?,
    val createdAt: Instant,
) {
    init {
        require((state == VariationState.ARCHIVED) == (archivedAt != null)) {
            "A variation is archived exactly when it has an archive time planId=$planId label=$label state=$state archivedAt=$archivedAt"
        }
    }

    val life: VariationLife get() = VariationLife(activeFrom, archivedAt)

    companion object {
        /**
         * The seed of [plan]: its entries as active variations in plan order, numbered from zero so that the label `V` plus the number is the entry's own,
         * with no creation day. An entry with another label is refused: an edit names its replacement by the next number and would meet that entry's key.
         */
        fun seed(plan: SweepPlan, createdAt: Instant): List<Variation> = plan.entries.mapIndexed { index, entry ->
            require(entry.label == VariationEditRules.labelOf(index)) {
                "A code plan's labels must be V and the entry's index planId=${plan.id} label=${entry.label} expected=${VariationEditRules.labelOf(index)}"
            }
            Variation(plan.id, entry.label, index, VariationState.ACTIVE, entry.config, null, null, null, null, createdAt)
        }
    }
}
