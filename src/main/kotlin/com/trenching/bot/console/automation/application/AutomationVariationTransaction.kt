package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationArchiveResult
import com.trenching.bot.console.automation.domain.VariationEditResult
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.domain.VariationNames
import com.trenching.bot.console.automation.domain.VariationNotFoundException
import com.trenching.bot.console.automation.domain.VariationRequestReusedException
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Each change to a plan's variations as one short transaction (plan Rule 2): an edit archives the variation and stores its replacement, or neither; a creation
 * stores one new variation; an archive ends one. No remote call, no replay, and no row is ever deleted.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationVariationTransaction(
    private val variations: AutomationVariationRepository,
    private val mapper: AutomationVariationMapper,
    private val views: AutomationVariationViews,
) {
    /**
     * Archives [label] at [now] and stores its replacement, active from [now], under the next sequence number and the label of its
     * name (`V03.3` for an edit of `V03.2`). The plan's active rows
     * are locked first, to the end of the transaction, so edits of one plan run one after the other and each reads what the one
     * before it left. What was true when the caller checked is checked again under the lock: the request id (a repeat of this edit that raced
     * answers with the stored variation, another operation's id is refused), then the refusals of [VariationEditRules.requireEditable], and that the new sequence number stays within [AutomationLimits.MAX_VARIATIONS_PER_PLAN], read after the lock is held.
     */
    @Transactional
    fun edit(planId: String, label: String, requestId: String, config: SimulationConfig, now: Instant): VariationEditResult {
        lockActive(planId)
        existing(planId, requestId, label)?.let { return VariationEditResult(it, false) }
        val target = variations.findOneByPlanIdAndLabel(planId, label) ?: throw VariationNotFoundException(label)
        val plan = views.variations(planId)
        val replaced = plan.first { it.label == label }
        VariationEditRules.requireEditable(replaced, config, plan.filter { it.state == VariationState.ACTIVE })
        val seq = nextSeq(planId)
        val createdLabel = VariationNames.editLabel(VariationNames.of(plan).getValue(label))
        val created = Variation(planId, createdLabel, seq, VariationState.ACTIVE, config, now, null, label, null, now)
        val name = VariationNames.of(plan + created).getValue(createdLabel)
        target.state = VariationState.ARCHIVED
        target.archivedAt = now
        target.replacedByLabel = created.label
        variations.saveAndFlush(target)
        variations.saveAndFlush(mapper.toEntity(created, requestId))
        return VariationEditResult(VariationView.of(created, name, replaced), true)
    }

    /**
     * Stores a new variation with [config], active from [now], under the plan's next base label (`V16` after `V15`), replacing none. The plan's active rows are
     * locked first, as for an edit, so creations and edits of one plan run one after the other; the request id (a repeat of this creation that raced answers with the stored
     * variation, another operation's id is refused), a duplicate of an active variation and the bound of the plan are checked again under the lock.
     */
    @Transactional
    fun create(planId: String, requestId: String, config: SimulationConfig, now: Instant): VariationEditResult {
        lockActive(planId)
        existing(planId, requestId, null)?.let { return VariationEditResult(it, false) }
        val plan = views.variations(planId)
        VariationEditRules.requireCreatable(config, plan.filter { it.state == VariationState.ACTIVE })
        val seq = nextSeq(planId)
        val label = VariationNames.nextBaseLabel(plan.map { it.label })
        val created = Variation(planId, label, seq, VariationState.ACTIVE, config, now, null, null, null, now)
        variations.saveAndFlush(mapper.toEntity(created, requestId))
        return VariationEditResult(VariationView.of(created, VariationNames.of(plan + created).getValue(label), null), true)
    }

    /**
     * Ends [label] at [now] without a replacement: its state becomes ARCHIVED and `replacedByLabel` stays empty. Under the plan's lock, so it cannot overlap an edit of
     * the same variation. An archived variation, whether an edit or an earlier archive ended it, is answered as it is and nothing is written.
     */
    @Transactional
    fun archive(planId: String, label: String, now: Instant): VariationArchiveResult {
        lockActive(planId)
        val target = variations.findOneByPlanIdAndLabel(planId, label) ?: throw VariationNotFoundException(label)
        VariationEditRules.requireArchivable(label)
        val archived = target.state == VariationState.ACTIVE
        if (archived) {
            target.state = VariationState.ARCHIVED
            target.archivedAt = now
            variations.saveAndFlush(target)
        }
        return VariationArchiveResult(views.plan(planId).first { it.variation.label == label }, archived)
    }

    /**
     * The variation [requestId] already stored, null when no variation of the plan has that request id. A request id belongs to one operation: it is a repeat only
     * when the stored variation replaces [replacesLabel], null for a creation and the edited label for an edit; any other stored variation refuses it.
     */
    @Transactional(readOnly = true)
    fun existing(planId: String, requestId: String, replacesLabel: String?): VariationView? {
        val stored = variations.findOneByPlanIdAndRequestId(planId, requestId) ?: return null
        if (stored.replacesLabel != replacesLabel) throw VariationRequestReusedException(requestId, stored.label)
        return views.plan(planId).first { it.variation.label == stored.label }
    }

    private fun lockActive(planId: String) {
        variations.findAllForUpdateByPlanIdAndStateOrderBySeqAsc(planId, VariationState.ACTIVE, LIMIT)
    }

    /** One past the plan's highest sequence number, which must stay within the bound of a plan; read after the lock is held. */
    private fun nextSeq(planId: String): Int {
        val seq = checkNotNull(variations.findMaxSeq(planId)) { "A plan with a variation has a sequence number planId=$planId" } + 1
        VariationEditRules.requireWithinLimit(planId, seq)
        return seq
    }

    private companion object {
        val LIMIT: Limit = Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN)
    }
}
