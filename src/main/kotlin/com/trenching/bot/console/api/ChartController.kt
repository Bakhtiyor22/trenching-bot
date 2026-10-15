package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ChartResponse
import com.trenching.bot.console.application.ChartConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.mapper.ChartResponseMapper
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@ConditionalOnConsoleEnabled
class ChartController(
    private val chartConsoleService: ChartConsoleService,
    private val chartResponseMapper: ChartResponseMapper,
) {
    @GetMapping("/runs/{id}/chart")
    fun run(
        @PathVariable id: String,
        @RequestParam(required = false) pair: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) maxBuckets: Int?,
        @RequestParam(required = false) markerLimit: Int?,
        @RequestParam(name = "wallet", required = false) wallets: List<String>?,
        @RequestParam(required = false) cursor: String?,
    ): ChartResponse = chartResponseMapper.toResponse(
        chartConsoleService.runChart(id, ChartQuery(pair, from, to, maxBuckets, markerLimit, wallets.orEmpty(), cursor)))

    @GetMapping("/datasets/{id}/chart")
    fun dataset(
        @PathVariable id: String,
        @RequestParam(required = false) pair: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) maxBuckets: Int?,
        @RequestParam(required = false) markerLimit: Int?,
        @RequestParam(name = "wallet", required = false) wallets: List<String>?,
        @RequestParam(required = false) cursor: String?,
    ): ChartResponse = chartResponseMapper.toResponse(
        chartConsoleService.datasetChart(id, ChartQuery(pair, from, to, maxBuckets, markerLimit, wallets.orEmpty(), cursor)))
}
