package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The seed of a plan's variations as one transaction: all of its rows or none, so a plan is never half seeded. */
@Service
@ConditionalOnAutomationConsole
class AutomationVariationSeedTransaction(
    private val variations: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
) {
    /** Stores the entries of [plan] as its active variations unless the plan has variations already; returns whether it did. */
    @Transactional
    fun seed(plan: SweepPlan, now: Instant): Boolean {
        if (variations.existsByPlanId(plan.id)) return false
        variations.saveAllAndFlush(Variation.seed(plan, now).map { mapper.toEntity(it) })
        return true
    }
}
