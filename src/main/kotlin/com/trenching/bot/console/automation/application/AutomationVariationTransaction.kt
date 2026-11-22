package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationEditResult
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.domain.VariationLimitReachedException
import com.trenching.bot.console.automation.domain.VariationNotFoundException
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** An edit as one short transaction (plan Rule 2): the variation is archived and its replacement stored, or neither. No remote call, no replay. */
@Service
@ConditionalOnAutomationConsole
class AutomationVariationTransaction(
    private val variations: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
    private val views: AutomationVariationViews,
) {
    /**
     * Archives [label] at [now] and stores its replacement under the next sequence number, active from [now]. The plan's active rows
     * are locked first, to the end of the transaction, so edits of one plan run one after the other and each reads what the one
     * before it left. What was true when the caller checked is checked again under the lock: the request id (a repeat that raced
     * answers with the stored variation), then the refusals of [VariationEditRules.requireEditable], and that the new sequence number stays within [AutomationLimits.MAX_VARIATIONS_PER_PLAN], read after the lock is held.
     */
    @Transactional
    fun edit(planId: String, label: String, requestId: String, config: SimulationConfig, now: Instant): VariationEditResult {
        variations.findAllForUpdateByPlanIdAndStateOrderBySeqAsc(planId, VariationState.ACTIVE, LIMIT)
        existing(planId, requestId)?.let { return VariationEditResult(it, false) }
        val target = variations.findOneByPlanIdAndLabel(planId, label) ?: throw VariationNotFoundException(label)
        val replaced = mapper.toVariation(target)
        val active = variations.findAllByPlanIdAndStateOrderBySeqAsc(planId, VariationState.ACTIVE, LIMIT).map(mapper::toVariation)
        VariationEditRules.requireEditable(replaced, config, active)
        val seq = checkNotNull(variations.findMaxSeq(planId)) { "A plan with a variation has a sequence number planId=$planId" } + 1
        if (seq >= AutomationLimits.MAX_VARIATIONS_PER_PLAN) throw VariationLimitReachedException(planId)
        val created = Variation(planId, VariationEditRules.labelOf(seq), seq, VariationState.ACTIVE, config, now, null, label, null, now)
        target.state = VariationState.ARCHIVED
        target.archivedAt = now
        target.replacedByLabel = created.label
        variations.saveAndFlush(target)
        variations.saveAndFlush(mapper.toEntity(created, requestId))
        return VariationEditResult(VariationView.of(created, replaced), true)
    }

    /** The variation the edit with [requestId] created, null when no edit of the plan had that request id. */
    @Transactional(readOnly = true)
    fun existing(planId: String, requestId: String): VariationView? =
        variations.findOneByPlanIdAndRequestId(planId, requestId)?.let(views::of)

    private companion object {
        val LIMIT: Limit = Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN)
    }
}
