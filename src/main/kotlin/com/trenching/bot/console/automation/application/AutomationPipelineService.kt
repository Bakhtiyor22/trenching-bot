package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationFirstBuy
import com.trenching.bot.console.automation.domain.AutomationHoldContext
import com.trenching.bot.console.automation.domain.AutomationHoldKind
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemTrace
import com.trenching.bot.console.automation.domain.AutomationOperationOutcome
import com.trenching.bot.console.automation.domain.AutomationOperationRef
import com.trenching.bot.console.automation.domain.AutomationRequests
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.AutomationStepOutcome
import com.trenching.bot.console.automation.domain.captured
import com.trenching.bot.console.automation.domain.outOfBudget
import com.trenching.bot.console.automation.domain.skipped
import com.trenching.bot.console.automation.domain.started
import com.trenching.bot.console.automation.domain.waitingUntil
import com.trenching.bot.console.automation.mapper.AutomationCaptureRequestMapper
import com.trenching.bot.console.capture.CaptureCatalogService
import com.trenching.bot.console.capture.CaptureDefaults
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureOperationRecord
import com.trenching.bot.console.capture.CaptureOperationService
import com.trenching.bot.console.capture.CaptureRequestException
import com.trenching.bot.console.capture.CaptureSessionService
import com.trenching.bot.console.capture.CaptureSessionView
import com.trenching.bot.marketdata.MarketCutoffMode
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Drives each due item through the wallet + token capture to a registered dataset (plan Rules 13–19), on every tick while
 * `capture-enabled` is on. It calls the capture console's own services, as a console user would, under request ids derived
 * from the item, step and attempt, so a tick after a restart gets the same session or operation back. An item does not create
 * its capture session while its wallet's discovery has a catch-up in progress, which may still find an earlier buy of its mint
 * to take its place (plan Rule 7, amended 2026-11-03); it waits for a later tick.
 *
 * A tick first follows the operations its items started; one that ended moves its item on, and the item may take its next
 * step in the same tick. Then, oldest buy first, items take their next step. At most one capture operation is started per
 * tick, and only while the capture worker is idle and no operation automation started is still running or waiting for its
 * own retry. Items take their steps in the order the database returns them ([AutomationPipelineTransaction.due]): live items of
 * sessions still open or closing, then the phase 0 backlog of closed sessions, then a backfill's, each oldest buy first (plan Rule 40). The market step waits until the buy's
 * block time plus `market-delay`; wallet and market operations start only within the rolling request cap (a backfill's item also
 * leaves `backfill-reserve-requests` of it free) and, for a backfill's item, its backfill's own request budget: an item the budget cannot cover ends `FAILED` with `BACKFILL_BUDGET` (a retried item is exempt). Each item is handled on its own: an unexpected failure is logged and the tick goes on.
 *
 * A market operation lists under `market-max-transactions`: a window holding more trades ends it `OVER_CAP` before any is fetched,
 * and the item fails at once, since another attempt lists the same trades.
 *
 * The capture console's refusals: `CAPTURE_BUSY` and `NOT_CONFIGURED` leave the item waiting with no attempt charged;
 * `CAPTURE_NOT_FOUND`, a capture session or operation deleted from the console, fails the item
 * at once; any other refusal is a failed attempt. An operation started for an item that changed meanwhile, for example one a
 * manual close abandoned, is cancelled, so it never spends requests the cap did not admit.
 *
 * Items held by the request cap, a backfill's reserve or a console that is not configured are not logged one by one: a tick counts
 * them, and [AutomationHolds] says once when a hold starts and when it ends.
 */
