package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationDisabledException
import com.trenching.bot.console.automation.domain.AutomationBackfillRow
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationRequests
import com.trenching.bot.console.automation.domain.BackfillActiveException
import com.trenching.bot.console.automation.domain.BackfillRequest
import com.trenching.bot.console.automation.domain.CaptureDisabledException
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Creating, settling and cancelling the wallet's backfills (plan Rules 35, 36, 39, 41 and 43). A create is idempotent: its id
 * is derived from the wallet and the request id, so a repeated request returns the stored backfill, whatever it has become
 * since and whatever else the repeat says, also while automation is off (plan Rule 43). Nothing new is created while it is off,
 * since nothing would scan it.
 *
 * A backfill's range, filters and budget are checked before anything is stored ([BackfillRules]); what the wallet's stored
 * state decides (an active backfill, live coverage, other backfills) is checked in [AutomationBackfillTransaction]. A cancel
 * cancels the capture operations its abandoned items had started once its transaction has committed.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationBackfillService(
    private val transaction: AutomationBackfillTransaction,
    private val query: AutomationBackfillQueryService,
    private val cancellation: AutomationCaptureCancellation,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationBackfillService::class.java)

    fun create(request: BackfillRequest): AutomationBackfillRow {
        val requestId = AutomationRequests.requireRequestId(request.requestId)
        properties.wallet?.let { AutomationIds.backfill(it, requestId) }?.takeIf(transaction::exists)?.let { return query.get(it) }
        if (!properties.enabled) throw AutomationDisabledException()
        val wallet = properties.walletAddress().value
        val id = AutomationIds.backfill(wallet, requestId)
        val now = clock.instant()
        val backfill = properties.backfillRules().validate(request, now)
        if (!backfill.dryRun && !properties.captureEnabled) throw CaptureDisabledException()
        try {
            transaction.create(wallet, backfill, now)
        } catch (e: DataIntegrityViolationException) {
            if (!transaction.exists(id)) throw BackfillActiveException(wallet, transaction.activeId(wallet))
            log.info("automation backfill create repeated while the first was stored backfillId={} requestId={}", id, requestId)
        }
        return query.get(id)
    }

    /** Ends every capturing backfill whose items are all terminal. */
    fun settle() {
        transaction.settle(clock.instant())
    }

    /** Cancels the backfill; one that already ended is returned unchanged, so a repeated cancel is harmless. */
    fun cancel(id: String, requestId: String?): AutomationBackfillRow {
        val validRequestId = AutomationRequests.requireRequestId(requestId)
        log.info("automation backfill cancel requested backfillId={} requestId={}", id, validRequestId)
        transaction.cancel(id, clock.instant()).forEach(cancellation::cancel)
        return query.get(id)
    }
}
