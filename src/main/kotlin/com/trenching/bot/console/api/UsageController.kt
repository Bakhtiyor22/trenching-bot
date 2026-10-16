package com.trenching.bot.console.api

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.console.api.dto.UsageResponse
import com.trenching.bot.console.application.UsageConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.UsageResponseMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/usage")
@ConditionalOnConsoleEnabled
class UsageController(
    private val usageConsoleService: UsageConsoleService,
    private val usageResponseMapper: UsageResponseMapper,
) {
    @GetMapping
    fun usage(
        @RequestParam(defaultValue = "LAST_HOUR") period: ApiUsagePeriod,
        @RequestParam(required = false) captureId: String?,
    ): UsageResponse = usageResponseMapper.toResponse(usageConsoleService.report(period, captureId))
}
