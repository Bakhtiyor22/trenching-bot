package com.trenching.bot.console.automation.api

import com.trenching.bot.console.api.ConsoleResponses
import com.trenching.bot.console.automation.api.dto.AutomationCloseRequest
import com.trenching.bot.console.automation.api.dto.AutomationEvaluationPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationItemDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationStatusResponse
import com.trenching.bot.console.automation.api.dto.AutomationSweepRunPageResponse
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationSessionSort
import com.trenching.bot.console.automation.domain.EvaluationSort
import com.trenching.bot.console.automation.mapper.AutomationResponseMapper
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/automation")
@ConditionalOnAutomationConsole
class AutomationController(
    private val query: AutomationQueryService,
    private val sessions: AutomationSessionService,
    private val mapper: AutomationResponseMapper,
) {
    @GetMapping("/status")
    fun status(): AutomationStatusResponse = mapper.toStatus(query.status())

    @GetMapping("/sessions")
    fun sessions(
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) health: String?,
        @RequestParam(defaultValue = AutomationSessionSort.DEFAULT_VALUE) sort: String,
    ): AutomationSessionPageResponse = mapper.toPage(query.sessions(page, size, from, to, status, health, sort))

    @GetMapping("/sessions/{id}")
    fun session(@PathVariable id: String): AutomationSessionDetailResponse = mapper.toDetail(query.session(id))

    @GetMapping("/items/{id}")
    fun item(@PathVariable id: String): AutomationItemDetailResponse = mapper.toItemDetail(query.item(id))

    @GetMapping("/evaluations")
    fun evaluations(
        @RequestParam(required = false) planId: String?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = EvaluationSort.DEFAULT_VALUE) sort: String,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): AutomationEvaluationPageResponse = mapper.toEvaluationPage(query.evaluations(planId, label, status, from, to, sort, page, size))

    @GetMapping("/sweep-runs")
    fun sweepRuns(
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(required = false) mint: String?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) outcome: String?,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): AutomationSweepRunPageResponse = mapper.toSweepRunPage(query.sweepRuns(sessionId, mint, label, outcome, page, size))

    @PostMapping("/sessions/{id}/close")
    fun close(@PathVariable id: String, @RequestBody request: AutomationCloseRequest): AutomationSessionDetailResponse =
        mapper.toDetail(sessions.close(id, request.requestId))
}