@Service
@ConditionalOnAutomationEnabled
class AutomationPipelineService(
    private val transaction: AutomationPipelineTransaction,
    private val discovery: AutomationDiscoveryTransaction,
    private val captureSessions: CaptureSessionService,
    private val operations: CaptureOperationService,
    private val datasets: CaptureCatalogService,
    private val cancellation: AutomationCaptureCancellation,
    private val requests: AutomationCaptureRequestMapper,
    private val usage: ApiUsageReports,
    private val properties: AutomationProperties,
    private val holds: AutomationHolds,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationPipelineService::class.java)

    fun advance() {
        if (!properties.captureEnabled) return
        // At the database's precision: an item moved on in this tick takes its next step against the progress it stored, which
        // a system clock finer than PostgreSQL's microseconds would no longer match, and the step's operation would be cancelled.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val due = transaction.due(now)
        val priority = due.withIndex().associate { (index, item) -> item.id to index }
        val (following, waiting) = due.partition { it.progress.operationId != null }
        val followed = following.mapNotNull { item -> isolated(item, now) { item to operation(item) } }
        var workerFree = followed.none { (_, operation) -> AutomationOperationOutcome.of(operation.state) == AutomationOperationOutcome.RUNNING }
        val moved = followed.mapNotNull { (item, operation) -> isolated(item, now) { settle(item, operation, now) } }
        val tally = AutomationHoldTally().also { it.blockedDue(due) }
        val budgetStops = AutomationBudgetStops()
        val catchingUp = discovery.catchingUp()
        (moved.filter { it.progress.dueAt(now) } + waiting).sortedBy { priority.getValue(it.id) }.forEach { item ->
            when (val outcome = isolated(item, now) { proceed(item, workerFree, item.wallet in catchingUp, now, tally, budgetStops) }) {
                AutomationStepOutcome.OperationStarted -> workerFree = false
                is AutomationStepOutcome.Held -> tally.held(outcome)
                AutomationStepOutcome.NoOperation, null -> Unit
            }
        }
        tally.reportTo(holds, PIPELINE_HOLDS)
        budgetStops.firstStops().forEach { (backfillId, items) -> logBudgetSpent(backfillId, items) }
    }

    /** Moves an item on from its operation's state; returns the item as stored when it moved, so it may take its next step. */
    private fun settle(item: AutomationDueItem, operation: CaptureOperationRecord, now: Instant): AutomationDueItem? =
        when (AutomationOperationOutcome.of(operation.state)) {
            AutomationOperationOutcome.RUNNING, AutomationOperationOutcome.PAUSED -> null
            AutomationOperationOutcome.SUCCEEDED -> succeeded(item, operation.state, now)
            AutomationOperationOutcome.FAILED -> failed(item, operation.code ?: operation.state, operation.message, requestsUsed(captureSession(item)), now, operation.state)
        }

    private fun proceed(
        item: AutomationDueItem,
        workerFree: Boolean,
        walletCatchingUp: Boolean,
        now: Instant,
        tally: AutomationHoldTally,
        budgetStops: AutomationBudgetStops,
    ): AutomationStepOutcome =
        when (val step = item.progress.currentStep) {
            AutomationStep.CREATE_SESSION -> if (walletCatchingUp) AutomationStepOutcome.NoOperation else createSession(item, now)
            AutomationStep.WALLET, AutomationStep.MARKET -> acquire(item, step, workerFree, now, tally, budgetStops)
            AutomationStep.RATES -> rates(item, workerFree, now, tally)
            AutomationStep.EXPORT -> if (canStart(workerFree)) start(item, step, null, now) else AutomationStepOutcome.NoOperation
            AutomationStep.QUALIFY -> qualify(item, workerFree, now)
            AutomationStep.REGISTER -> register(item, now)
            AutomationStep.SWEEP -> throw IllegalStateException("The sweep is not a capture step itemId=${item.id}")
        }

    private fun createSession(item: AutomationDueItem, now: Instant): AutomationStepOutcome {
        val wallet = requests.walletToken(item.wallet, item.mint, CaptureDefaults.walletToken)
        val session = try {
            captureSessions.create(requests.session(requestId(item), wallet, MarketCutoffMode.FULL.name))
        } catch (e: CaptureRequestException) {
            return refused(item, e, now)
        }
        change(item, item.progress.sessionCreated(session.id, now), null, now)
        return AutomationStepOutcome.NoOperation
    }

    /** Wallet and market acquisition spend provider requests: the market waits for its delay, and both for room under the cap. */
    private fun acquire(
        item: AutomationDueItem,
        step: AutomationStep,
        workerFree: Boolean,
        now: Instant,
        tally: AutomationHoldTally,
        budgetStops: AutomationBudgetStops,
    ): AutomationStepOutcome {
        val startsAt = startsAt(item, step, now)
        if (startsAt.isAfter(now)) {
            change(item, item.progress.waitingUntil(startsAt), null, now)
            return AutomationStepOutcome.NoOperation
        }
        if (!canStart(workerFree)) return AutomationStepOutcome.NoOperation
        if (!withinBackfillBudget(item)) {
            stopForBudget(item, requireNotNull(item.backfillId) { "Only a backfill's item has a budget itemId=${item.id}" }, now, budgetStops)
            return AutomationStepOutcome.NoOperation
        }
        val requestsLastDay = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests
        val cap = properties.requestCap()
        val backfill = item.backfillId != null
        val context = AutomationHoldContext(requestsLastDay, properties.maxRequestsPer24h, properties.backfillReserveRequests.takeIf { backfill })
        val refusedBy = when {
            !cap.admits(requestsLastDay) -> AutomationHoldKind.REQUEST_CAP
            backfill && !cap.admitsBackfill(requestsLastDay, properties.backfillReserveRequests) -> AutomationHoldKind.BACKFILL_RESERVE
            else -> null
        }
        if (refusedBy != null) {
            change(item, item.progress.blocked(), null, now)
            return AutomationStepOutcome.Held(refusedBy, context)
        }
        tally.admitted(AutomationHoldKind.REQUEST_CAP, context)
        if (backfill) tally.admitted(AutomationHoldKind.BACKFILL_RESERVE, context)
        return startedAdmitted(item, step, properties.captureRecovery(), now, tally)
    }

    /** Ends the item `FAILED` with `BACKFILL_BUDGET`; the backfill says once that its budget is spent, when its first item is stopped. */
    private fun stopForBudget(item: AutomationDueItem, backfillId: String, now: Instant, budgetStops: AutomationBudgetStops) {
        val firstOfBackfill = !transaction.stoppedByBudget(backfillId)
        val stopped = change(item, item.progress.outOfBudget("The backfill's request budget cannot cover another operation"), capturedRequests(item), now)
        if (stopped != null) budgetStops.stopped(backfillId, firstOfBackfill)
    }

    private fun logBudgetSpent(backfillId: String, itemsStopped: Int) {
        val budget = transaction.budget(backfillId)
        LogLine("Backfill stopped capturing: its request budget is spent.").field("backfillId", backfillId).field("requestBudget", budget.requestBudget)
            .field("requestsSpent", budget.spent).field("itemsStopped", itemsStopped).warn(log)
    }

    /** The rates step spends no provider requests, so only the console's configuration can hold it. */
    private fun rates(item: AutomationDueItem, workerFree: Boolean, now: Instant, tally: AutomationHoldTally): AutomationStepOutcome =
        if (canStart(workerFree)) startedAdmitted(item, AutomationStep.RATES, null, now, tally) else AutomationStepOutcome.NoOperation

    /** Starts the step; one the console accepted, or refused for another reason than its configuration, shows that the step's configuration is in place. */
    private fun startedAdmitted(item: AutomationDueItem, step: AutomationStep, recovery: CaptureRecoveryConfig?, now: Instant, tally: AutomationHoldTally): AutomationStepOutcome {
        val started = start(item, step, recovery, now)
        if (started !is AutomationStepOutcome.Held) AutomationHoldKind.notConfiguredFor(step)?.let(tally::admitted)
        return started
    }

    /**
     * Whether the item's backfill, if it has one, can pay for the operation: its spend plus one operation's recovery allowance
     * must stay within its budget (plan Rule 39). A live item has no budget of its own, and a retried item is not held to its
     * backfill's: the person chose that spend (plan 20261107, Rule 16), though it still counts in the backfill's.
     */
    private fun withinBackfillBudget(item: AutomationDueItem): Boolean {
        val backfillId = item.backfillId ?: return true
        if (item.progress.retries > 0) return true
        val budget = transaction.budget(backfillId)
        return budget.admits(properties.requestCap().operationRequests)
    }

    /**
     * The export queues its own qualification; the first attempt follows that one, a later attempt starts its own. After a retry the
     * session's latest qualification may be the one that failed before it; it is not followed, the retry starts its own.
     */
    private fun qualify(item: AutomationDueItem, workerFree: Boolean, now: Instant): AutomationStepOutcome {
        val queued = captureSessions.find(captureSession(item)).operation
            ?.takeIf { item.progress.onFirstAttempt && it.step == CaptureNames.QUALIFY && !(item.progress.retries > 0 && endedFailed(it)) }
        if (queued != null) {
            change(item, item.progress.started(queued.id), null, now)
            return AutomationStepOutcome.NoOperation
        }
        return if (canStart(workerFree)) start(item, AutomationStep.QUALIFY, null, now) else AutomationStepOutcome.NoOperation
    }

    private fun endedFailed(operation: CaptureOperationRecord): Boolean =
        AutomationOperationOutcome.of(operation.state) == AutomationOperationOutcome.FAILED

    private fun register(item: AutomationDueItem, now: Instant): AutomationStepOutcome {
        val sessionId = captureSession(item)
        val datasetId = captureSessions.find(sessionId).datasetId ?: try {
            datasets.register(sessionId).dataset.id
        } catch (e: CaptureRequestException) {
            return refused(item, e, now)
        }
        change(item, item.progress.captured(datasetId), requestsUsed(sessionId), now)
        return AutomationStepOutcome.NoOperation
    }

    /**
     * Starts the step's operation and follows it. When the item changed while the operation was being started (a manual close
     * abandoned it), nothing follows the operation, so it is cancelled at once.
     */
    private fun start(item: AutomationDueItem, step: AutomationStep, recovery: CaptureRecoveryConfig?, now: Instant): AutomationStepOutcome {
        val sessionId = captureSession(item)
        val request = requests.operation(requestId(item), captureStep(step), attemptId(captureSessions.find(sessionId), step),
            properties.captureBudget(), recovery, properties.marketMaxTransactions.takeIf { step == AutomationStep.MARKET })
        val operation = try {
            operations.start(sessionId, request)
        } catch (e: CaptureRequestException) {
            return refused(item, e, now)
        }
        if (change(item, item.progress.started(operation.id), null, now) == null) {
            cancellation.cancel(AutomationOperationRef(item.id, sessionId, operation.id, step, item.progress.currentAttempt, item.progress.retries))
        }
        return AutomationStepOutcome.OperationStarted
    }

    private fun succeeded(item: AutomationDueItem, operationState: String, now: Instant): AutomationDueItem? {
        val sessionId = captureSession(item)
        val classification = captureSessions.find(sessionId).qualification?.classification
        val next = if (AutomationFirstBuy.unproven(classification)) {
            item.progress.skipped(classification)
        } else {
            item.progress.succeeded(startsAt(item, item.progress.currentStep.next(), now), now, classification)
        }
        return change(item, next, requestsUsed(sessionId), now, AutomationItemTrace(endedOperationState = operationState))
    }

    /**
     * A busy capture worker or a capture console that is not configured only delays the step; a capture deleted from the
     * console fails the item at once; any other refusal counts as a failed attempt.
     */
    private fun refused(item: AutomationDueItem, refusal: CaptureRequestException, now: Instant): AutomationStepOutcome {
        when (refusal.code) {
            CAPTURE_BUSY -> Unit
            NOT_CONFIGURED -> {
                val hold = AutomationHoldKind.notConfiguredFor(item.progress.currentStep)
                if (hold != null) return AutomationStepOutcome.Held(hold, AutomationHoldContext(reason = refusal.message))
                failed(item, refusal.code, refusal.message, capturedRequests(item), now)
            }
            AutomationFailures.CAPTURE_NOT_FOUND -> change(item, item.progress.lost(refusal.code, refusal.message), capturedRequests(item), now)
            else -> failed(item, refusal.code, refusal.message, capturedRequests(item), now)
        }
        return AutomationStepOutcome.NoOperation
    }

    /** A failed attempt of the step; [operationState] is the state the item's operation ended in, null when the capture call was refused and none ran. */
    private fun failed(item: AutomationDueItem, code: String, message: String?, requestsUsed: Int?, now: Instant, operationState: String? = null): AutomationDueItem? =
        change(item, item.progress.failed(code, message, properties.maxStepAttempts, now), requestsUsed, now,
            AutomationItemTrace(endedOperationState = operationState, attemptFailed = true))

    /** Stores [next] unless nothing changes; returns the item as stored, or null when nothing was stored. */
    private fun change(item: AutomationDueItem, next: AutomationItemProgress, requestsUsed: Int?, now: Instant, trace: AutomationItemTrace = AutomationItemTrace.NONE): AutomationDueItem? {
        if (next == item.progress && requestsUsed == null) return null
        return if (transaction.record(item.id, item.progress, next, requestsUsed, now, trace)) item.copy(progress = next) else null
    }

    private fun canStart(workerFree: Boolean): Boolean = workerFree && operations.idle()

    /** The market step starts no earlier than the buy's block time plus `market-delay`; every other step starts at once. */
    private fun startsAt(item: AutomationDueItem, step: AutomationStep, now: Instant): Instant {
        if (step != AutomationStep.MARKET) return now
        val blockTime = requireNotNull(item.blockTime) { "An item in capture has a block time itemId=${item.id}" }
        return maxOf(now, blockTime.plus(properties.marketDelay))
    }

    private fun operation(item: AutomationDueItem): CaptureOperationRecord =
        operations.find(captureSession(item), requireNotNull(item.progress.operationId) { "Item follows no operation itemId=${item.id}" })

    private fun requestsUsed(captureSessionId: String): Int =
        Math.toIntExact(usage.report(ApiUsagePeriod.ALL, captureSessionId).totals.requests)

    private fun capturedRequests(item: AutomationDueItem): Int? = item.progress.captureSessionId?.let(::requestsUsed)

    private fun captureSession(item: AutomationDueItem): String =
        requireNotNull(item.progress.captureSessionId) { "Item has no capture session itemId=${item.id}" }

    private fun requestId(item: AutomationDueItem): String =
        AutomationRequests.capture(item.progress.currentStep, item.id, item.progress.currentAttempt, item.progress.retries)

    private fun captureStep(step: AutomationStep): String = when (step) {
        AutomationStep.WALLET -> CaptureNames.WALLET
        AutomationStep.MARKET -> CaptureNames.MARKET
        AutomationStep.RATES -> CaptureNames.RATES
        AutomationStep.EXPORT -> CaptureNames.EXPORT
        AutomationStep.QUALIFY -> CaptureNames.QUALIFY
        AutomationStep.CREATE_SESSION, AutomationStep.REGISTER, AutomationStep.SWEEP -> throw IllegalStateException("No capture operation for step=$step")
    }

    /** The attempt each operation works on, as the capture console selects it. */
    private fun attemptId(session: CaptureSessionView, step: AutomationStep): String? = when (step) {
        AutomationStep.WALLET -> session.walletAttemptId
        AutomationStep.RATES -> session.ratesAttempt?.id
        AutomationStep.MARKET, AutomationStep.EXPORT, AutomationStep.QUALIFY -> session.marketAttempt?.id
        AutomationStep.CREATE_SESSION, AutomationStep.REGISTER, AutomationStep.SWEEP -> throw IllegalStateException("No capture attempt for step=$step")
    }

    /**
     * Runs one item's [step] on its own. A capture console refusal raised outside a step's own handling, such as a capture
     * session or operation that was deleted, is handled as [refused]; any other failure is logged and the tick goes on.
     */
    private fun <T> isolated(item: AutomationDueItem, now: Instant, step: () -> T): T? = try {
        step()
    } catch (e: CaptureRequestException) {
        isolated(item, now) { refused(item, e, now) }
        null
    } catch (e: Exception) {
        LogLine("Unexpected error while moving a buy on; the tick goes on with the other buys.").field("step", item.progress.currentStep.name.lowercase())
            .field("itemId", item.id).field("mint", item.mint).field("wallet", item.wallet).error(log, e)
        null
    }

    private companion object {
        const val CAPTURE_BUSY = "CAPTURE_BUSY"
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
        val PIPELINE_HOLDS = listOf(
            AutomationHoldKind.REQUEST_CAP, AutomationHoldKind.BACKFILL_RESERVE, AutomationHoldKind.RPC_NOT_CONFIGURED, AutomationHoldKind.RATES_NOT_CONFIGURED,
        )
    }
}
