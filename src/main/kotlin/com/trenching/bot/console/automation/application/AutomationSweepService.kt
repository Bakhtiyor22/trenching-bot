package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemTrace
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
 * Replays the active variations on captured items (plan Rules 21–23), on every tick while `capture-enabled` is on. Each item
 * reads the active variations when its sweep starts and again once its entries have run (editable-variations plan Rules 4–6):
 * a variation an edit created meanwhile runs on it before it is `SIMULATED`, so a buy swept during an edit is not left without
 * the replacement's run. A tick that finds no active variation sweeps nothing and changes no item. At most
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
    private val variations: AutomationVariationService,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationSweepService::class.java)

    fun sweep() {
        if (!properties.captureEnabled) return
        if (variations.activePlan().entries.isEmpty()) return
        transaction.due(clock.instant(), AutomationLimits.SWEEP_ITEMS_PER_TICK).forEach { item ->
            try {
                sweepItem(item)
            } catch (e: Exception) {
                LogLine("Unexpected error while sweeping a buy; the tick goes on with the other buys.").field("itemId", item.id).field("mint", item.mint)
                    .field("wallet", item.wallet).error(log, e)
            }
        }
    }

    /**
     * Runs each active variation the item has no row for, then reads the active variations again, at most [PLAN_READS] times, so
     * one an edit created while the item ran is not missed; the item is `SIMULATED` once a read finds nothing missing (or the
     * reads are spent). An unexpected failure ends the item's attempt at once. With no active variation the item is left as it is.
     */
    private fun sweepItem(item: AutomationDueItem) {
        var failure: Exception? = null
        var reads = 0
        while (reads++ < PLAN_READS) {
            val plan = variations.activePlan()
            if (plan.entries.isEmpty()) return
            val swept = transaction.sweptLabels(item.id, plan.id)
            val missing = plan.entries.filterNot { it.label in swept }
            if (missing.isEmpty()) break
            failure = missing.mapNotNull { entry -> unexpectedFailure(item, plan, entry) }.firstOrNull()
            if (failure != null) break
        }
        val now = clock.instant()
        val next = failure?.let { item.progress.sweepFailed(AutomationFailures.SWEEP_FAILED, it.message, properties.maxStepAttempts, now) }
            ?: item.progress.simulated()
        pipeline.record(item.id, item.progress, next, null, now, AutomationItemTrace(attemptFailed = failure != null))
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
        LogLine("Unexpected error while running a sweep entry; it has no row yet and the buy's sweep counts as a failed attempt.").field("label", entry.label)
            .field("itemId", item.id).field("mint", item.mint).field("wallet", item.wallet).error(log, failure)
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
        LogLine("Sweep entry refused by the replay; its row records the refusal and no run.").field("code", code).field("label", entry.label)
            .field("itemId", item.id).field("mint", item.mint).field("wallet", item.wallet).debug(log)
        return SweepEntryResult.refused(code)
    }

    private companion object {
        /** Reads of the active variations per item: one before its entries run and up to two after, for edits that land meanwhile. */
        const val PLAN_READS = 3
    }
}
