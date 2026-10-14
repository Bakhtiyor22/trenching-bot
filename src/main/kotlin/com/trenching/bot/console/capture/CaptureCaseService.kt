package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.DatasetRegistrationResponse
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.console.mapper.DatasetResponseMapper
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.replay.CaptureCaseInspection
import com.trenching.bot.replay.CaptureCaseExport
import com.trenching.bot.replay.CaptureCaseExportRequest
import com.trenching.bot.replay.CaptureCaseStage
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.CaptureStepResult
import com.trenching.bot.replay.CaptureWorkflow
import com.trenching.bot.replay.ReplayDatasetCatalog
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class CaptureCaseService(
    private val store: CaptureFileStore,
    private val workflow: CaptureWorkflow,
    private val mapper: CaptureResponseMapper,
    private val datasets: DatasetResponseMapper,
    private val catalog: ReplayDatasetCatalog,
    private val clock: Clock,
    private val executor: ExecutorService,
) : CaptureCaseActivity, AutoCloseable {
    private val log = LoggerFactory.getLogger(CaptureCaseService::class.java)
    private var activeSession: String? = null
    private var closed = false

    fun recover() = synchronized(store) {
        store.sessions().forEach { sessionId ->
            store.operations(sessionId).filter { it.caseId != null && it.state == CaptureNames.RUNNING }.forEach { operation ->
                store.save(sessionId, operation.copy(state = CaptureNames.INTERRUPTED, updatedAt = clock.instant(), endedAt = clock.instant(),
                    code = "PROCESS_RESTART", message = "Case operation was interrupted by process restart"))
            }
        }
    }

    fun cases(id: String): CaptureCasesView {
        val snapshot = synchronized(store) {
            val session = store.session(id)
            if (session.marketAttemptId == null) return CaptureCasesView(emptyList())
            session to paths(session)
        }
        val session = snapshot.first
        val paths = snapshot.second
        val inventory = inspectInventory(paths)
        return synchronized(store) {
            val current = store.session(id)
            if (!sameAttempts(session, current)) CaptureValidation.conflict("STALE_ATTEMPT", "Capture attempts changed while cases were inspected")
            CaptureCasesView(inventory.cases.map { inspection -> view(current, inspection) })
        }
    }

    fun captureRates(id: String, caseId: String, requestId: String): CaptureOperationRecord =
        start(id, caseId, requestId, CaptureNames.RATES)

    fun export(id: String, caseId: String, requestId: String): CaptureOperationRecord =
        start(id, caseId, requestId, CaptureNames.EXPORT)

    fun artifact(id: String, caseId: String): ByteArray {
        val snapshot = synchronized(store) {
            val session = store.session(id)
            val record = currentRecord(session, caseId)
            val exportId = record?.exportOperationId ?: throw CaptureRequestException(404, "ARTIFACT_NOT_FOUND", "Successful case dataset was not found")
            val operation = store.operation(id, exportId)
            val relative = operation.artifact ?: throw CaptureRequestException(404, "ARTIFACT_NOT_FOUND", "Successful case dataset was not found")
            if (operation.state != CaptureNames.SUCCEEDED || operation.caseId != caseId || !relative.startsWith("console/$id/generated/cases/$caseId/")) {
                CaptureValidation.conflict("ARTIFACT_INVALID", "Generated case artifact identity is invalid")
            }
            relative to operation.checksum
        }
        val bytes = Files.readAllBytes(store.paths.resolve(snapshot.first))
        if (hash(bytes) != snapshot.second) CaptureValidation.conflict("ARTIFACT_INVALID", "Generated case artifact checksum no longer matches")
        return bytes
    }

    fun register(id: String, caseId: String): DatasetRegistrationResponse {
        val snapshot = synchronized(store) {
            val session = store.session(id)
            val record = currentRecord(session, caseId)
                ?: throw CaptureRequestException(404, "ARTIFACT_NOT_FOUND", "Successful case dataset was not found")
            val exportId = record.exportOperationId
                ?: throw CaptureRequestException(404, "ARTIFACT_NOT_FOUND", "Successful case dataset was not found")
            Triple(session.name, record, store.operation(id, exportId))
        }
        val bytes = artifact(id, caseId)
        val registered = bytes.inputStream().use { catalog.register("${snapshot.first} · $caseId", it) }
        synchronized(store) {
            val session = store.session(id)
            val current = currentRecord(session, caseId)
            if (current?.exportOperationId != snapshot.second.exportOperationId || store.operation(id, snapshot.third.id).checksum != snapshot.third.checksum) {
                CaptureValidation.conflict("STALE_ATTEMPT", "The case export changed while the dataset was imported")
            }
            store.save(id, requireNotNull(current).copy(datasetId = registered.dataset.id))
        }
        return datasets.toResponse(registered)
    }

    private fun start(id: String, caseIdValue: String, requestId: String, step: String): CaptureOperationRecord {
        val caseId = marketCaseId(caseIdValue)
        val snapshot = synchronized(store) {
            store.id(requestId)
            val session = store.session(id)
            if (session.marketAttemptId == null) CaptureValidation.conflict("PREREQUISITE", "Create a market attempt before selecting a case")
            val fingerprint = fingerprint(session, requestId, step, caseId)
            store.operationOrNull(id, requestId)?.let { existing ->
                if (existing.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is bound to different case settings")
                return existing
            }
            session to paths(session)
        }
        val session = snapshot.first
        val paths = snapshot.second
        val summary = inspectInventory(paths).cases.singleOrNull { it.caseId == caseId }
            ?: CaptureValidation.conflict("MARKET_CASE_NOT_FOUND", "The selected case is not in the current wallet plan")
        return synchronized(store) {
            val currentSession = store.session(id)
            if (!sameAttempts(session, currentSession)) CaptureValidation.conflict("STALE_ATTEMPT", "Capture attempts changed while the case was inspected")
            val fingerprint = fingerprint(currentSession, requestId, step, caseId)
            store.operationOrNull(id, requestId)?.let { existing ->
                if (existing.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is bound to different case settings")
                return@synchronized existing
            }
            requireIdle()
            val record = currentRecord(currentSession, caseId.value)
            if (step == CaptureNames.EXPORT && !ratesReady(record, id)) {
                CaptureValidation.conflict("REFERENCE_RATES_INCOMPLETE", "Validate the case and capture its reference rates before exporting")
            }
            val at = clock.instant()
            val operation = CaptureOperationRecord(
                id = requestId, requestId = requestId, fingerprint = fingerprint, step = step, attemptId = currentSession.marketAttemptId,
                state = CaptureNames.RUNNING, startedAt = at, updatedAt = at, budget = CaptureBudget(), scanPages = null,
                walletAttemptId = currentSession.walletAttemptId, marketAttemptId = currentSession.marketAttemptId, ratesAttemptId = currentSession.ratesAttemptId,
                caseId = caseId.value, caseInspection = summary,
            )
            store.save(id, operation, true)
            val current = record ?: CaptureCaseRecord(caseId = caseId.value, walletAttemptId = currentSession.walletAttemptId,
                marketAttemptId = requireNotNull(currentSession.marketAttemptId), ratesAttemptId = currentSession.ratesAttemptId)
            store.save(id, current.copy(operationId = operation.id))
            activeSession = id
            executor.execute { run(id, operation) }
            operation
        }
    }

    private fun run(id: String, initial: CaptureOperationRecord) {
        var operation = initial
        try {
            val session = synchronized(store) { store.session(id) }
            val paths = paths(session)
            val caseId = marketCaseId(requireNotNull(initial.caseId))
            val before = workflow.inspectCase(paths.wallet, requireNotNull(paths.market), caseId)
            operation = operation.copy(caseInspection = before)
            when (initial.step) {
                CaptureNames.RATES -> {
                    val result = workflow.execute(CaptureStepCommand.CaseRates(paths.wallet, requireNotNull(paths.market), caseId, ratesDirectory(session, caseId)), observer(id, operation.id) { saved -> operation = saved })
                    val rates = (result as? CaptureStepResult.CaseRates)?.capture
                        ?: throw CaptureValidationException("CASE_RESULT_MISMATCH")
                    val after = workflow.inspectCase(paths.wallet, requireNotNull(paths.market), caseId)
                    operation = operation.copy(
                        state = if (rates.manifest.complete) CaptureNames.SUCCEEDED else CaptureNames.INCOMPLETE,
                        updatedAt = clock.instant(), endedAt = clock.instant(),
                        code = if (rates.manifest.complete) null else "REFERENCE_RATES_INCOMPLETE",
                        message = if (rates.manifest.complete) null else "Reference rates are incomplete for this case",
                        caseInspection = after,
                    )
                }
                CaptureNames.EXPORT -> operation = exportCase(session, paths, caseId, before, operation)
                else -> throw CaptureValidationException("CASE_OPERATION_UNSUPPORTED")
            }
        } catch (exception: Exception) {
            val code = when (exception) {
                is CaptureRequestException -> exception.code
                is CaptureValidationException -> safeCategory(exception.category)
                is InterruptedException -> "INTERRUPTED"
                else -> "CAPTURE_FAILED"
            }
            if (code == "CAPTURE_FAILED") {
                log.error("case operation failed operationId={} category={}", operation.id, code,
                    IllegalStateException("Case operation failed").apply { stackTrace = exception.stackTrace })
            } else {
                log.warn("case operation stopped operationId={} category={}", operation.id, code)
            }
            operation = operation.copy(state = CaptureNames.FAILED, updatedAt = clock.instant(), endedAt = clock.instant(),
                code = code, message = "Case operation stopped: $code. Inspect saved evidence before retrying.")
        } finally {
            synchronized(store) {
                try {
                    val session = store.sessionOrNull(id)
                    if (session != null) {
                        store.save(id, operation)
                        val record = currentRecord(session, requireNotNull(operation.caseId))
                        if (record != null && record.operationId == operation.id) {
                            store.save(id, record.copy(
                                ratesOperationId = if (operation.state == CaptureNames.SUCCEEDED && operation.step == CaptureNames.RATES) operation.id else record.ratesOperationId,
                                exportOperationId = if (operation.state == CaptureNames.SUCCEEDED && operation.step == CaptureNames.EXPORT) operation.id else record.exportOperationId,
                            ))
                        }
                    }
                } finally {
                    activeSession = null
                }
            }
        }
    }

    private fun exportCase(session: CaptureSessionRecord, paths: CapturePaths, caseId: MarketCaseId, before: CaptureCaseInspection,
                           operation: CaptureOperationRecord): CaptureOperationRecord {
        val finalArtifact = datasetArtifact(session, caseId)
        val temporaryArtifact = "$finalArtifact.${operation.id}.tmp"
        val request = CaptureCaseExportRequest(
            paths.wallet, requireNotNull(paths.market), ratesDirectory(session, caseId), caseId,
            before.wallet, before.mint, before.anchorSignature, before.anchorSlot, before.instructionIndex,
            before.window.fromSlot.value, before.window.toSlot.value, before.windowParameters, store.paths.resolve(temporaryArtifact),
        )
        val result = workflow.execute(CaptureStepCommand.ExportCase(request))
        val exported = (result as? CaptureStepResult.CaseExported)?.export
            ?: throw CaptureValidationException("CASE_RESULT_MISMATCH")
        val after = workflow.inspectCase(paths.wallet, requireNotNull(paths.market), caseId)
        requireSameCase(before, after)
        val provenanceArtifact = finalArtifact.removeSuffix("dataset.json") + "provenance.json"
        val record = exportRecord(exported, finalArtifact, provenanceArtifact)
        store.saveCaseProvenance(provenanceArtifact, record)
        Files.createDirectories(store.paths.resolve(finalArtifact).parent)
        Files.move(store.paths.resolve(temporaryArtifact), store.paths.resolve(finalArtifact), StandardCopyOption.ATOMIC_MOVE)
        val checksum = hash(Files.readAllBytes(store.paths.resolve(finalArtifact)))
        return operation.copy(state = CaptureNames.SUCCEEDED, updatedAt = clock.instant(), endedAt = clock.instant(),
            artifact = finalArtifact, checksum = checksum, caseInspection = after, caseExport = record)
    }

    private fun observer(id: String, operationId: String, update: (CaptureOperationRecord) -> Unit) = CaptureCheckpointObserver { checkpoint ->
        synchronized(store) {
            val current = store.operation(id, operationId)
            val saved = current.copy(updatedAt = checkpoint.at, progress = mapper.progress(checkpoint))
            store.save(id, saved)
            update(saved)
        }
    }

    private fun view(session: CaptureSessionRecord, inspection: CaptureCaseInspection): CaptureCaseView {
        val record = currentRecord(session, inspection.caseId.value)
        val operation = record?.operationId?.let { store.operationOrNull(session.id, it) }
        val ratesOperation = record?.ratesOperationId?.let { store.operationOrNull(session.id, it) }
        val validated = ratesOperation?.caseInspection?.ready == true && ratesOperation.state == CaptureNames.SUCCEEDED
        val export = record?.exportOperationId?.let { store.operationOrNull(session.id, it) }
        val exportReady = export?.state == CaptureNames.SUCCEEDED && export.artifact != null
        val active = operation?.state in ACTIVE_STATES
        val failed = operation?.state in setOf(CaptureNames.FAILED, CaptureNames.CANCELLED)
        val incomplete = operation?.state == CaptureNames.INCOMPLETE
        val readiness = when {
            active -> requireNotNull(operation).state
            failed -> requireNotNull(operation).state
            incomplete -> "BLOCKED"
            validated || exportReady -> "READY"
            inspection.stage == CaptureCaseStage.READY_TO_VALIDATE -> "READY_TO_VALIDATE"
            else -> "BLOCKED"
        }
        val captureComplete = inspection.stage in setOf(CaptureCaseStage.READY_TO_VALIDATE, CaptureCaseStage.READY, CaptureCaseStage.INVALID)
        return CaptureCaseView(
            inspection.caseId.value, inspection.wallet.value, inspection.mint.value, inspection.anchorSignature.value,
            inspection.anchorSlot, inspection.instructionIndex, inspection.window.fromSlot.value, inspection.window.toSlot.value,
            mapOf("horizonSlots" to inspection.windowParameters.horizonSlots, "maxAgeSlots" to inspection.windowParameters.maxAgeSlots),
            inspection.selected, inspection.captured, inspection.gaps, inspection.stage.name, inspection.coverage.name,
            operation?.progress, readiness, captureComplete, validated, validated, exportReady,
            exportReady && record.datasetId == null, inspection.stage == CaptureCaseStage.READY_TO_VALIDATE && !validated && !active,
            validated && !exportReady && !active,
            when { operation?.state in setOf(CaptureNames.FAILED, CaptureNames.CANCELLED, CaptureNames.INCOMPLETE) -> operation?.code; validated -> ratesOperation?.caseInspection?.blocker; else -> inspection.blocker },
            operation?.id, operation?.state, record?.datasetId,
        )
    }

    private fun currentRecord(session: CaptureSessionRecord, caseId: String): CaptureCaseRecord? =
        store.captureCaseOrNull(session.id, caseId)?.takeIf {
            it.walletAttemptId == session.walletAttemptId && it.marketAttemptId == session.marketAttemptId && it.ratesAttemptId == session.ratesAttemptId
        }

    private fun fingerprint(session: CaptureSessionRecord, requestId: String, step: String, caseId: MarketCaseId): String =
        store.fingerprint(mapOf("requestId" to requestId, "step" to step, "caseId" to caseId.value,
            "walletAttemptId" to session.walletAttemptId, "marketAttemptId" to session.marketAttemptId, "ratesAttemptId" to session.ratesAttemptId))

    private fun sameAttempts(first: CaptureSessionRecord, second: CaptureSessionRecord): Boolean =
        first.walletAttemptId == second.walletAttemptId && first.marketAttemptId == second.marketAttemptId && first.ratesAttemptId == second.ratesAttemptId

    private fun ratesReady(record: CaptureCaseRecord?, sessionId: String): Boolean {
        val operation = record?.ratesOperationId?.let { store.operationOrNull(sessionId, it) } ?: return false
        return operation.step == CaptureNames.RATES && operation.state == CaptureNames.SUCCEEDED && operation.caseInspection?.ready == true
    }

    private fun paths(session: CaptureSessionRecord): CapturePaths {
        val wallet = store.attempt(session.id, session.walletAttemptId).directory
        val market = session.marketAttemptId?.let { store.attempt(session.id, it).directory }
        val rates = session.ratesAttemptId?.let { store.attempt(session.id, it).directory }
        store.paths.independent(listOfNotNull(wallet, market, rates))
        return CapturePaths(store.paths.resolve(wallet), market?.let(store.paths::resolve), rates?.let(store.paths::resolve))
    }

    private fun inspectInventory(paths: CapturePaths) = try {
        workflow.inspectCases(paths.wallet, requireNotNull(paths.market))
    } catch (exception: CaptureValidationException) {
        CaptureValidation.conflict(safeCategory(exception.category), "Capture cases cannot be inspected until their wallet plan is valid")
    }

    private fun marketCaseId(value: String): MarketCaseId = try {
        MarketCaseId.of(value)
    } catch (_: IllegalArgumentException) {
        CaptureValidation.invalid("caseId", "invalid market case id")
    }

    private fun ratesDirectory(session: CaptureSessionRecord, caseId: MarketCaseId) =
        store.paths.resolve("console/${session.id}/cases/${caseId.value}/${requireNotNull(session.marketAttemptId)}/rates")

    private fun datasetArtifact(session: CaptureSessionRecord, caseId: MarketCaseId) =
        "console/${session.id}/generated/cases/${caseId.value}/${requireNotNull(session.marketAttemptId)}/dataset.json"

    private fun exportRecord(value: CaptureCaseExport, artifact: String, provenanceArtifact: String) = CaptureCaseExportRecord(
        value.caseId.value, value.trackedWallet.value, value.mint.value, value.anchorSignature.value, value.anchorSlot,
        value.instructionIndex, value.fromSlot, value.toSlot,
        mapOf("horizonSlots" to value.windowParameters.horizonSlots, "maxAgeSlots" to value.windowParameters.maxAgeSlots),
        value.extractor.name, artifact, value.checksum, provenanceArtifact,
    )

    private fun requireSameCase(first: CaptureCaseInspection, second: CaptureCaseInspection) {
        if (first.caseId != second.caseId || first.wallet != second.wallet || first.mint != second.mint ||
            first.anchorSignature != second.anchorSignature || first.anchorSlot != second.anchorSlot ||
            first.instructionIndex != second.instructionIndex || first.window != second.window ||
            first.windowParameters != second.windowParameters || !second.ready) {
            throw CaptureValidationException("MARKET_CASE_IDENTITY_MISMATCH")
        }
    }

    override fun requireIdle() {
        if (activeSession != null || closed) CaptureValidation.conflict("CASE_WORKER_BUSY", "The independent case worker is busy or stopping")
    }

    override fun requireNotRunning(sessionId: String) {
        if (activeSession == sessionId) CaptureValidation.conflict("CAPTURE_BUSY", "This capture has a running case operation")
    }

    override fun close() {
        synchronized(store) { closed = true }
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

    private fun safeCategory(value: String): String = if (value.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) value else "CAPTURE_FAILED"
    private fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val ACTIVE_STATES = setOf(CaptureNames.RUNNING, CaptureNames.WAITING_RETRY, CaptureNames.PAUSED)
    }
}
