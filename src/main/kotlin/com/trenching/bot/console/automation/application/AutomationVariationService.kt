package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.api.dto.AutomationVariationEditRequest
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.InvalidVariationConfigException
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationEditResult
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.domain.VariationList
import com.trenching.bot.console.automation.domain.VariationNotFoundException
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.domain.ConfigValidation
import java.time.Clock
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The stored variations of the plan `trenching.automation.sweep-plan` names (plan Key Decisions 1–3). Once a plan has rows they
 * are the authority; the code plan only seeds an empty one. Every read is ordered by `seq` and bounded by
 * [AutomationLimits.MAX_VARIATIONS_PER_PLAN], and loads the plan once, so no caller reads per item or per day.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationVariationService(
    private val variations: AutomationVariationRepository,
    private val seeding: AutomationVariationSeedTransaction,
    private val edits: AutomationVariationTransaction,
    private val views: AutomationVariationViews,
    private val mapper: AutomationVariationMapper,
    private val validator: ConsoleConfigValidator,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationVariationService::class.java)
    private val planId = properties.sweepPlan

    /**
     * Stores the code plan's entries as the plan's variations when it has none, then checks the active ones (plan Rule 8). A plan
     * that has rows is left alone, so a later change to the code plan never rewrites history.
     */
    fun start() {
        seed()
        activePlan().requireCaptureMinimumWithin(properties.captureMinSignalSol)
    }

    /**
     * Idempotent: a second call, or a second instance seeding at the same moment, changes nothing. A concurrent seed that loses
     * on a unique key counts as seeded once the winner's rows are there.
     */
    fun seed() {
        val seeded = try {
            seeding.seed(properties.activePlan(), now())
        } catch (e: DataIntegrityViolationException) {
            if (!variations.existsByPlanId(planId)) throw e
            false
        }
        if (seeded) log.info("Sweep variations seeded from the code plan. planId={}", planId)
    }

    /** The plan's variations, active and archived. */
    @Transactional(readOnly = true)
    fun list(): List<Variation> = variations.findAllByPlanIdOrderBySeqAsc(planId, LIMIT).map(mapper::toVariation)

    /** The plan's variations as the console reads them: [list], each with what differs from the agreed configuration and from the variation it replaced. */
    @Transactional(readOnly = true)
    fun listViews(state: String?): VariationList {
        val wanted = state?.let(::parse)
        val all = views.all(variations.findAllByPlanIdOrderBySeqAsc(planId, LIMIT))
        return VariationList(planId, all.filter { wanted == null || it.variation.state == wanted })
    }

    @Transactional(readOnly = true)
    fun findView(label: String): VariationView =
        variations.findOneByPlanIdAndLabel(planId, label)?.let(views::of) ?: throw VariationNotFoundException(label)

    /**
     * Replaces [label] with a new variation holding [request]'s configuration (plan Rules 2, 11 and 12), the request as the API took
     * it, as a run start's is (`RunLaunchService`): what it means is decided here. The checks run in the order the
     * API documents, the ones that need no lock first: the request id, a repeat of it (answered with the variation it created, even
     * when [label] has been archived since), the label, the configuration, and then under the plan's lock the baseline, an archived
     * label, an unchanged configuration and a duplicate of another active one.
     */
    fun edit(label: String, request: AutomationVariationEditRequest): VariationEditResult {
        val requestId = VariationEditRules.requireRequestId(request.requestId.orEmpty())
        edits.existing(planId, requestId)?.let { return VariationEditResult(it, false) }
        if (variations.findOneByPlanIdAndLabel(planId, label) == null) throw VariationNotFoundException(label)
        val config = when (val parsed = validator.validateVariation(request.config, properties.captureMinSignalSol)) {
            is ConfigValidation.Valid -> parsed.config
            is ConfigValidation.Invalid -> throw InvalidVariationConfigException(parsed.fields, parsed.code)
        }
        val result = try {
            edits.edit(planId, label, requestId, config, now())
        } catch (e: DataIntegrityViolationException) {
            edits.existing(planId, requestId)?.let { return VariationEditResult(it, false) }
            throw OptimisticLockingFailureException("The plan's variations changed while the edit ran planId=$planId label=$label", e)
        }
        if (result.created) log.info("Sweep variation edited. planId={} label={} createdLabel={}", planId, label, result.variation.variation.label)
        return result
    }

    /** The active variations as the plan a sweep replays; empty when none is active, and then a sweep has nothing to run. */
    @Transactional(readOnly = true)
    fun activePlan(): SweepPlan = mapper.toPlan(planId, variations.findAllByPlanIdAndStateOrderBySeqAsc(planId, VariationState.ACTIVE, LIMIT))

    /** The clock's instant at the precision the database keeps, so what an edit answers is what a later read of it returns. */
    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun parse(state: String): VariationState =
        VariationState.entries.firstOrNull { it.name == state }
            ?: throw InvalidAutomationRequestException(STATE, "must be one of ${VariationState.entries.map { it.name }}")

    private companion object {
        val LIMIT: Limit = Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN)
        const val STATE = "state"
    }
}
