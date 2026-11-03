package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationFirstBuy
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemState
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
 * leaves `backfill-reserve-requests` of it free) and, for a backfill's item, its backfill's own request budget: an item the budget cannot cover ends `FAILED` with `BACKFILL_BUDGET`. Each item is handled on its own: an unexpected failure is logged and the tick goes on.
 *
 * The capture console's refusals: `CAPTURE_BUSY` and `NOT_CONFIGURED` leave the item waiting with no attempt charged (the
 * latter logged once per tick); `CAPTURE_NOT_FOUND`, a capture session or operation deleted from the console, fails the item
 * at once; any other refusal is a failed attempt. An operation started for an item that changed meanwhile, for example one a
 * manual close abandoned, is cancelled, so it never spends requests the cap did not admit.
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
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationPipelineService::class.java)

    fun advance() {
        if (!properties.captureEnabled) return
        val now = clock.instant()
        val due = transaction.due(now)
        val priority = due.withIndex().associate { (index, item) -> item.id to index }
        val (following, waiting) = due.partition { it.progress.operationId != null }
        val followed = following.mapNotNull { item -> isolated(item, now) { item to operation(item) } }
        var workerFree = followed.none { (_, operation) -> AutomationOperationOutcome.of(operation.state) == AutomationOperationOutcome.RUNNING }
        val moved = followed.mapNotNull { (item, operation) -> isolated(item, now) { settle(item, operation, now) } }
        val unconfigured = mutableListOf<AutomationStepOutcome.NotConfigured>()
        val catchingUp = discovery.catchingUp()
        (moved.filter { it.progress.dueAt(now) } + waiting).sortedBy { priority.getValue(it.id) }.forEach { item ->
            when (val outcome = isolated(item, now) { proceed(item, workerFree, item.wallet in catchingUp, now) }) {
                AutomationStepOutcome.OperationStarted -> workerFree = false
                is AutomationStepOutcome.NotConfigured -> unconfigured += outcome
                AutomationStepOutcome.NoOperation, null -> Unit
            }
        }
        unconfigured.firstOrNull()?.let {
            log.warn("automation capture not configured, items wait items={} step={} reason={}", unconfigured.size, it.step, it.reason)
        }
    }

    /** Moves an item on from its operation's state; returns the item as stored when it moved, so it may take its next step. */
    private fun settle(item: AutomationDueItem, operation: CaptureOperationRecord, now: Instant): AutomationDueItem? =
        when (AutomationOperationOutcome.of(operation.state)) {
            AutomationOperationOutcome.RUNNING, AutomationOperationOutcome.PAUSED -> null
            AutomationOperationOutcome.SUCCEEDED -> succeeded(item, now)
            AutomationOperationOutcome.FAILED -> failed(item, operation.code ?: operation.state, operation.message, requestsUsed(captureSession(item)), now)
        }

    private fun proceed(item: AutomationDueItem, workerFree: Boolean, walletCatchingUp: Boolean, now: Instant): AutomationStepOutcome =
        when (val step = item.progress.currentStep) {
            AutomationStep.CREATE_SESSION -> if (walletCatchingUp) AutomationStepOutcome.NoOperation else createSession(item, now)
            AutomationStep.WALLET, AutomationStep.MARKET -> acquire(item, step, workerFree, now)
            AutomationStep.RATES, AutomationStep.EXPORT -> if (canStart(workerFree)) start(item, step, null, now) else AutomationStepOutcome.NoOperation
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
    private fun acquire(item: AutomationDueItem, step: AutomationStep, workerFree: Boolean, now: Instant): AutomationStepOutcome {
        val startsAt = startsAt(item, step, now)
        if (startsAt.isAfter(now)) {
            change(item, item.progress.waitingUntil(startsAt), null, now)
            return AutomationStepOutcome.NoOperation
        }
        if (!canStart(workerFree)) return AutomationStepOutcome.NoOperation
        if (!withinBackfillBudget(item, step)) {
            change(item, item.progress.outOfBudget("The backfill's request budget cannot cover another operation"), capturedRequests(item), now)
            return AutomationStepOutcome.NoOperation
        }
        val requestsLastDay = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests
        if (!withinRequestCap(item, requestsLastDay)) {
            if (item.progress.state != AutomationItemState.BLOCKED_BUDGET) {
                log.warn("automation request cap reached itemId={} step={} backfillId={} requestsLast24h={} maxRequestsPer24h={} reserveRequests={}",
                    item.id, step, item.backfillId, requestsLastDay, properties.maxRequestsPer24h, properties.backfillReserveRequests)
            }
            change(item, item.progress.blocked(), null, now)
            return AutomationStepOutcome.NoOperation
        }
        return start(item, step, properties.captureRecovery(), now)
    }

    /** A live step needs room under the cap; a backfill's step also leaves `backfill-reserve-requests` of it free for live capture (plan Rule 19, amended). */
    private fun withinRequestCap(item: AutomationDueItem, requestsLastDay: Long): Boolean {
        val cap = properties.requestCap()
        return if (item.backfillId == null) cap.admits(requestsLastDay) else cap.admitsBackfill(requestsLastDay, properties.backfillReserveRequests)
    }

    /**
     * Whether the item's backfill, if it has one, can pay for the operation: its spend plus one operation's recovery allowance
     * must stay within its budget (plan Rule 39). A live item has no budget of its own.
     */
    private fun withinBackfillBudget(item: AutomationDueItem, step: AutomationStep): Boolean {
        val backfillId = item.backfillId ?: return true
        val budget = transaction.budget(backfillId)
        if (budget.admits(properties.requestCap().operationRequests)) return true
        log.warn("automation backfill budget reached itemId={} step={} backfillId={} requestBudget={} requestsSpent={}",
            item.id, step, backfillId, budget.requestBudget, budget.spent)
        return false
    }

    /** The export queues its own qualification; the first attempt follows that one, a later attempt starts its own. */
    private fun qualify(item: AutomationDueItem, workerFree: Boolean, now: Instant): AutomationStepOutcome {
        val queued = captureSessions.find(captureSession(item)).operation
            ?.takeIf { item.progress.onFirstAttempt && it.step == CaptureNames.QUALIFY }
        if (queued != null) {
            change(item, item.progress.started(queued.id), null, now)
            return AutomationStepOutcome.NoOperation
        }
        return if (canStart(workerFree)) start(item, AutomationStep.QUALIFY, null, now) else AutomationStepOutcome.NoOperation
    }

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
            properties.captureBudget(), recovery)
        val operation = try {
            operations.start(sessionId, request)
        } catch (e: CaptureRequestException) {
            return refused(item, e, now)
        }
        if (change(item, item.progress.started(operation.id), null, now) == null) {
            cancellation.cancel(AutomationOperationRef(item.id, sessionId, operation.id, step, item.progress.currentAttempt))
        }
        return AutomationStepOutcome.OperationStarted
    }

    private fun succeeded(item: AutomationDueItem, now: Instant): AutomationDueItem? {
        val sessionId = captureSession(item)
        val classification = captureSessions.find(sessionId).qualification?.classification
        val next = if (AutomationFirstBuy.unproven(classification)) {
            item.progress.skipped(classification)
        } else {
            item.progress.succeeded(startsAt(item, item.progress.currentStep.next(), now), now, classification)
        }
        return change(item, next, requestsUsed(sessionId), now)
    }

    /**
     * A busy capture worker or a capture console that is not configured only delays the step; a capture deleted from the
     * console fails the item at once; any other refusal counts as a failed attempt.
     */
    private fun refused(item: AutomationDueItem, refusal: CaptureRequestException, now: Instant): AutomationStepOutcome {
        when (refusal.code) {
            CAPTURE_BUSY -> Unit
            NOT_CONFIGURED -> return AutomationStepOutcome.NotConfigured(item.progress.currentStep, refusal.message)
            AutomationFailures.CAPTURE_NOT_FOUND -> {
                log.warn("automation capture deleted from the console itemId={} step={} captureSessionId={}",
                    item.id, item.progress.currentStep, item.progress.captureSessionId)
                change(item, item.progress.lost(refusal.code, refusal.message), capturedRequests(item), now)
            }
            else -> {
                log.warn("automation capture call refused itemId={} step={} code={}", item.id, item.progress.currentStep, refusal.code)
                failed(item, refusal.code, refusal.message, capturedRequests(item), now)
            }
        }
        return AutomationStepOutcome.NoOperation
    }

    private fun failed(item: AutomationDueItem, code: String, message: String?, requestsUsed: Int?, now: Instant): AutomationDueItem? =
        change(item, item.progress.failed(code, message, properties.maxStepAttempts, now), requestsUsed, now)

    /** Stores [next] unless nothing changes; returns the item as stored, or null when nothing was stored. */
    private fun change(item: AutomationDueItem, next: AutomationItemProgress, requestsUsed: Int?, now: Instant): AutomationDueItem? {
        if (next == item.progress && requestsUsed == null) return null
        return if (transaction.record(item.id, item.progress, next, requestsUsed, now)) item.copy(progress = next) else null
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
        AutomationRequests.capture(item.progress.currentStep, item.id, item.progress.currentAttempt)

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
        log.error("automation item step failed itemId={}", item.id, e)
        null
    }

    private companion object {
        const val CAPTURE_BUSY = "CAPTURE_BUSY"
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
    }
}
