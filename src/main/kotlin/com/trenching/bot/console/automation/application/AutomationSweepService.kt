package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.SweepEntry
import com.trenching.bot.console.automation.domain.SweepEntryResult
import com.trenching.bot.console.automation.domain.SweepOutcomes
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.simulated
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.DatasetInvalidException
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.MarketCoverageEndedException
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.TradesRequiredException
import com.trenching.bot.replay.domain.VerdictReasons
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Replays the active sweep plan on captured items (plan Rules 21–23), on every tick while `capture-enabled` is on. At most
 * [AutomationLimits.SWEEP_ITEMS_PER_TICK] items whose sweep is due are swept per tick, oldest buy first. Each entry the item has
 * no row for yet runs as a dataset-referenced run, in entry order, and gets one row; runs are idempotent, so a repeat returns the
 * same run ids. An entry the replay refuses, including for a dataset that is gone or unreadable, is stored with its refusal code
 * and no run. An unexpected failure stores nothing for that entry and is logged; the item's sweep then counts as a failed attempt
 * (step `SWEEP`): it is swept again after a growing delay, and fails with `SWEEP_FAILED` once `max-step-attempts` are spent, so
 * one broken item never holds the tick's slots. Once every entry has its row, the item is `SIMULATED`. No transaction is held
 * across a run.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationSweepService(
    private val transaction: AutomationSweepTransaction,
    private val pipeline: AutomationPipelineTransaction,
    private val runs: SimulationRuns,
    private val cycles: AutomationRunCycles,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationSweepService::class.java)

    fun sweep() {
        if (!properties.captureEnabled) return
        val plan = properties.activePlan()
        transaction.due(clock.instant(), AutomationLimits.SWEEP_ITEMS_PER_TICK).forEach { item ->
            try {
                sweepItem(item, plan)
            } catch (e: Exception) {
                log.error("automation sweep failed itemId={}", item.id, e)
            }
        }
    }

    private fun sweepItem(item: AutomationDueItem, plan: SweepPlan) {
        val swept = transaction.sweptLabels(item.id, plan.id)
        val failure = plan.entries.filterNot { it.label in swept }.mapNotNull { entry -> unexpectedFailure(item, plan, entry) }.firstOrNull()
        val now = clock.instant()
        val next = failure?.let { item.progress.sweepFailed(AutomationFailures.SWEEP_FAILED, it.message, properties.maxStepAttempts, now) }
            ?: item.progress.simulated()
        if (pipeline.record(item.id, item.progress, next, null, now) && failure != null) {
            log.warn("automation sweep attempt failed itemId={} attempt={} state={} nextActionAt={}",
                item.id, item.progress.sweepAttempt, next.state, next.nextActionAt)
        }
    }

    /** Runs one entry and stores its row; returns the unexpected failure that left the entry without a row, or null. */
    private fun unexpectedFailure(item: AutomationDueItem, plan: SweepPlan, entry: SweepEntry): Exception? = try {
        transaction.record(item, plan.id, entry.label, replay(item, entry), clock.instant())
        null
    } catch (e: DataIntegrityViolationException) {
        if (entry.label in transaction.sweptLabels(item.id, plan.id)) null else logged(item, entry, e)
    } catch (e: Exception) {
        logged(item, entry, e)
    }

    private fun logged(item: AutomationDueItem, entry: SweepEntry, failure: Exception): Exception {
        log.error("automation sweep entry failed itemId={} label={}", item.id, entry.label, failure)
        return failure
    }

    private fun replay(item: AutomationDueItem, entry: SweepEntry): SweepEntryResult {
        val datasetId = requireNotNull(item.progress.datasetId) { "Captured item without a dataset itemId=${item.id}" }
        val run = try {
            runs.start(datasetId, entry.config, RunStorage.DATASET_REFERENCE).run
        } catch (e: MarketCoverageEndedException) {
            return refused(item, entry, VerdictReasons.MARKET_COVERAGE_ENDED)
        } catch (e: TradesRequiredException) {
            return refused(item, entry, e.code)
        } catch (e: DatasetNotFoundException) {
            return refused(item, entry, SweepOutcomes.DATASET_NOT_FOUND)
        } catch (e: DatasetInvalidException) {
            return refused(item, entry, SweepOutcomes.DATASET_INVALID)
        }
        return SweepEntryResult.ran(run, cycles.cycle(run.id, item.signature))
    }

    private fun refused(item: AutomationDueItem, entry: SweepEntry, code: String): SweepEntryResult {
        log.warn("automation sweep entry refused itemId={} label={} code={}", item.id, entry.label, code)
        return SweepEntryResult.refused(code)
    }
}
