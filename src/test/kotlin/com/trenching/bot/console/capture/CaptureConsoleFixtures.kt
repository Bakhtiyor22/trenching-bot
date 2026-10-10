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
import java.nio.file.Path
import java.time.Clock
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

/** A workflow whose inspection result is set by the test; it records every command it receives. */
class FakeCaptureWorkflow : CaptureWorkflow {
    val executed = mutableListOf<CaptureStepCommand>()
    var inspections = 0
    var inspection = inspection(CaptureStageSummary(CaptureStageState.NOT_STARTED, canStart = true))
    var failure: Exception? = null

    override fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult {
        executed += command
        failure?.let { throw it }
        checkpoints.checkpoint(CaptureCheckpoint("FAKE", AT, operationRequests = 3, cumulativeRequests = 5))
        return when (command) {
            is CaptureStepCommand.Inspect -> CaptureStepResult.Inspected(inspect(command.paths))
            else -> throw CaptureValidationException("UNSUPPORTED_IN_FAKE")
        }
    }

    override fun inspect(paths: CapturePaths): CaptureInspection { inspections++; return inspection }

    companion object {
        val AT: Instant = Instant.parse("2026-10-10T09:00:00Z")
        private val notReady = CaptureStageSummary(CaptureStageState.NOT_STARTED, "WALLET_CAPTURE_NOT_READY")
        fun inspection(wallet: CaptureStageSummary, market: CaptureStageSummary = notReady, rates: CaptureStageSummary = notReady) =
            CaptureInspection(wallet, market, rates, canCalibrate = false, canExport = false)
    }
}

class CaptureConsoleFixture(root: Path, rpcConfigured: Boolean = true, ratesConfigured: Boolean = true) {
    val json: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    val clock: Clock = Clock.fixed(FakeCaptureWorkflow.AT, ZoneOffset.UTC)
    val workflow = FakeCaptureWorkflow()
    val executor = ManualExecutor()
    val store = CaptureFileStore(CapturePathPolicy(root), json).also { it.initialize() }
    private var nextId = 0
    val operations = CaptureOperationService(store, workflow, CaptureResponseMapperImpl(), json, clock, executor, rpcConfigured, ratesConfigured)
    val sessions = CaptureSessionService(store, operations, clock) { "id-${++nextId}" }

    /** Creates a wallet session and runs its automatic offline inspection, leaving the worker idle and the log empty. */
    fun create(requestId: String = "session-1"): CaptureSessionView {
        sessions.create(CaptureCreateRequest(requestId, "Sample", wallet = CaptureWalletSettings(WALLET)))
        settle()
        return sessions.find(requestId)
    }

    /** Runs queued work and forgets the commands it recorded, so a test sees only what it triggers itself. */
    fun settle() { executor.runAll(); workflow.executed.clear(); workflow.inspections = 0 }

    companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
