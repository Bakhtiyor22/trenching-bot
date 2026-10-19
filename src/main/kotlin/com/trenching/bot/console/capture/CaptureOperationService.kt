package com.trenching.bot.console.capture

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.*
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.marketdata.MarketCaptureReasons
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.replay.*
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.walletactivity.TokenAccountGap
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCompletion
import com.trenching.bot.walletactivity.WalletHistoryManifest
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class CaptureOperationService(private val store: CaptureFileStore, private val workflow: CaptureWorkflow, private val mapper: CaptureResponseMapper, private val json: ObjectMapper, private val clock: Clock, private val executor: ExecutorService, private val rpcConfigured: Boolean, private val ratesConfigured: Boolean, private val retryScheduler: CaptureRetryScheduler = CaptureRetryScheduler.NONE) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val inspectionMapper = CaptureInspectionMapper(mapper)
    private val qualificationMapper = CaptureQualificationMapper(mapper)
    /** The session whose operation the worker is running, or null when idle. */
    private var activeSession: String? = null
    private var closed = false
    fun idle(): Boolean = activeSession == null && !closed
    fun requireIdle() { if (!idle()) CaptureValidation.conflict("CAPTURE_BUSY", "Capture worker is busy or stopping") }
    /** Rejects a change to [sessionId] while its own operation runs; other sessions may change meanwhile. */
    fun requireNotRunning(sessionId: String) { if (activeSession == sessionId) CaptureValidation.conflict("CAPTURE_BUSY", "This capture has a running operation") }
    fun recover() {
        val retries = synchronized(store) {
            store.sessions().flatMap { id ->
                var session = store.session(id)
                val pending = mutableListOf<Pair<String, CaptureOperationRecord>>()
                store.operations(id).filter { it.caseId == null }.sortedWith(compareBy<CaptureOperationRecord> { it.startedAt }.thenBy { it.id }).forEach { original ->
                    val recovered = if (original.state in setOf(CaptureNames.RUNNING, CaptureNames.WAITING_RETRY)) settleUnknownReservation(original) else original
                    val latest = when {
                        recovered.state == CaptureNames.RUNNING && recovered.recovery != null && !matchesCurrentAttempts(session, recovered) -> recovered.copy(state = CaptureNames.BLOCKED, updatedAt = clock.instant(), endedAt = clock.instant(), code = "STALE_ATTEMPT", message = "Saved operation belongs to attempts that are no longer selected")
                        recovered.state == CaptureNames.RUNNING && recovered.control == CaptureNames.PAUSE_REQUESTED -> recovered.copy(state = CaptureNames.PAUSED, control = null, updatedAt = clock.instant(), endedAt = clock.instant(), code = "PAUSED_BY_USER", message = "Capture paused after restart")
                        recovered.state == CaptureNames.RUNNING && recovered.control == CaptureNames.CANCEL_REQUESTED -> recovered.copy(state = CaptureNames.CANCELLED, control = null, updatedAt = clock.instant(), endedAt = clock.instant(), code = "CANCELLED_BY_USER", message = "Remaining capture work was cancelled")
                        recovered.state in setOf(CaptureNames.RUNNING, CaptureNames.WAITING_RETRY) && recovered.recovery != null && recovered.recovery.attemptsUsed >= recovered.recovery.maxAttempts -> recovered.copy(state = CaptureNames.FAILED, updatedAt = clock.instant(), endedAt = clock.instant(), code = "RETRY_EXHAUSTED", message = "The capture reached its configured attempt limit", recovery = recovered.recovery.copy(nextRetryAt = null))
                        recovered.state in setOf(CaptureNames.RUNNING, CaptureNames.WAITING_RETRY) && recovered.recovery != null && budgetExhausted(recovered.recovery) -> recovered.copy(state = CaptureNames.FAILED, updatedAt = clock.instant(), endedAt = clock.instant(), code = "BUDGET_EXHAUSTED", message = "The capture reached its total request or byte budget", recovery = recovered.recovery.copy(nextRetryAt = null))
                        recovered.state == CaptureNames.RUNNING && recovered.recovery != null -> recovered.copy(state = CaptureNames.WAITING_RETRY, updatedAt = clock.instant(), endedAt = null, code = "PROCESS_RESTART", message = "Resuming the saved capture after restart", recovery = recovered.recovery.copy(nextRetryAt = recovered.recovery.nextRetryAt ?: clock.instant()))
                        recovered.state == CaptureNames.WAITING_RETRY && recovered.recovery != null -> recovered
                        original.state == CaptureNames.RUNNING -> original.copy(state = CaptureNames.INTERRUPTED, updatedAt = clock.instant(), endedAt = clock.instant(), code = "PROCESS_RESTART", message = "Operation interrupted; inspect or explicitly resume")
                        else -> original
                    }
                    if (latest != original) store.save(id, latest)
                    session = project(session, latest)
                    if (latest.state == CaptureNames.WAITING_RETRY && latest.recovery != null && latest.recovery.attemptsUsed < latest.recovery.maxAttempts && !budgetExhausted(latest.recovery)) pending += id to latest
                }
                store.save(session)
                pending
            }
        }
        retries.forEach { (id, operation) ->
            val at = operation.recovery?.nextRetryAt ?: clock.instant()
            scheduleRetry(id, operation, Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO))
        }
    }
    fun find(id: String, operationId: String): CaptureOperationRecord = synchronized(store) { store.session(id); store.operation(id,operationId) }
    fun control(id: String, operationId: String, action: String, requestId: String): CaptureOperationRecord = synchronized(store) {
        store.id(requestId)
        if (action !in setOf("pause", "resume", "cancel", "retry")) CaptureValidation.invalid("action", "unsupported operation control")
        store.session(id)
        val original = store.operation(id, operationId)
        if (original.caseId != null) CaptureValidation.conflict("CASE_CONTROL_UNSUPPORTED", "Case operations do not use main capture controls")
        val key = "$action:$requestId"
        if (original.controlRequestIds.any { it.substringAfter(':') == requestId && it != key }) CaptureValidation.conflict("REQUEST_CONFLICT", "Control request id is already bound to another action")
        if (key in original.controlRequestIds) return@synchronized original
        val updatedIds = original.controlRequestIds + key
        val now = clock.instant()
        val updated = when (action) {
            "pause" -> when (original.state) {
                CaptureNames.RUNNING -> original.copy(control = CaptureNames.PAUSE_REQUESTED, controlRequestIds = updatedIds, updatedAt = now)
                CaptureNames.WAITING_RETRY -> original.copy(state = CaptureNames.PAUSED, endedAt = now, controlRequestIds = updatedIds, recovery = original.recovery?.copy(nextRetryAt = null), updatedAt = now)
                CaptureNames.PAUSED, CaptureNames.CANCELLED -> original.copy(controlRequestIds = updatedIds)
                else -> CaptureValidation.conflict("OPERATION_TERMINAL", "Operation cannot be paused in its current state")
            }
            "cancel" -> when (original.state) {
                CaptureNames.RUNNING -> original.copy(control = CaptureNames.CANCEL_REQUESTED, controlRequestIds = updatedIds, updatedAt = now)
                CaptureNames.WAITING_RETRY, CaptureNames.PAUSED -> original.copy(state = CaptureNames.CANCELLED, code = "CANCELLED_BY_USER", message = "Remaining capture work was cancelled", endedAt = now, controlRequestIds = updatedIds, recovery = original.recovery?.copy(nextRetryAt = null), updatedAt = now)
                CaptureNames.CANCELLED -> original.copy(controlRequestIds = updatedIds)
                else -> CaptureValidation.conflict("OPERATION_TERMINAL", "Operation cannot be cancelled in its current state")
            }
            "resume", "retry" -> {
                val allowed = if (action == "resume") setOf(CaptureNames.PAUSED) else setOf(CaptureNames.WAITING_RETRY, CaptureNames.FAILED, CaptureNames.INCOMPLETE, CaptureNames.INTERRUPTED)
                if (original.state !in allowed) CaptureValidation.conflict("OPERATION_STATE", "Operation cannot be $action in its current state")
                if (action == "retry" && original.state == CaptureNames.WAITING_RETRY && original.recovery?.nextRetryAt?.isAfter(now) == true) {
                    CaptureValidation.conflict("RETRY_COOLDOWN", "The provider retry cooldown lasts until ${original.recovery.nextRetryAt}")
                }
                if (!matchesCurrentAttempts(store.session(id), original)) CaptureValidation.conflict("STALE_ATTEMPT", "Select the current capture attempt")
                requireIdle()
                val next = reserve(original.copy(state = CaptureNames.RUNNING, control = null, updatedAt = now, endedAt = null, controlRequestIds = updatedIds, code = null, message = null), firstAttempt = false)
                store.save(id, next)
                project(store.session(id), next).also(store::save)
                activeSession = id
                executor.execute { run(id, next) }
                return@synchronized next
            }
            else -> error("Exhaustive action check")
        }
        store.save(id, updated)
        project(store.session(id), updated).also(store::save)
        updated
    }
    private fun reserve(operation: CaptureOperationRecord, firstAttempt: Boolean): CaptureOperationRecord {
        val recovery = operation.recovery ?: return operation
        if (!firstAttempt && recovery.attemptsUsed >= recovery.maxAttempts) CaptureValidation.conflict("RETRY_EXHAUSTED", "The capture reached its configured attempt limit")
        val availableRequests = recovery.totalRequestBudget - recovery.requestsUsed - recovery.reservedRequests
        val availableBytes = recovery.totalByteBudget - recovery.bytesUsed - recovery.reservedBytes
        if (availableRequests < 1 || availableBytes < 1) CaptureValidation.conflict("BUDGET_EXHAUSTED", "The capture reached its total request or byte budget")
        val reservedRequests = minOf(operation.budget.requestBudget, availableRequests)
        val reservedBytes = minOf(operation.budget.byteBudget, availableBytes)
        return operation.copy(
            budget = operation.budget.copy(requestBudget = reservedRequests, byteBudget = reservedBytes),
            progress = null,
            recovery = recovery.copy(
                attemptsUsed = recovery.attemptsUsed + 1,
                reservedRequests = recovery.reservedRequests + reservedRequests,
                reservedBytes = recovery.reservedBytes + reservedBytes,
                nextRetryAt = null,
            ),
        )
    }

    private fun matchesCurrentAttempts(session: CaptureSessionRecord, operation: CaptureOperationRecord): Boolean =
        session.walletAttemptId == operation.walletAttemptId && session.marketAttemptId == operation.marketAttemptId && session.ratesAttemptId == operation.ratesAttemptId

    private fun scheduleRetry(id: String, operation: CaptureOperationRecord, delay: Duration) {
        retryScheduler.schedule(delay) {
            synchronized(store) {
                if (closed) return@schedule
                val current = store.operationOrNull(id, operation.id) ?: return@schedule
                if (current.state != CaptureNames.WAITING_RETRY || !matchesCurrentAttempts(store.session(id), current)) return@schedule
                if (!idle()) {
                    scheduleRetry(id, current, Duration.ofSeconds(1))
                    return@schedule
                }
                val next = try {
                    reserve(current.copy(state = CaptureNames.RUNNING, updatedAt = clock.instant(), endedAt = null), firstAttempt = false)
                } catch (exception: CaptureRequestException) {
                    val blocked = current.copy(state = CaptureNames.FAILED, updatedAt = clock.instant(), endedAt = clock.instant(), code = exception.code, message = exception.message, recovery = current.recovery?.copy(nextRetryAt = null))
                    store.save(id, blocked)
                    store.save(project(store.session(id), blocked))
                    return@schedule
                }
                store.save(id, next)
                store.save(project(store.session(id), next))
                activeSession = id
                executor.execute { run(id, next) }
            }
        }
    }

    private fun retryDelay(operation: CaptureOperationRecord, retryAfter: Duration?): Duration {
        val attempt = operation.recovery?.attemptsUsed ?: 1
        val baseSeconds = (1L shl (attempt - 1).coerceIn(0, 8)).coerceAtMost(MAX_RETRY_SECONDS)
        val jitterMillis = Math.floorMod(operation.id.hashCode().toLong() * attempt, RETRY_JITTER_MILLIS + 1)
        val backoff = Duration.ofSeconds(baseSeconds).plusMillis(jitterMillis)
        return maxOf(backoff, retryAfter ?: Duration.ZERO)
    }

    private fun retryable(operation: CaptureOperationRecord): Boolean = when {
        operation.recovery == null -> false
        operation.recovery.attemptsUsed >= operation.recovery.maxAttempts -> false
        operation.recovery.requestsUsed + operation.recovery.reservedRequests >= operation.recovery.totalRequestBudget -> false
        operation.recovery.bytesUsed + operation.recovery.reservedBytes >= operation.recovery.totalByteBudget -> false
        operation.control != null -> false
        operation.state !in setOf(CaptureNames.FAILED, CaptureNames.INCOMPLETE) -> false
        operation.code in RETRYABLE_PROVIDER_CATEGORIES -> true
        operation.code == MarketCaptureReasons.HORIZON_NOT_ELAPSED -> true
        operation.code?.removePrefix("BUDGET_EXHAUSTED_") in RETRYABLE_BUDGET_CATEGORIES -> true
        else -> false
    }

    private fun accountCheckpoint(operation: CaptureOperationRecord, progress: CaptureProgressResponse): CaptureOperationRecord =
        accountUsage(operation, progress.operationRequests ?: 0, progress.operationBytes ?: 0)

    private fun accountUsage(operation: CaptureOperationRecord, requests: Int, bytes: Long): CaptureOperationRecord {
        val recovery = operation.recovery ?: return operation
        val previousRequests = operation.progress?.operationRequests ?: 0
        val previousBytes = operation.progress?.operationBytes ?: 0L
        val requestDelta = (requests - previousRequests).coerceAtLeast(0)
        val byteDelta = (bytes - previousBytes).coerceAtLeast(0)
        return operation.copy(recovery = recovery.copy(
            requestsUsed = Math.addExact(recovery.requestsUsed, requestDelta),
            bytesUsed = Math.addExact(recovery.bytesUsed, byteDelta),
            reservedRequests = (recovery.reservedRequests - requestDelta).coerceAtLeast(0),
            reservedBytes = (recovery.reservedBytes - byteDelta).coerceAtLeast(0),
        ))
    }

    private fun releaseReservation(recovery: CaptureRecoveryRecord?): CaptureRecoveryRecord? =
        recovery?.copy(reservedRequests = 0, reservedBytes = 0, nextRetryAt = null)

    private fun settleUnknownReservation(operation: CaptureOperationRecord): CaptureOperationRecord {
        val recovery = operation.recovery ?: return operation
        return operation.copy(recovery = recovery.copy(
            requestsUsed = Math.addExact(recovery.requestsUsed, recovery.reservedRequests),
            bytesUsed = Math.addExact(recovery.bytesUsed, recovery.reservedBytes),
            reservedRequests = 0,
            reservedBytes = 0,
        ))
    }

    private fun budgetExhausted(recovery: CaptureRecoveryRecord): Boolean =
        recovery.requestsUsed >= recovery.totalRequestBudget || recovery.bytesUsed >= recovery.totalByteBudget
    fun start(id: String, request: CaptureOperationRequest): CaptureOperationRecord = synchronized(store) {
        val session = store.session(id)
        val fingerprint = store.fingerprint(request)
        store.operationOrNull(id,request.requestId)?.let {
            if (it.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is bound to different operation settings")
            return@synchronized it
        }
        requireIdle()
        validate(session,request)
        request.recovery?.let { CaptureValidation.recovery(it, request.step) }
        val cutoffMode = marketCutoffMode(session, request)
        val at = clock.instant()
        val recovery = request.recovery?.let { CaptureRecoveryRecord(it.totalRequestBudget, it.totalByteBudget, it.maxAttempts) }
        val operation = CaptureOperationRecord(store.id(request.requestId),request.requestId,fingerprint,request.step,request.attemptId,CaptureNames.RUNNING,at,at,budget=request.budget,scanPages=request.scanPages,walletAttemptId=session.walletAttemptId,marketAttemptId=session.marketAttemptId,ratesAttemptId=session.ratesAttemptId,recovery=recovery,cutoffMode=cutoffMode?.name)
        val reserved = reserve(operation, firstAttempt = true)
        store.save(id,reserved,true)
        store.save(session.copy(operationId=reserved.id))
        activeSession=id
        executor.execute { run(id,reserved) }
        reserved
    }
    private fun validate(session: CaptureSessionRecord, request: CaptureOperationRequest) {
        if (request.step !in setOf(CaptureNames.WALLET,CaptureNames.MARKET,CaptureNames.RATES,CaptureNames.INSPECT,CaptureNames.EXPORT,CaptureNames.CALIBRATE,CaptureNames.QUALIFY)) CaptureValidation.invalid("step","unsupported operation")
        if (request.cutoffMode != null && request.step != CaptureNames.MARKET) CaptureValidation.invalid(CaptureCutoffModes.FIELD,"only a MARKET operation takes a cutoff mode")
        val selected = when(request.step) { CaptureNames.WALLET -> session.walletAttemptId; CaptureNames.RATES -> session.ratesAttemptId; else -> session.marketAttemptId }
        if (request.attemptId != selected) CaptureValidation.conflict("STALE_ATTEMPT","Select the current capture attempt")
        if (request.step in setOf(CaptureNames.WALLET,CaptureNames.MARKET) && !rpcConfigured) CaptureValidation.conflict("NOT_CONFIGURED","Configure SOLANA_RPC_URL in the backend process")
        if (request.step == CaptureNames.RATES && !ratesConfigured) CaptureValidation.conflict("NOT_CONFIGURED","Reference rate capture is disabled")
        CaptureValidation.budget(request.step,request.budget)
        if (request.step == CaptureNames.WALLET && session.wallet == null) CaptureValidation.conflict("PREREQUISITE","Inspect attached wallet identity before resuming")
        if (request.step == CaptureNames.MARKET) {
            val attempt = session.marketAttemptId?.let { store.attempt(session.id,it) } ?: CaptureValidation.conflict("PREREQUISITE","Create a market attempt")
            val settings = requireNotNull(attempt.market)
            val pages = request.scanPages ?: settings.scanPages
            CaptureValidation.range("scanPages",pages.toLong(),500)
            val previousPages = session.inspection?.marketSummary?.get("scanPages")?.toIntOrNull() ?: settings.scanPages
            if(pages < previousPages) CaptureValidation.conflict("RESUME_IDENTITY","Market scan pages cannot decrease")
        }
        if (request.step == CaptureNames.RATES && selected == null) CaptureValidation.conflict("PREREQUISITE","Create a rates attempt")
        if (request.step == CaptureNames.QUALIFY) {
            if (!walletToken(session.wallet)) CaptureValidation.conflict("UNSUPPORTED_SCOPE","Qualification needs a wallet + token capture")
            val exportId = session.exportOperationId ?: CaptureValidation.conflict("EXPORT_REQUIRED","Export a dataset from the current capture attempts before qualifying")
            if (!exportUnchanged(store.operation(session.id,exportId))) CaptureValidation.conflict("EXPORT_CHANGED","The exported dataset changed since its export; export it again before qualifying")
        }
    }
    private fun marketCutoffMode(session: CaptureSessionRecord, request: CaptureOperationRequest): MarketCutoffMode? {
        if (request.step != CaptureNames.MARKET) return null
        val settings = requireNotNull(store.attempt(session.id, requireNotNull(session.marketAttemptId)).market) { "Market attempt has no settings sessionId=${session.id}" }
        return CaptureCutoffModes.forOperation(request.cutoffMode, settings, ratesConfigured)
    }
    /** Lets the capture see a saved pause or cancel request between detail requests instead of only at its next checkpoint. */
    private fun stoppable(id: String, operationId: String, delegate: CaptureCheckpointObserver) = object : CaptureCheckpointObserver {
        override fun checkpoint(checkpoint: CaptureCheckpoint) = delegate.checkpoint(checkpoint)

        override fun stopRequested(): Boolean = synchronized(store) { store.operation(id, operationId).control } in STOP_CONTROLS
    }
    private fun run(id: String, initial: CaptureOperationRecord) {
        var operation = initial
        var retryAfter: Duration? = null
        try {
            val session = synchronized(store) { store.session(id) }
            val paths = paths(session)
            val verified = workflow.inspect(paths)
            requirePrerequisite(initial,verified)
            val command = command(session,initial,paths)
            val result = workflow.execute(command, stoppable(id, initial.id, CaptureCheckpointObserver { checkpoint -> synchronized(store) {
                val progress = mapper.progress(checkpoint)
                operation = accountCheckpoint(operation, progress).copy(updatedAt=checkpoint.at, progress=progress)
                val saved = store.operation(id, operation.id)
                operation = operation.copy(control = saved.control, controlRequestIds = saved.controlRequestIds)
                when (saved.control) {
                    CaptureNames.PAUSE_REQUESTED -> {
                        operation = operation.copy(state = CaptureNames.PAUSED, control = null, endedAt = checkpoint.at, recovery = releaseReservation(operation.recovery))
                        store.save(id, operation)
                        throw CaptureOperationStop()
                    }
                    CaptureNames.CANCEL_REQUESTED -> {
                        operation = operation.copy(state = CaptureNames.CANCELLED, control = null, code = "CANCELLED_BY_USER", message = "Remaining capture work was cancelled", endedAt = checkpoint.at, recovery = releaseReservation(operation.recovery))
                        store.save(id, operation)
                        throw CaptureOperationStop()
                    }
                    else -> store.save(id,operation)
                }
            } }))
            retryAfter = when (result) {
                is CaptureStepResult.Wallet -> result.capture.stopRetryAfter
                is CaptureStepResult.Markets -> result.capture.stopRetryAfter
                else -> null
            }
            val inspected = if(result is CaptureStepResult.Inspected) result.inspection else workflow.inspect(paths)
            val wallet = inspected.walletCapture?.manifest?.let(::walletSettings)
            val qualification = when {
                result is CaptureStepResult.Qualified -> qualificationMapper.map(result.verdict)
                walletToken(wallet ?: session.wallet) -> qualificationMapper.map(workflow.verdict(inspected, result is CaptureStepResult.Exported || session.exportOperationId != null))
                else -> null
            }
            val complete = when(result) {
                is CaptureStepResult.Wallet -> result.capture.manifest.complete
                is CaptureStepResult.Markets -> result.capture.manifest.complete
                is CaptureStepResult.Rates -> result.capture.manifest.complete
                is CaptureStepResult.CaseRates -> result.capture.manifest.complete
                is CaptureStepResult.Inspected, is CaptureStepResult.Exported, is CaptureStepResult.CaseExported, is CaptureStepResult.Calibrated, is CaptureStepResult.Qualified -> true
            }
            val stopReason = when(result) {
                is CaptureStepResult.Wallet -> result.capture.stopReason
                is CaptureStepResult.Markets -> result.capture.stopReason
                else -> null
            }?.let(::safeCategory)
            val cappedHistory = if (stopReason == null) (result as? CaptureStepResult.Wallet)?.capture?.manifest?.let(::cappedHistoryCode) else null
            val artifact = when(result) {
                is CaptureStepResult.Exported -> generated(session,"dataset.json")
                is CaptureStepResult.Calibrated -> generated(session,"calibration.json")
                else -> null
            }
            operation = operation.copy(state=if(complete) CaptureNames.SUCCEEDED else CaptureNames.INCOMPLETE,updatedAt=clock.instant(),endedAt=clock.instant(),code=stopReason ?: cappedHistory,message=stopReason?.let { stopMessage(it, retryAfter) } ?: cappedHistory?.let { CAPPED_HISTORY_MESSAGE },inspection=inspectionMapper.map(inspected),walletSettings=wallet,artifact=artifact,checksum=artifact?.let { hashFile(it) },calibration=if(result is CaptureStepResult.Calibrated) json.readTree(result.json) else null,qualification=qualification,recovery=releaseReservation(operation.recovery))
        } catch(exception: Exception) {
            if (exception is CaptureOperationStop) {
                operation = store.operation(id, operation.id)
            } else {
            val interrupted = exception is InterruptedException || Thread.currentThread().isInterrupted
            val code = when(exception) {
                is CaptureRequestException -> exception.code
                is CaptureValidationException -> safeCategory(exception.category)
                is CaptureProviderException -> safeCategory(exception.category)
                is CaptureBudgetException -> "BUDGET_EXHAUSTED_${safeCategory(exception.category)}"
                else -> if(interrupted) "INTERRUPTED" else "CAPTURE_FAILED"
            }
            // Only a fixed replacement exception reaches logs: provider exception text can contain credentials.
            if(code == "CAPTURE_FAILED") log.error("capture operation failed operationId={} category={}",operation.id,code,IllegalStateException("Capture operation failed").apply { stackTrace=exception.stackTrace })
            else log.warn("capture operation stopped operationId={} category={}",operation.id,code)
            val provider = exception as? CaptureProviderException
            retryAfter = provider?.retryAfter
            val budgetFailure = exception as? CaptureBudgetException
            val usedRequests = provider?.requestsUsed ?: budgetFailure?.requestsUsed
            val usedBytes = provider?.bytesUsed ?: budgetFailure?.bytesUsed
            val accounted = if (usedRequests != null && usedBytes != null) accountUsage(operation, usedRequests, usedBytes) else operation
            val settled = if (usedRequests != null && usedBytes != null) accounted.copy(recovery = releaseReservation(accounted.recovery)) else settleUnknownReservation(accounted)
            operation = settled.copy(state=if(interrupted) CaptureNames.INTERRUPTED else CaptureNames.FAILED,updatedAt=clock.instant(),endedAt=clock.instant(),code=code,message="Capture operation stopped: $code. Inspect saved evidence before retrying.")
            }
        } finally {
            var followUp: CaptureOperationRecord? = null
            synchronized(store) {
                try {
                    val saved = store.operation(id, operation.id)
                    operation = operation.copy(controlRequestIds = saved.controlRequestIds)
                    operation = when {
                        saved.control == CaptureNames.PAUSE_REQUESTED && operation.state in setOf(CaptureNames.RUNNING, CaptureNames.FAILED, CaptureNames.INCOMPLETE) -> operation.copy(state = CaptureNames.PAUSED, control = null, code = "PAUSED_BY_USER", message = "Capture paused at its last durable checkpoint", endedAt = clock.instant(), recovery = releaseReservation(operation.recovery))
                        saved.control == CaptureNames.CANCEL_REQUESTED && operation.state in setOf(CaptureNames.RUNNING, CaptureNames.FAILED, CaptureNames.INCOMPLETE) -> operation.copy(state = CaptureNames.CANCELLED, control = null, code = "CANCELLED_BY_USER", message = "Remaining capture work was cancelled", endedAt = clock.instant(), recovery = releaseReservation(operation.recovery))
                        else -> operation.copy(control = null)
                    }
                    if (retryable(operation)) {
                        val nextAt = clock.instant().plus(retryDelay(operation, retryAfter))
                        operation = operation.copy(state = CaptureNames.WAITING_RETRY, endedAt = null, updatedAt = clock.instant(), recovery = operation.recovery?.copy(nextRetryAt = nextAt))
                        followUp = operation
                    }
                    store.save(id,operation)
                    store.save(project(store.session(id),operation))
                } finally { activeSession=null }
            }
            followUp?.let { scheduleRetry(id, it, Duration.between(clock.instant(), it.recovery?.nextRetryAt ?: clock.instant()).coerceAtLeast(Duration.ZERO)) }
            if (operation.step == CaptureNames.EXPORT && operation.state == CaptureNames.SUCCEEDED) synchronized(store) { queueQualification(id, operation) }
        }
    }
    /**
     * Qualifies a wallet + token export as the worker's next operation, so the verdict needs no click and never runs beside the
     * export. When it cannot be queued, the session keeps the export's pending verdict and Qualify starts it explicitly.
     */
    private fun queueQualification(id: String, export: CaptureOperationRecord) {
        val session = store.session(id)
        if (!walletToken(session.wallet)) return
        if (!idle()) { log.warn("capture qualification not queued sessionId={} category=WORKER_STOPPING", id); return }
        try {
            start(id, CaptureOperationRequest("qualify-${hash(export.id.toByteArray()).take(QUALIFY_ID_HASH_LENGTH)}", CaptureNames.QUALIFY, session.marketAttemptId))
        } catch (exception: CaptureRequestException) {
            log.warn("capture qualification not queued sessionId={} category={}", id, exception.code)
        }
    }
    private fun exportUnchanged(export: CaptureOperationRecord): Boolean {
        val artifact = export.artifact ?: return false
        return Files.isRegularFile(store.paths.resolve(artifact)) && hashFile(artifact) == export.checksum
    }
    private fun walletToken(settings: CaptureWalletSettings?): Boolean = settings?.let { CaptureWalletScope.of(it.scope) } == CaptureWalletScope.WALLET_TOKEN
    private fun walletSettings(manifest: WalletHistoryManifest): CaptureWalletSettings =
        CaptureWalletSettings(manifest.wallet, CaptureWalletScope.of(manifest.scope).value, manifest.limit, manifest.scanPageLimit, manifest.targetMint)
    private fun requirePrerequisite(operation: CaptureOperationRecord, value: CaptureInspection) {
        val eligible = when(operation.step) {
            CaptureNames.WALLET -> value.wallet.canStart || value.wallet.canResume
            CaptureNames.MARKET -> value.market.canStart || value.market.canResume || extendsDecidedWindow(operation, value)
            CaptureNames.RATES -> value.rates.canStart || value.rates.canResume
            CaptureNames.EXPORT -> value.canExport
            CaptureNames.CALIBRATE -> value.canCalibrate
            else -> true
        }
        if(!eligible) CaptureValidation.conflict("PREREQUISITE","Selected capture does not meet the operation prerequisites")
    }
    /** A complete market stage restarts only to capture the rest of a decided window, which a FULL operation does. */
    private fun extendsDecidedWindow(operation: CaptureOperationRecord, value: CaptureInspection): Boolean =
        CaptureCutoffModes.saved(operation.cutoffMode) == MarketCutoffMode.FULL && value.market.state != CaptureStageState.INVALID &&
            value.marketCapture?.manifest?.windows.orEmpty().any { it.cutoff != null }
    private fun paths(session: CaptureSessionRecord): CapturePaths {
        val wallet=store.attempt(session.id,session.walletAttemptId).directory
        val market=session.marketAttemptId?.let { store.attempt(session.id,it).directory }
        val rates=session.ratesAttemptId?.let { store.attempt(session.id,it).directory }
        store.paths.independent(listOfNotNull(wallet,market,rates))
        return CapturePaths(store.paths.resolve(wallet),market?.let(store.paths::resolve),rates?.let(store.paths::resolve))
    }
    private fun command(session: CaptureSessionRecord, operation: CaptureOperationRecord, paths: CapturePaths): CaptureStepCommand = when(operation.step) {
        CaptureNames.WALLET -> {
            val settings=requireNotNull(session.wallet)
            val scope=requireNotNull(CaptureWalletScope.of(settings.scope)) { "Unsupported wallet scope sessionId=${session.id}" }
            CaptureStepCommand.Wallet(WalletHistoryCaptureRequest(WalletAddress.of(settings.wallet),paths.wallet,settings.limit,settings.scanPages,operation.budget.requestBudget,operation.budget.byteBudget,scope.historyScope(),timeout(operation),settings.mint?.let(MintAddress::of),usage(session,ApiTask.WALLET_STEP)))
        }
        CaptureNames.MARKET -> {
            val attempt=store.attempt(session.id,requireNotNull(session.marketAttemptId)); val settings=requireNotNull(attempt.market)
            CaptureStepCommand.Markets(ReplayMarketCaptureRequest(walletCaptureDirectory=paths.wallet,directory=requireNotNull(paths.market),scanPages=operation.scanPages ?: settings.scanPages,maxTransactions=settings.maxTransactions,
                requestBudget=operation.budget.requestBudget,byteBudget=operation.budget.byteBudget,operationTimeout=timeout(operation),cutoffMode=CaptureCutoffModes.saved(operation.cutoffMode),usage=usage(session,ApiTask.MARKET_STEP)))
        }
        CaptureNames.RATES -> CaptureStepCommand.Rates(paths.wallet,requireNotNull(paths.market),requireNotNull(paths.rates),usage(session,ApiTask.RATES_STEP))
        CaptureNames.EXPORT -> CaptureStepCommand.Export(RecordedReplayExportRequest(paths.wallet,requireNotNull(paths.market),requireNotNull(paths.rates),store.paths.resolve(generated(session,"dataset.json"))))
        CaptureNames.CALIBRATE -> CaptureStepCommand.Calibrate(paths.wallet,requireNotNull(paths.market),store.paths.resolve(generated(session,"calibration.json")))
        CaptureNames.QUALIFY -> CaptureStepCommand.Qualify(store.paths.resolve(exportedDataset(session)),paths)
        else -> CaptureStepCommand.Inspect(paths)
    }
    private fun usage(session: CaptureSessionRecord, task: ApiTask) = ApiUsageContext(task, session.id)
    private fun exportedDataset(session: CaptureSessionRecord): String {
        val exportId=requireNotNull(session.exportOperationId) { "No successful export sessionId=${session.id}" }
        return requireNotNull(store.operation(session.id,exportId).artifact) { "Export has no artifact sessionId=${session.id} operationId=$exportId" }
    }
    private fun timeout(operation: CaptureOperationRecord): Duration = Duration.ofMinutes(operation.budget.operationTimeoutMinutes.toLong())
    private fun generated(session: CaptureSessionRecord, name: String): String = "console/${session.id}/generated/${session.marketAttemptId ?: "none"}/${if(name=="calibration.json") "calibration" else session.ratesAttemptId ?: "none"}/$name"
    private fun project(session: CaptureSessionRecord, operation: CaptureOperationRecord): CaptureSessionRecord {
        if (operation.caseId != null) return session
        if(operation.walletAttemptId!=session.walletAttemptId || operation.marketAttemptId!=session.marketAttemptId) return session
        if(operation.ratesAttemptId!=session.ratesAttemptId && operation.step!=CaptureNames.CALIBRATE) return session
        val success=operation.state==CaptureNames.SUCCEEDED
        val qualifyOperationId=when { success && operation.step==CaptureNames.QUALIFY -> operation.id; success && operation.step==CaptureNames.EXPORT -> null; else -> session.qualifyOperationId }
        val qualification=if(operation.step==CaptureNames.QUALIFY) session.qualification else operation.qualification ?: session.qualification
        return session.copy(operationId=operation.id,inspection=operation.inspection ?: session.inspection,wallet=operation.walletSettings ?: session.wallet,exportOperationId=if(success && operation.step==CaptureNames.EXPORT) operation.id else session.exportOperationId,calibrationOperationId=if(success && operation.step==CaptureNames.CALIBRATE) operation.id else session.calibrationOperationId,qualification=qualification,qualifyOperationId=qualifyOperationId)
    }
    fun artifact(id: String, kind: String): ByteArray = synchronized(store) {
        val session=store.session(id)
        val operationId=when(kind) { "dataset" -> session.exportOperationId; "calibration" -> session.calibrationOperationId; else -> null } ?: throw CaptureRequestException(404,"ARTIFACT_NOT_FOUND","Successful artifact not found")
        val operation=store.operation(id,operationId)
        val relative=operation.artifact ?: throw CaptureRequestException(404,"ARTIFACT_NOT_FOUND","Successful artifact not found")
        if(operation.state!=CaptureNames.SUCCEEDED || !relative.startsWith("console/$id/generated/") || !relative.endsWith("/$kind.json")) CaptureValidation.conflict("ARTIFACT_INVALID","Generated artifact identity is invalid")
        val bytes=Files.readAllBytes(store.paths.resolve(relative))
        if(hash(bytes)!=operation.checksum) CaptureValidation.conflict("ARTIFACT_INVALID","Generated artifact checksum no longer matches")
        bytes
    }
    private fun hashFile(relative: String): String = hash(Files.readAllBytes(store.paths.resolve(relative)))
    private fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun stopMessage(reason: String, retryAfter: Duration?): String = when (reason) {
        DEADLINE -> "The operation deadline ended this run. Saved evidence is kept; resume to continue with a fresh allowance."
        MarketCaptureReasons.HORIZON_NOT_ELAPSED -> "A market window has not ended on chain yet, so it was not listed. " +
            "Retry after ${clock.instant().plus(retryAfter ?: Duration.ZERO).truncatedTo(ChronoUnit.SECONDS)} (estimated); " +
            "resuming then finishes it."
        else -> "This run stopped early: $reason. Saved evidence is kept; inspect it, then resume to continue."
    }
    /** A wallet + token capture that finished its requests but stopped listing before the end of its history; resuming cannot complete it. */
    private fun cappedHistoryCode(manifest: WalletHistoryManifest): String? =
        TokenAccountGap.HISTORY_NOT_EXHAUSTED.name.takeIf { WalletHistoryCompletion.historyNotExhausted(manifest) }
    private fun safeCategory(value: String): String = if(value.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) value else "CAPTURE_FAILED"
    override fun close() {
        synchronized(store) { closed=true }
        retryScheduler.close()
        executor.shutdownNow()
        var interrupted = false
        while (true) {
            try {
                if (executor.awaitTermination(1, TimeUnit.MINUTES)) break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
    private companion object {
        val STOP_CONTROLS = setOf(CaptureNames.PAUSE_REQUESTED, CaptureNames.CANCEL_REQUESTED)
        const val DEADLINE = "DEADLINE"
        const val CAPPED_HISTORY_MESSAGE = "The token-account listing stopped at the sample's record or page limit before the end of its history. " +
            "Saved evidence is kept; resuming cannot finish it, so start a new session with a larger sample or more wallet scan pages."
        const val QUALIFY_ID_HASH_LENGTH = 32
        const val MAX_RETRY_SECONDS = 300L
        const val RETRY_JITTER_MILLIS = 1000L
        val RETRYABLE_PROVIDER_CATEGORIES = setOf("IO", "TIMEOUT", "RATE_LIMITED", "UNAVAILABLE")
        val RETRYABLE_BUDGET_CATEGORIES = setOf("REQUESTS", "BYTES", "RESPONSE_BYTES", DEADLINE)
    }
}
