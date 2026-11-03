package com.trenching.bot.console.automation.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.CoverageRanges
import com.trenching.bot.console.automation.domain.DayCoverage
import com.trenching.bot.console.automation.domain.DayDetail
import com.trenching.bot.console.automation.domain.DayFacts
import com.trenching.bot.console.automation.domain.DayList
import com.trenching.bot.console.automation.domain.DayScoring
import com.trenching.bot.console.automation.domain.DayStanding
import com.trenching.bot.console.automation.domain.DayStandings
import com.trenching.bot.console.automation.domain.DaySummary
import com.trenching.bot.console.automation.domain.DayVerdict
import com.trenching.bot.console.automation.domain.EvaluationSort
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.TimeRange
import com.trenching.bot.console.automation.domain.TradingDay
import com.trenching.bot.console.automation.domain.WindowItem
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemStateRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The trading-day read API (plan Key Decisions 3–5). Nothing here is stored: every call reads the sessions, items and sweep
 * rows of the days it needs, and of the earlier days of their verdict windows, and derives each day's completeness and rows from
 * them with [DayScoring], so a day filled in later changes the rows of the days after it. A list covers at most
 * [AutomationLimits.MAX_DAYS_PER_LIST] days; the rows of the span's sessions, items and sweep runs are read with one query each
 * (never one per item), bounded by the days read and cut off nowhere, since a verdict must never rest on a truncated day. Scoring uses the active sweep plan and `evaluation-window-sessions`
 * trading days.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(readOnly = true)
