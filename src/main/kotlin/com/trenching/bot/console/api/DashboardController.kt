package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.DashboardResponse
import com.trenching.bot.console.application.DashboardService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.DashboardResponseMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dashboard")
@ConditionalOnConsoleEnabled
class DashboardController(
    private val dashboardService: DashboardService,
    private val dashboardResponseMapper: DashboardResponseMapper,
) {
    @GetMapping
    fun dashboard(): DashboardResponse = dashboardResponseMapper.toResponse(dashboardService.snapshot())
}
