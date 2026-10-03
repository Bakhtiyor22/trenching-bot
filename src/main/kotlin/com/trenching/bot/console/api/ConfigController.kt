package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ConfigDefaultsResponse
import com.trenching.bot.console.application.RunLaunchService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.ConfigResponseMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/config")
@ConditionalOnConsoleEnabled
class ConfigController(
    private val runLaunchService: RunLaunchService,
    private val configResponseMapper: ConfigResponseMapper,
) {
    @GetMapping("/defaults")
    fun defaults(): ConfigDefaultsResponse = configResponseMapper.toResponse(runLaunchService.defaults())
}