class AutomationDayService(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
    private val coverage: AutomationCoverage,
    private val sessionRows: AutomationSessionRows,
    private val mapper: AutomationRecordMapper,
    private val properties: AutomationProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val calendar = properties.tradingCalendar()
    private val scoring = DayScoring(properties.activePlan(), properties.evaluationWindowSessions, properties.evaluationRule())

    /** The days from [from] to [to] (trading dates, inclusive), newest first; by default the 14 days ending with today's. */
    fun days(from: LocalDate?, to: LocalDate?): DayList {
        val (first, last) = span(from, to)
        val data = load(first, last)
        return DayList(first.date, last.date, newestFirst(first, last).map { summary(it, data) })
    }

    fun day(date: LocalDate): DayDetail {
        val day = TradingDay(date)
        val data = load(day, day)
        val entities = data.sessionsByDay[day].orEmpty()
        val ids = entities.map { it.id }
        val dayItems = if (ids.isEmpty()) emptyList() else {
            items.findAllBySessionIdIn(ids, PageRequest.of(0, ids.size * AutomationLimits.MAX_ITEMS_PER_SESSION)).map(mapper::toItem)
        }
        return DayDetail(summary(day, data), DayCoverage(data.facts.getValue(day).covered, data.coverage.ranges), sessionRows.of(entities), dayItems,
            scoring.verdicts(day, data.facts))
    }

    /**
     * The rows of the days from [from] to [to] (default as in [days]), one per entry of the active plan, filtered by [label] and
     * [status] (a verdict or `PENDING`) and ordered by [sort].
     */
    fun evaluations(from: LocalDate?, to: LocalDate?, label: String?, status: String?, sort: String, page: Int, size: Int): PageResult<DayVerdict> {
        val pageable = AutomationPaging.pageable(page, size)
        val order = EvaluationSort.of(sort) ?: throw InvalidAutomationRequestException(SORT, "must be one of ${EvaluationSort.entries.map { it.value }}")
        if (status != null && status !in DayStanding.LABELS) throw InvalidAutomationRequestException(STATUS, "must be one of ${DayStanding.LABELS}")
        val (first, last) = span(from, to)
        val data = load(first, last)
        val rows = first.through(last).flatMap { scoring.verdicts(it, data.facts) }
            .filter { (label == null || it.label == label) && (status == null || it.status == status) }
            .sortedWith(comparator(order))
        val start = pageable.offset.coerceAtMost(rows.size.toLong()).toInt()
        return PageResult(rows.subList(start, (start + pageable.pageSize).coerceAtMost(rows.size)), page, pageable.pageSize, rows.size.toLong())
    }

    private fun comparator(order: EvaluationSort): Comparator<DayVerdict> {
        val byStatus = compareBy<DayVerdict> { it.standing.rank }.thenByDescending { it.tally.worstCasePnl }
        return when (order) {
            EvaluationSort.STATUS -> byStatus.thenByDescending { it.day }.thenBy { it.label }
            EvaluationSort.DAY_DESC -> compareByDescending<DayVerdict> { it.day }.then(byStatus).thenBy { it.label }
        }
    }

    private fun newestFirst(first: TradingDay, last: TradingDay): List<TradingDay> = first.through(last).asReversed()

    /** The span asked for, defaulting to the last [DEFAULT_DAYS] days ending with the trading day of the clock; at most 62 days. */
    private fun span(from: LocalDate?, to: LocalDate?): Pair<TradingDay, TradingDay> {
        val last = to?.let(::TradingDay) ?: calendar.dayOf(clock.instant())
        val first = from?.let(::TradingDay) ?: last.minusDays(DEFAULT_DAYS - 1L)
        if (first > last) throw InvalidAutomationRequestException(FROM, "must not be after to")
        if (ChronoUnit.DAYS.between(first.date, last.date) >= AutomationLimits.MAX_DAYS_PER_LIST) {
            throw InvalidAutomationRequestException(TO, "must be within ${AutomationLimits.MAX_DAYS_PER_LIST} days of from")
        }
        return first to last
    }

    private fun summary(day: TradingDay, data: DayData): DaySummary {
        val facts = data.facts.getValue(day)
        val verdicts = scoring.verdicts(day, data.facts)
        val best = DayStandings.best(verdicts)
        val ofDay = data.sessionsByDay[day].orEmpty()
        val states = ofDay.flatMap { data.itemsBySession[it.id].orEmpty() }.groupingBy { it.state }.eachCount()
        val range = calendar.range(day)
        return DaySummary(
            day = day.date, from = range.from, to = range.to, covered = facts.covered, complete = facts.complete,
            sessions = ofDay.size, buys = states.values.sum(), belowFilter = ofDay.sumOf { it.belowFilterCount },
            captured = (states[AutomationItemState.CAPTURED] ?: 0) + (states[AutomationItemState.SIMULATED] ?: 0),
            simulated = states[AutomationItemState.SIMULATED] ?: 0, failed = states[AutomationItemState.FAILED] ?: 0,
            abandoned = states[AutomationItemState.ABANDONED] ?: 0, requestsUsed = ofDay.sumOf { data.requestsBySession[it.id] ?: 0L },
            bestStatus = best?.status, bestLabel = best?.takeIf { it.standing is DayStanding.Decided }?.label, pendingDay = best?.pendingDay,
        )
    }

    /** Everything stored for the days from [first] through [last] and the earlier days their windows reach. */
    private fun load(first: TradingDay, last: TradingDay): DayData {
        val wallet = properties.wallet
        val loadFrom = scoring.windowStart(first)
        val days = loadFrom.through(last)
        val range = TimeRange(calendar.range(loadFrom).from, calendar.range(last).to)
        val stored = wallet?.let { sessions.findStartedIn(it, range.from, range.to) }.orEmpty()
        val ids = stored.map { it.id }
        val itemRows = if (ids.isEmpty()) emptyList() else items.findStatesBySessionIdIn(ids)
        val runsByItem = if (ids.isEmpty()) emptyMap() else {
            sweepRuns.findAllBySessionIdInAndPlanId(ids, properties.sweepPlan).map(mapper::toSweepScore).groupBy { it.itemId }
        }
        val requests = if (ids.isEmpty()) emptyMap() else items.sumRequestsBySession(ids).associate { it.sessionId to it.requests }
        val covered = wallet?.let(coverage::of) ?: CoverageRanges.NONE
        val sessionsByDay = stored.groupBy { calendar.dayOf(it.firstBuyAt) }
        val itemsBySession = itemRows.groupBy { it.sessionId }
        val facts = days.associateWith { day ->
            val ofDay = sessionsByDay[day].orEmpty()
            val windowItems = ofDay.flatMap { session ->
                itemsBySession[session.id].orEmpty().map {
                    WindowItem(it.id, it.state, runsByItem[it.id].orEmpty().associateBy { run -> run.label }, it.failureCode == AutomationFailures.BACKFILL_BUDGET)
                }
            }
            DayFacts(day, covered.covers(calendar.range(day)), ofDay.map { it.state }, windowItems)
        }
        return DayData(sessionsByDay, itemsBySession, requests, covered, facts)
    }

    private class DayData(
        val sessionsByDay: Map<TradingDay, List<AutomationSessionEntity>>,
        val itemsBySession: Map<String, List<AutomationItemStateRow>>,
        val requestsBySession: Map<String, Long>,
        val coverage: CoverageRanges,
        val facts: Map<TradingDay, DayFacts>,
    )

    private companion object {
        const val DEFAULT_DAYS = 14
        const val SORT = "sort"
        const val STATUS = "status"
        const val FROM = "from"
        const val TO = "to"
    }
}
