package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.common.PageResult
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.application.ConsoleAddresses
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemDetail
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationSessionDetail
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.AutomationSessionRow
import com.trenching.bot.console.automation.domain.AutomationSessionSort
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStatus
import com.trenching.bot.console.automation.domain.AutomationSweepRunRow
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SessionHealth
import com.trenching.bot.console.automation.domain.SweepOutcomes
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.console.config.SolanaRpcProperties
import java.time.Clock
import java.time.Instant
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The automation read API for state, sessions, items and sweep runs ([AutomationDayService] reads days and their verdicts). A
 * session page reads its item counts and requests with one grouped query each ([AutomationSessionRows]), never one per row, and
 * a sweep run page reads its buys with one query. Every page holds at most [AutomationPaging.MAX_PAGE_SIZE] rows.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(readOnly = true)
class AutomationQueryService(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val watermarks: AutomationWatermarkRepository,
    private val backfills: AutomationBackfillRepository,
    private val mapper: AutomationRecordMapper,
    private val sessionRows: AutomationSessionRows,
    private val properties: AutomationProperties,
    private val runtime: AutomationRuntime,
    private val usage: ApiUsageReports,
    private val rpc: SolanaRpcProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    fun status(): AutomationStatus {
        val heartbeat = runtime.heartbeat()
        val wallet = properties.wallet
        val stored = wallet?.let(watermarks::findOneByWallet)
        val mode = properties.discoveryMode()
        return AutomationStatus(
            enabled = properties.enabled,
            captureEnabled = properties.captureEnabled,
            wallet = wallet,
            sweepPlanId = properties.sweepPlan,
            zone = properties.zone,
            pollFrom = properties.pollFrom,
            pollTo = properties.pollTo,
            mode = mode,
            inPollWindow = mode.scansAt(clock.instant()),
            manualRunQueued = runtime.manualRunQueued(),
            tradingDayStart = properties.tradingDayStart,
            coveredFrom = stored?.coveredFrom,
            watermark = stored?.takeIf { mapper.toCursor(it) != DiscoveryCursor.ORIGIN }?.let(mapper::toWatermark),
            requestsLast24h = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests,
            maxRequestsPer24h = properties.maxRequestsPer24h,
            rpcConfigured = !rpc.rpcUrl.isNullOrBlank(),
            lastTickAt = heartbeat.lastTickAt,
            nextTickAt = heartbeat.lastTickAt?.takeIf { properties.enabled }?.plus(properties.tickInterval),
            lastDiscoveryAt = heartbeat.lastDiscoveryAt,
            discoveryProblem = heartbeat.discoveryProblem,
            openSessionId = wallet?.let { sessions.findFirstByWalletAndBackfillIdIsNullAndStateOrderByLastBuyAtDesc(it, AutomationSessionState.OPEN) }?.id,
            activeBackfillId = wallet?.let(backfills::findOneByActiveWallet)?.id,
        )
    }

    /** [from] (inclusive) and [to] (exclusive) bound the first buy time; [health] keeps sessions with capture issues (`ISSUES`) or without any (`OK`). */
    fun sessions(page: Int, size: Int, from: Instant?, to: Instant?, health: String?, sort: String): PageResult<AutomationSessionRow> {
        val pageable = AutomationPaging.pageable(page, size)
        AutomationSessionSort.of(sort) ?: throw InvalidAutomationRequestException(SORT, "must be one of ${AutomationSessionSort.entries.map { it.value }}")
        val issues = health?.let(::sessionHealth)?.let { it == SessionHealth.ISSUES }
        val found = sessions.pageNewestFirst(from, to, properties.sweepPlan, issues, SessionHealth.ISSUE_STATES, pageable)
        return PageResult(sessionRows.of(found.content), page, pageable.pageSize, found.totalElements)
    }

    fun session(id: String): AutomationSessionDetail {
        val session = sessions.findOneById(id) ?: throw AutomationSessionNotFoundException(id)
        val sessionItems = items.findAllBySessionIdOrderByBlockTimeAscIdAsc(id, PageRequest.of(0, AutomationLimits.MAX_ITEMS_PER_SESSION)).map(mapper::toItem)
        return AutomationSessionDetail(sessionRows.of(listOf(session)).single(), sessionItems)
    }

    /** One item with its sweep runs, which a plan has at most [MAX_SWEEP_RUNS] of. */
    fun item(id: String): AutomationItemDetail {
        val item = items.findOneById(id) ?: throw AutomationItemNotFoundException(id)
        val runs = sweepRuns.findAllByItemIdOrderByPlanIdAscLabelAsc(id, PageRequest.of(0, MAX_SWEEP_RUNS)).map(mapper::toSweepRun)
        return AutomationItemDetail(mapper.toItem(item), runs)
    }

    /** The sweep runs of every plan, newest buy first, filtered by [sessionId], the buy's [mint], [label] and [outcome]. */
    fun sweepRuns(sessionId: String?, mint: String?, label: String?, outcome: String?, page: Int, size: Int): PageResult<AutomationSweepRunRow> {
        val pageable = AutomationPaging.pageable(page, size)
        if (mint != null && ConsoleAddresses.mintOrNull(mint) == null) throw InvalidAutomationRequestException(MINT, "must be a base58 mint address")
        if (outcome != null && outcome !in SweepOutcomes.KNOWN) {
            throw InvalidAutomationRequestException(OUTCOME, "must be a close, skip or refusal reason")
        }
        val found = sweepRuns.page(sessionId, mint, label, outcome, pageable)
        val buys = items.findAllById(found.content.map { it.itemId }.distinct()).associateBy { it.id }
        val rows = found.content.map { run ->
            val buy = checkNotNull(buys[run.itemId]) { "Sweep run without its item sweepRunId=${run.id} itemId=${run.itemId}" }
            AutomationSweepRunRow(mapper.toSweepRun(run), buy.mint, buy.signature, buy.blockTime)
        }
        return PageResult(rows, page, pageable.pageSize, found.totalElements)
    }

    private fun sessionHealth(value: String): SessionHealth =
        SessionHealth.entries.firstOrNull { it.name == value }
            ?: throw InvalidAutomationRequestException(HEALTH, "must be one of ${SessionHealth.entries}")

    private companion object {
        const val MAX_SWEEP_RUNS = 100
        const val SORT = "sort"
        const val HEALTH = "health"
        const val MINT = "mint"
        const val OUTCOME = "outcome"
    }
}
