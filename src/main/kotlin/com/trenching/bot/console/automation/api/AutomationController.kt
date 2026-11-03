package com.trenching.bot.console.automation.api

import com.trenching.bot.console.api.ConsoleResponses
import com.trenching.bot.console.automation.api.dto.AutomationBackfillCreateRequest
import com.trenching.bot.console.automation.api.dto.AutomationBackfillPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationBackfillResponse
import com.trenching.bot.console.automation.api.dto.AutomationCloseRequest
import com.trenching.bot.console.automation.api.dto.AutomationDayDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationDayListResponse
import com.trenching.bot.console.automation.api.dto.AutomationEvaluationPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationItemDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationManualRunResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationStatusResponse
import com.trenching.bot.console.automation.api.dto.AutomationSweepRunPageResponse
import com.trenching.bot.console.automation.application.AutomationBackfillQueryService
import com.trenching.bot.console.automation.application.AutomationBackfillService
import com.trenching.bot.console.automation.application.AutomationDayService
import com.trenching.bot.console.automation.application.AutomationManualRun
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationSessionSort
import com.trenching.bot.console.automation.domain.EvaluationSort
import com.trenching.bot.console.automation.mapper.AutomationBackfillRequestMapper
import com.trenching.bot.console.automation.mapper.AutomationResponseMapper
import java.time.Instant
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/automation")
@ConditionalOnAutomationConsole
class AutomationController(
    private val query: AutomationQueryService,
    private val days: AutomationDayService,
    private val sessions: AutomationSessionService,
    private val manualRun: AutomationManualRun,
    private val backfills: AutomationBackfillService,
    private val backfillQuery: AutomationBackfillQueryService,
    private val mapper: AutomationResponseMapper,
    private val backfillRequests: AutomationBackfillRequestMapper,
) {
    @GetMapping("/status")
    fun status(): AutomationStatusResponse = mapper.toStatus(query.status())

    @PostMapping("/discovery/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun runDiscovery(): AutomationManualRunResponse = mapper.toManualRun(manualRun.request())

    @GetMapping("/sessions")
    fun sessions(
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) health: String?,
        @RequestParam(defaultValue = AutomationSessionSort.DEFAULT_VALUE) sort: String,
    ): AutomationSessionPageResponse = mapper.toPage(query.sessions(page, size, from, to, health, sort))

    @GetMapping("/sessions/{id}")
    fun session(@PathVariable id: String): AutomationSessionDetailResponse = mapper.toDetail(query.session(id))

    @GetMapping("/items/{id}")
    fun item(@PathVariable id: String): AutomationItemDetailResponse = mapper.toItemDetail(query.item(id))

    @GetMapping("/days")
    fun days(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): AutomationDayListResponse = mapper.toDayList(days.days(from, to))

    @GetMapping("/days/{date}")
    fun day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate): AutomationDayDetailResponse =
        mapper.toDayDetail(days.day(date))

    @GetMapping("/evaluations")
    fun evaluations(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = EvaluationSort.DEFAULT_VALUE) sort: String,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): AutomationEvaluationPageResponse = mapper.toEvaluationPage(days.evaluations(from, to, label, status, sort, page, size))

    @GetMapping("/sweep-runs")
    fun sweepRuns(
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(required = false) mint: String?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) outcome: String?,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): AutomationSweepRunPageResponse = mapper.toSweepRunPage(query.sweepRuns(sessionId, mint, label, outcome, page, size))

    @PostMapping("/backfills")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBackfill(@RequestBody request: AutomationBackfillCreateRequest): AutomationBackfillResponse =
        mapper.toBackfill(backfills.create(backfillRequests.toRequest(request)))

    @GetMapping("/backfills")
    fun backfills(
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): AutomationBackfillPageResponse = mapper.toBackfillPage(backfillQuery.list(page, size))

    @GetMapping("/backfills/{id}")
    fun backfill(@PathVariable id: String): AutomationBackfillResponse = mapper.toBackfill(backfillQuery.get(id))

    @PostMapping("/backfills/{id}/cancel")
    fun cancelBackfill(@PathVariable id: String, @RequestBody request: AutomationCloseRequest): AutomationBackfillResponse =
        mapper.toBackfill(backfills.cancel(id, request.requestId))

    @PostMapping("/sessions/{id}/close")
    fun close(@PathVariable id: String, @RequestBody request: AutomationCloseRequest): AutomationSessionDetailResponse =
        mapper.toDetail(sessions.close(id, request.requestId))
}
