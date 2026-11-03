package com.trenching.bot.console.automation.application

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.common.PageResult
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.application.ConsoleAddresses
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationItemDetail
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationSessionDetail
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.AutomationSessionRow
import com.trenching.bot.console.automation.domain.AutomationSessionSort
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStatus
import com.trenching.bot.console.automation.domain.AutomationSweepRunRow
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.EvaluationRanking
import com.trenching.bot.console.automation.domain.EvaluationSort
import com.trenching.bot.console.automation.domain.EvaluationStatus
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SessionHealth
import com.trenching.bot.console.automation.domain.SweepOutcomes
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemStateCountRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
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
 * The automation read API. A session page reads its item counts, its requests and its best evaluations with one grouped query
 * each, never one per row, and a sweep run page reads its buys with one query. A session's evaluations and best status are
 * those of the active sweep plan. Every page holds at most [MAX_PAGE_SIZE] rows.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(readOnly = true)
class AutomationQueryService(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val evaluations: AutomationEvaluationRepository,
    private val watermarks: AutomationWatermarkRepository,
    private val mapper: AutomationRecordMapper,
    private val properties: AutomationProperties,
    private val runtime: AutomationRuntime,
    private val usage: ApiUsageReports,
    private val rpc: SolanaRpcProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    fun status(): AutomationStatus {
        val heartbeat = runtime.heartbeat()
        val wallet = properties.wallet
        return AutomationStatus(
            enabled = properties.enabled,
            captureEnabled = properties.captureEnabled,
            wallet = wallet,
            sweepPlanId = properties.sweepPlan,
            zone = properties.zone,
            pollFrom = properties.pollFrom,
            pollTo = properties.pollTo,
            inPollWindow = properties.pollWindow().contains(clock.instant()),
            watermark = wallet?.let(watermarks::findOneByWallet)?.takeIf { mapper.toCursor(it) != DiscoveryCursor.ORIGIN }?.let(mapper::toWatermark),
            requestsLast24h = usage.report(ApiUsagePeriod.LAST_24_HOURS, null).totals.requests,
            maxRequestsPer24h = properties.maxRequestsPer24h,
            rpcConfigured = !rpc.rpcUrl.isNullOrBlank(),
            lastTickAt = heartbeat.lastTickAt,
            nextTickAt = heartbeat.lastTickAt?.takeIf { properties.enabled }?.plus(properties.tickInterval),
            lastDiscoveryAt = heartbeat.lastDiscoveryAt,
            discoveryProblem = heartbeat.discoveryProblem,
            openSessionId = wallet?.let { sessions.findFirstByWalletAndStateOrderByLastBuyAtDesc(it, AutomationSessionState.OPEN) }?.id,
        )
    }

    /**
     * [from] (inclusive) and [to] (exclusive) bound the first buy time; [status] keeps sessions whose best evaluation has it;
     * [health] keeps sessions with capture issues (`ISSUES`) or without any (`OK`).
     */
    fun sessions(page: Int, size: Int, from: Instant?, to: Instant?, status: String?, health: String?, sort: String): PageResult<AutomationSessionRow> {
        val pageable = pageable(page, size)
        val order = AutomationSessionSort.of(sort)
            ?: throw InvalidAutomationRequestException(SORT, "must be one of ${AutomationSessionSort.entries.map { it.value }}")
        val wanted = status?.let(::evaluationStatus)
        val better = wanted?.let { EvaluationStatus.entries.filter { other -> other.rank < it.rank } }.orEmpty()
        val issues = health?.let(::sessionHealth)?.let { it == SessionHealth.ISSUES }
        val plan = properties.sweepPlan
        val found = when (order) {
            AutomationSessionSort.STATUS ->
                sessions.pageByBestEvaluation(from, to, plan, wanted, better, issues, SessionHealth.ISSUE_STATES, pageable)
            AutomationSessionSort.CREATED_DESC ->
                sessions.pageNewestFirst(from, to, plan, wanted, better, issues, SessionHealth.ISSUE_STATES, pageable)
        }
        return PageResult(rows(found.content), page, pageable.pageSize, found.totalElements)
    }

    fun session(id: String): AutomationSessionDetail {
        val session = sessions.findOneById(id) ?: throw AutomationSessionNotFoundException(id)
        val sessionItems = items.findAllBySessionIdOrderByBlockTimeAscIdAsc(id, PageRequest.of(0, AutomationLimits.MAX_ITEMS_PER_SESSION)).map(mapper::toItem)
        val scores = evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc(id, properties.sweepPlan).map(mapper::toEvaluation)
        return AutomationSessionDetail(rows(listOf(session)).single(), sessionItems, scores)
    }

    /** One item with its sweep runs, which a plan has at most [MAX_SWEEP_RUNS] of. */
    fun item(id: String): AutomationItemDetail {
        val item = items.findOneById(id) ?: throw AutomationItemNotFoundException(id)
        val runs = sweepRuns.findAllByItemIdOrderByPlanIdAscLabelAsc(id, PageRequest.of(0, MAX_SWEEP_RUNS)).map(mapper::toSweepRun)
        return AutomationItemDetail(mapper.toItem(item), runs)
    }

    /**
     * The scoreboard: the evaluations of every plan unless [planId] is given, filtered by [label] and [status], with [from]
     * (inclusive) and [to] (exclusive) bounding the evaluated session's first buy.
     */
    fun evaluations(
        planId: String?,
        label: String?,
        status: String?,
        from: Instant?,
        to: Instant?,
        sort: String,
        page: Int,
        size: Int,
    ): PageResult<AutomationEvaluationView> {
        val pageable = pageable(page, size)
        val order = EvaluationSort.of(sort) ?: throw InvalidAutomationRequestException(SORT, "must be one of ${EvaluationSort.entries.map { it.value }}")
        val wanted = status?.let(::evaluationStatus)
        val found = when (order) {
            EvaluationSort.STATUS -> evaluations.pageByStatus(planId, label, wanted, from, to, pageable)
            EvaluationSort.EVALUATED_DESC -> evaluations.pageNewestFirst(planId, label, wanted, from, to, pageable)
        }
        return PageResult(found.content.map(mapper::toEvaluation), page, pageable.pageSize, found.totalElements)
    }

    /** The sweep runs of every plan, newest buy first, filtered by [sessionId], the buy's [mint], [label] and [outcome]. */
    fun sweepRuns(sessionId: String?, mint: String?, label: String?, outcome: String?, page: Int, size: Int): PageResult<AutomationSweepRunRow> {
        val pageable = pageable(page, size)
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

    private fun rows(page: List<AutomationSessionEntity>): List<AutomationSessionRow> {
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.id }
        val counts = items.countBySessionAndState(ids).groupBy { it.sessionId }
        val requests = items.sumRequestsBySession(ids).associate { it.sessionId to it.requests }
        val best = evaluations.findAllBySessionIdInAndPlanId(ids, properties.sweepPlan)
            .groupBy({ it.sessionId }, mapper::toScore)
            .mapValues { (_, scores) -> EvaluationRanking.best(scores) }
        return page.map { session ->
            val top = best[session.id]
            AutomationSessionRow(mapper.toSession(session), stateCounts(counts[session.id].orEmpty()), requests[session.id] ?: 0L, top?.status, top?.label)
        }
    }

    private fun stateCounts(rows: List<AutomationItemStateCountRow>): Map<AutomationItemState, Long> {
        val byState = rows.associate { it.state to it.items }
        return AutomationItemState.entries.associateWith { byState[it] ?: 0L }
    }

    private fun pageable(page: Int, size: Int): PageRequest {
        if (page < 0) throw InvalidAutomationRequestException(PAGE, "must not be negative")
        if (size <= 0) throw InvalidAutomationRequestException(SIZE, "must be positive")
        return PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE))
    }

    private fun evaluationStatus(value: String): EvaluationStatus =
        EvaluationStatus.entries.firstOrNull { it.name == value }
            ?: throw InvalidAutomationRequestException(STATUS, "must be one of ${EvaluationStatus.entries}")

    private fun sessionHealth(value: String): SessionHealth =
        SessionHealth.entries.firstOrNull { it.name == value }
            ?: throw InvalidAutomationRequestException(HEALTH, "must be one of ${SessionHealth.entries}")

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_SWEEP_RUNS = 100
        const val PAGE = "page"
        const val SIZE = "size"
        const val SORT = "sort"
        const val STATUS = "status"
        const val HEALTH = "health"
        const val MINT = "mint"
        const val OUTCOME = "outcome"
    }
}
