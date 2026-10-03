package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ObservationResponse
import com.trenching.bot.console.api.dto.OrderResponse
import com.trenching.bot.console.api.dto.PositionResponse
import com.trenching.bot.console.api.dto.RunDetailResponse
import com.trenching.bot.console.api.dto.RunEventResponse
import com.trenching.bot.console.api.dto.RunPageResponse
import com.trenching.bot.console.api.dto.RunStartRequest
import com.trenching.bot.console.api.dto.RunStartResponse
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.application.RunLaunchService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.ObservationResponseMapper
import com.trenching.bot.console.mapper.RunResponseMapper
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/runs")
@ConditionalOnConsoleEnabled
class RunController(
    private val runLaunchService: RunLaunchService,
    private val consoleService: ConsoleService,
    private val runResponseMapper: RunResponseMapper,
    private val observationResponseMapper: ObservationResponseMapper,
) {
    @PostMapping
    fun start(@RequestBody request: RunStartRequest): ResponseEntity<RunStartResponse> {
        val start = runLaunchService.start(request)
        return ConsoleResponses.createdOrExisting(start.created, runResponseMapper.toResponse(start))
    }

    @GetMapping
    fun page(
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
        @RequestParam(required = false) datasetId: String?,
    ): RunPageResponse = runResponseMapper.toPage(consoleService.page(page, size, datasetId))

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): RunDetailResponse = runResponseMapper.toResponse(consoleService.detail(id))

    @GetMapping("/{id}/events")
    fun events(@PathVariable id: String): List<RunEventResponse> = runResponseMapper.toEvents(consoleService.events(id))

    @GetMapping("/{id}/observations")
    fun observations(@PathVariable id: String): List<ObservationResponse> =
        observationResponseMapper.toResponses(consoleService.observations(id))

    @GetMapping("/{id}/orders")
    fun orders(@PathVariable id: String): List<OrderResponse> = runResponseMapper.toOrders(consoleService.orders(id))

    @GetMapping("/{id}/positions")
    fun positions(@PathVariable id: String): List<PositionResponse> =
        runResponseMapper.toPositions(consoleService.positions(id))

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: String): ResponseEntity<String> {
        val report = consoleService.report(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-$id.json").build().toString())
            .body(report)
    }
}
