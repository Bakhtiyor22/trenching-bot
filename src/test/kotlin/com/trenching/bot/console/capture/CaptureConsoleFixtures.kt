package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.CaptureStageSummary
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.CaptureStepResult
import com.trenching.bot.replay.CaptureWorkflow
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.TargetTokenVerdict
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** Queues work until the test runs it, so a RUNNING operation can be observed and contended deterministically. */
class ManualExecutor : AbstractExecutorService() {
    val queued = ArrayDeque<Runnable>()
    private var shutdown = false
    override fun execute(command: Runnable) { queued.addLast(command) }
    fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return queued.toMutableList().also { queued.clear() } }
    override fun isShutdown() = shutdown
    override fun isTerminated() = shutdown && queued.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

class ManualCaptureRetryScheduler : CaptureRetryScheduler {
    data class Pending(val delay: Duration, val action: () -> Unit)
    val pending = ArrayDeque<Pending>()
    override fun schedule(delay: Duration, action: () -> Unit) { pending.addLast(Pending(delay, action)) }
    fun runNext() { pending.removeFirst().action() }
}

/** A workflow whose inspection result is set by the test; it records every command it receives. */
class FakeCaptureWorkflow : CaptureWorkflow {
    val executed = mutableListOf<CaptureStepCommand>()
    var inspections = 0
    var inspection = inspection(CaptureStageSummary(CaptureStageState.NOT_STARTED, canStart = true))
    var failure: Exception? = null
    var checkpointBeforeFailure: CaptureCheckpoint? = null
    var qualifyVerdict: TargetTokenVerdict = CaptureTokenFixtures.takeProfit
    /** Each inspection-only verdict asked for, with whether a dataset was exported; it answers with [CaptureTokenFixtures.inspectionOnly]. */
    val verdicts = mutableListOf<Pair<CaptureInspection, Boolean>>()

    /** What the observer reported about a stop request when each command started, before any checkpoint. */
    val stopRequestedAtStart = mutableListOf<Boolean>()

    override fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult {
        executed += command
        stopRequestedAtStart += checkpoints.stopRequested()
        checkpointBeforeFailure?.let(checkpoints::checkpoint)
        failure?.let { throw it }
        checkpoints.checkpoint(CaptureCheckpoint("FAKE", AT, operationRequests = 3, cumulativeRequests = 5))
        return when (command) {
            is CaptureStepCommand.Inspect -> CaptureStepResult.Inspected(inspect(command.paths))
            is CaptureStepCommand.Export -> {
                Files.createDirectories(command.request.output.parent)
                Files.writeString(command.request.output, EXPORTED)
                CaptureStepResult.Exported(RecordedReplayExport(command.request.output, "checksum", WalletAddress.of(CaptureConsoleFixture.WALLET), 1, 1, 1, 1, true))
            }
            is CaptureStepCommand.Qualify -> CaptureStepResult.Qualified(qualifyVerdict)
            is CaptureStepCommand.CaseRates, is CaptureStepCommand.ExportCase -> throw CaptureValidationException("UNSUPPORTED_IN_FAKE")
            else -> throw CaptureValidationException("UNSUPPORTED_IN_FAKE")
        }
    }

    override fun inspect(paths: CapturePaths): CaptureInspection { inspections++; return inspection }

    override fun verdict(inspection: CaptureInspection, datasetExported: Boolean): TargetTokenVerdict {
        verdicts += inspection to datasetExported
        return CaptureTokenFixtures.inspectionOnly(datasetExported = datasetExported)
    }

    companion object {
        val AT: Instant = Instant.parse("2026-10-10T09:00:00Z")
        const val EXPORTED = """{"schemaVersion":3}"""
        private val notReady = CaptureStageSummary(CaptureStageState.NOT_STARTED, "WALLET_CAPTURE_NOT_READY")
        fun inspection(wallet: CaptureStageSummary, market: CaptureStageSummary = notReady, rates: CaptureStageSummary = notReady) =
            CaptureInspection(wallet, market, rates, canCalibrate = false, canExport = false)
    }
}

class CaptureConsoleFixture(root: Path, rpcConfigured: Boolean = true, ratesConfigured: Boolean = true, retryScheduler: CaptureRetryScheduler = CaptureRetryScheduler.NONE) {
    val json: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val clock: Clock = Clock.fixed(FakeCaptureWorkflow.AT, ZoneOffset.UTC)
    val workflow = FakeCaptureWorkflow()
    val executor = ManualExecutor()
    val store = CaptureFileStore(CapturePathPolicy(root), json).also { it.initialize() }
    private var nextId = 0
    val operations = CaptureOperationService(store, workflow, CaptureResponseMapperImpl(), json, clock, executor, rpcConfigured, ratesConfigured, retryScheduler)
    val sessions = CaptureSessionService(store, operations, clock, CaptureIds { "id-${++nextId}" }, ratesConfigured)

    /** Creates a wallet session and runs its automatic offline inspection, leaving the worker idle and the log empty. */
    fun create(requestId: String = "session-1"): CaptureSessionView {
        sessions.create(CaptureCreateRequest(requestId, "Sample", wallet = CaptureWalletSettings(WALLET)))
        settle()
        return sessions.find(requestId)
    }

    /** Creates a wallet + token session for [CaptureTokenFixtures.MINT] and runs its automatic inspection. */
    fun createToken(requestId: String = "session-1"): CaptureSessionView {
        sessions.create(CaptureCreateRequest(requestId, "Token", wallet = CaptureWalletSettings(WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT)))
        settle()
        return sessions.find(requestId)
    }

    /** Runs queued work and forgets the commands it recorded, so a test sees only what it triggers itself. */
    fun settle() { executor.runAll(); workflow.executed.clear(); workflow.inspections = 0 }

    companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
