package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.*
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.replay.*
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryScope
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class CaptureOperationService(private val store: CaptureFileStore, private val workflow: CaptureWorkflow, private val mapper: CaptureResponseMapper, private val json: ObjectMapper, private val clock: Clock, private val executor: ExecutorService, private val rpcConfigured: Boolean, private val ratesConfigured: Boolean) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val inspectionMapper = CaptureInspectionMapper(mapper)
    private var active = false
    private var closed = false
    fun requireIdle() { if (active || closed) CaptureValidation.conflict("CAPTURE_BUSY", "Capture worker is busy or stopping") }
    fun recover() = synchronized(store) {
        store.sessions().forEach { id ->
            var session = store.session(id)
            store.operations(id).sortedWith(compareBy<CaptureOperationRecord> { it.startedAt }.thenBy { it.id }).forEach { original ->
                val operation = if (original.state == CaptureNames.RUNNING) original.copy(state = CaptureNames.INTERRUPTED, updatedAt = clock.instant(), endedAt = clock.instant(), code = "PROCESS_RESTART", message = "Operation interrupted; inspect or explicitly resume") else original
                if (operation != original) store.save(id, operation)
                session = project(session, operation)
            }
            store.save(session)
        }
    }
    fun find(id: String, operationId: String): CaptureOperationRecord = synchronized(store) { store.session(id); store.operation(id,operationId) }
    fun start(id: String, request: CaptureOperationRequest): CaptureOperationRecord = synchronized(store) {
        val session = store.session(id)
        val fingerprint = store.fingerprint(request)
        store.operationOrNull(id,request.requestId)?.let {
            if (it.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is bound to different operation settings")
            return@synchronized it
        }
        requireIdle()
        validate(session,request)
        val at = clock.instant()
        val operation = CaptureOperationRecord(store.id(request.requestId),request.requestId,fingerprint,request.step,request.attemptId,CaptureNames.RUNNING,at,at,budget=request.budget,scanPages=request.scanPages,walletAttemptId=session.walletAttemptId,marketAttemptId=session.marketAttemptId,ratesAttemptId=session.ratesAttemptId)
        store.save(id,operation,true)
        store.save(session.copy(operationId=operation.id))
        active=true
        executor.execute { run(id,operation) }
        operation
    }
    private fun validate(session: CaptureSessionRecord, request: CaptureOperationRequest) {
        if (request.step !in setOf(CaptureNames.WALLET,CaptureNames.MARKET,CaptureNames.RATES,CaptureNames.INSPECT,CaptureNames.EXPORT,CaptureNames.CALIBRATE)) CaptureValidation.invalid("step","unsupported operation")
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
    }
    private fun run(id: String, initial: CaptureOperationRecord) {
        var operation = initial
        try {
            val session = synchronized(store) { store.session(id) }
            val paths = paths(session)
            val verified = workflow.inspect(paths)
            requirePrerequisite(initial.step,verified)
            val command = command(session,initial,paths)
            val result = workflow.execute(command, CaptureCheckpointObserver { checkpoint -> synchronized(store) {
                operation = operation.copy(updatedAt=checkpoint.at,progress=mapper.progress(checkpoint))
                store.save(id,operation)
            } })
            val inspected = if(result is CaptureStepResult.Inspected) result.inspection else workflow.inspect(paths)
            val wallet = inspected.walletCapture?.manifest?.let { CaptureWalletSettings(it.wallet,if(it.scope == WalletHistoryScope.WALLET_REFERENCES) "wallet-references" else "pumpfun-user-buys",it.limit,it.scanPageLimit) }
            val complete = when(result) {
                is CaptureStepResult.Wallet -> result.capture.manifest.complete
                is CaptureStepResult.Markets -> result.capture.manifest.complete
                is CaptureStepResult.Rates -> result.capture.manifest.complete
                is CaptureStepResult.Inspected, is CaptureStepResult.Exported, is CaptureStepResult.Calibrated -> true
            }
            val stopReason = when(result) {
                is CaptureStepResult.Wallet -> result.capture.stopReason
                is CaptureStepResult.Markets -> result.capture.stopReason
                else -> null
            }?.let(::safeCategory)
            val artifact = when(result) {
                is CaptureStepResult.Exported -> generated(session,"dataset.json")
                is CaptureStepResult.Calibrated -> generated(session,"calibration.json")
                else -> null
            }
            operation = operation.copy(state=if(complete) CaptureNames.SUCCEEDED else CaptureNames.INCOMPLETE,updatedAt=clock.instant(),endedAt=clock.instant(),code=stopReason,message=stopReason?.let(::stopMessage),inspection=inspectionMapper.map(inspected),walletSettings=wallet,artifact=artifact,checksum=artifact?.let { hashFile(it) },calibration=if(result is CaptureStepResult.Calibrated) json.readTree(result.json) else null)
        } catch(exception: Exception) {
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
            operation = operation.copy(state=if(interrupted) CaptureNames.INTERRUPTED else CaptureNames.FAILED,updatedAt=clock.instant(),endedAt=clock.instant(),code=code,message="Capture operation stopped: $code. Inspect saved evidence before retrying.")
        } finally {
            synchronized(store) {
                try { store.save(id,operation); store.save(project(store.session(id),operation)) } finally { active=false }
            }
        }
    }
    private fun requirePrerequisite(step: String, value: CaptureInspection) {
        val eligible = when(step) {
            CaptureNames.WALLET -> value.wallet.canStart || value.wallet.canResume
            CaptureNames.MARKET -> value.market.canStart || value.market.canResume
            CaptureNames.RATES -> value.rates.canStart || value.rates.canResume
            CaptureNames.EXPORT -> value.canExport
            CaptureNames.CALIBRATE -> value.canCalibrate
            else -> true
        }
        if(!eligible) CaptureValidation.conflict("PREREQUISITE","Selected capture does not meet the operation prerequisites")
    }
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
            CaptureStepCommand.Wallet(WalletHistoryCaptureRequest(WalletAddress.of(settings.wallet),paths.wallet,settings.limit,settings.scanPages,operation.budget.requestBudget,operation.budget.byteBudget,if(settings.scope=="wallet-references") WalletHistoryScope.WALLET_REFERENCES else WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR,timeout(operation)))
        }
        CaptureNames.MARKET -> {
            val attempt=store.attempt(session.id,requireNotNull(session.marketAttemptId)); val settings=requireNotNull(attempt.market)
            CaptureStepCommand.Markets(ReplayMarketCaptureRequest(paths.wallet,requireNotNull(paths.market),operation.scanPages ?: settings.scanPages,settings.maxTransactions,operation.budget.requestBudget,operation.budget.byteBudget,timeout(operation)))
        }
        CaptureNames.RATES -> CaptureStepCommand.Rates(paths.wallet,requireNotNull(paths.market),requireNotNull(paths.rates))
        CaptureNames.EXPORT -> CaptureStepCommand.Export(RecordedReplayExportRequest(paths.wallet,requireNotNull(paths.market),requireNotNull(paths.rates),store.paths.resolve(generated(session,"dataset.json"))))
        CaptureNames.CALIBRATE -> CaptureStepCommand.Calibrate(paths.wallet,requireNotNull(paths.market),store.paths.resolve(generated(session,"calibration.json")))
        else -> CaptureStepCommand.Inspect(paths)
    }
    private fun timeout(operation: CaptureOperationRecord): Duration = Duration.ofMinutes(operation.budget.operationTimeoutMinutes.toLong())
    private fun generated(session: CaptureSessionRecord, name: String): String = "console/${session.id}/generated/${session.marketAttemptId ?: "none"}/${if(name=="calibration.json") "calibration" else session.ratesAttemptId ?: "none"}/$name"
    private fun project(session: CaptureSessionRecord, operation: CaptureOperationRecord): CaptureSessionRecord {
        if(operation.walletAttemptId!=session.walletAttemptId || operation.marketAttemptId!=session.marketAttemptId) return session
        if(operation.ratesAttemptId!=session.ratesAttemptId && operation.step!=CaptureNames.CALIBRATE) return session
        val success=operation.state==CaptureNames.SUCCEEDED
        return session.copy(operationId=operation.id,inspection=operation.inspection ?: session.inspection,wallet=operation.walletSettings ?: session.wallet,exportOperationId=if(success && operation.step==CaptureNames.EXPORT) operation.id else session.exportOperationId,calibrationOperationId=if(success && operation.step==CaptureNames.CALIBRATE) operation.id else session.calibrationOperationId)
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
    private fun stopMessage(reason: String): String =
        if (reason == DEADLINE) "The operation deadline ended this run. Saved evidence is kept; resume to continue with a fresh allowance."
        else "This run stopped early: $reason. Saved evidence is kept; inspect it, then resume to continue."
    private fun safeCategory(value: String): String = if(value.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) value else "CAPTURE_FAILED"
    override fun close() {
        synchronized(store) { closed=true }
        executor.shutdownNow()
        if(!executor.awaitTermination(20,TimeUnit.SECONDS)) log.warn("capture worker shutdown category=WAITING_FOR_BOUNDED_PROVIDER_TIMEOUT")
    }
    private companion object { const val DEADLINE = "DEADLINE" }
}
