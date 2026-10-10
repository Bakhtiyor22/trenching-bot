package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.capture.CaptureNames.WALLET
import com.trenching.bot.console.capture.CaptureNames.MARKET
import com.trenching.bot.console.capture.CaptureNames.RATES
import com.trenching.bot.console.capture.CaptureNames.INSPECT
import java.time.Clock
import org.slf4j.LoggerFactory

class CaptureSessionService(private val store: CaptureFileStore, private val operations: CaptureOperationService, private val clock: Clock, private val ids: CaptureIds) {
    private val log = LoggerFactory.getLogger(CaptureSessionService::class.java)
    fun create(request: CaptureCreateRequest): CaptureSessionView = synchronized(store) {
        store.id(request.requestId)
        val normalized = request.copy(name = request.name.trim())
        val fingerprint = store.fingerprint(normalized)
        store.sessionOrNull(request.requestId)?.let { existing ->
            if (existing.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is already bound to different settings")
            return@synchronized view(existing)
        }
        if (normalized.name.isBlank() || normalized.name.length > 120) CaptureValidation.invalid("name", "must contain 1 to 120 characters")
        if ((request.wallet == null) == (request.attach == null)) CaptureValidation.invalid("wallet", "provide wallet settings or attached paths")
        request.wallet?.let(CaptureValidation::wallet)
        request.attach?.let { attach ->
            val paths = listOfNotNull(attach.wallet, attach.market, attach.rates)
            store.paths.independent(paths)
            if (paths.any { it == "console" || it.startsWith("console/") }) CaptureValidation.invalid("attach", "managed console directories cannot be attached")
            if (attach.rates != null && attach.market == null) CaptureValidation.invalid("attach", "rates require a market directory")
            operations.requireIdle()
        }
        val id = request.requestId
        val at = clock.instant()
        val walletId = ids.next()
        val marketId = if (request.attach == null || request.attach.market != null) ids.next() else null
        val ratesId = if (request.attach == null || request.attach.rates != null) ids.next() else null
        store.save(id, CaptureAttemptRecord(walletId, request.requestId, fingerprint, WALLET, at, request.attach?.wallet ?: "console/$id/wallet", request.attach != null, null, walletId, null), true)
        marketId?.let { store.save(id, CaptureAttemptRecord(it, request.requestId, fingerprint, MARKET, at, request.attach?.market ?: "console/$id/markets/$it", request.attach?.market != null, CaptureMarketSettings(), walletId, null), true) }
        ratesId?.let { store.save(id, CaptureAttemptRecord(it, request.requestId, fingerprint, RATES, at, request.attach?.rates ?: "console/$id/rates/$it", request.attach?.rates != null, null, walletId, marketId), true) }
        val record = CaptureSessionRecord(id = id, requestId = request.requestId, fingerprint = fingerprint, name = normalized.name, createdAt = at, wallet = request.wallet, walletAttemptId = walletId, marketAttemptId = marketId, ratesAttemptId = ratesId)
        store.save(record, true)
        if (request.attach != null) operations.start(id, CaptureOperationRequest(ids.next(), INSPECT, marketId))
        view(store.session(id))
    }
    fun newAttempt(id: String, request: CaptureAttemptRequest): CaptureSessionView = synchronized(store) {
        val session = store.session(id)
        val fingerprint = store.fingerprint(request)
        store.attemptOrNull(id, request.requestId)?.let {
            if (it.fingerprint != fingerprint) CaptureValidation.conflict("REQUEST_CONFLICT", "Request id is bound to different attempt settings")
            return@synchronized view(session)
        }
        operations.requireIdle()
        if (request.stage !in setOf(MARKET, RATES)) CaptureValidation.invalid("stage", "must be MARKET or RATES")
        val settings = if (request.stage == MARKET) request.market ?: CaptureMarketSettings() else null
        settings?.let(CaptureValidation::market)
        if (request.stage == RATES && session.marketAttemptId == null) CaptureValidation.conflict("PREREQUISITE", "Select a market attempt first")
        val attempt = CaptureAttemptRecord(store.id(request.requestId), request.requestId, fingerprint, request.stage, clock.instant(), "console/$id/${if (request.stage == MARKET) "markets" else "rates"}/${request.requestId}", false, settings, session.walletAttemptId, if (request.stage == RATES) session.marketAttemptId else null)
        store.save(id, attempt, true)
        val updated = if (request.stage == MARKET) session.copy(marketAttemptId = attempt.id, ratesAttemptId = null, inspection = null, exportOperationId = null, calibrationOperationId = null, datasetId = null) else session.copy(ratesAttemptId = attempt.id, inspection = null, exportOperationId = null, datasetId = null)
        store.save(updated)
        view(updated)
    }
    /** Deletes the session and its managed files; attached evidence and imported datasets stay. */
    fun delete(id: String) = synchronized(store) {
        store.session(id)
        operations.requireNotRunning(id)
        val files = store.remove(id, ids.next())
        log.info("capture_deleted sessionId={} managedFiles={}", id, files)
    }
    fun find(id: String): CaptureSessionView = synchronized(store) { view(store.session(id)) }
    fun page(page: Int, size: Int): CapturePage<CaptureSessionView> = synchronized(store) {
        paging(page,size)
        val all = store.sessions()
        CapturePage(all.drop(page * size).take(size).map { view(store.session(it)) }, page,size,all.size.toLong())
    }
    fun attempts(id: String, page: Int, size: Int): CapturePage<CaptureAttemptRecord> = synchronized(store) {
        paging(page,size); store.session(id)
        val all = store.attempts(id).sortedByDescending { it.createdAt }
        CapturePage(all.drop(page * size).take(size),page,size,all.size.toLong())
    }
    private fun view(record: CaptureSessionRecord): CaptureSessionView = CaptureSessionView(record.id,record.name,record.createdAt,record.wallet,record.walletAttemptId,record.marketAttemptId?.let { store.attempt(record.id,it) },record.ratesAttemptId?.let { store.attempt(record.id,it) },record.inspection,record.operationId?.let { store.operation(record.id,it) }, record.exportOperationId != null,record.calibrationOperationId != null,record.datasetId,record.calibrationOperationId?.let { store.operation(record.id,it).calibration })
    private fun paging(page: Int, size: Int) { if (page !in 0..100000 || size !in 1..100) CaptureValidation.invalid("page", "page must be nonnegative and size between 1 and 100") }
}

