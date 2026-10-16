package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.RecommendationResponse
import com.trenching.bot.console.api.dto.RunPresetResponse
import com.trenching.bot.console.application.RunSetupService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.RecommendationResponseMapper
import com.trenching.bot.console.mapper.RunPresetMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/runs")
@ConditionalOnConsoleEnabled
class RunSetupController(
    private val runSetupService: RunSetupService,
    private val runPresetMapper: RunPresetMapper,
    private val recommendationResponseMapper: RecommendationResponseMapper,
) {
    @GetMapping("/preset")
    fun preset(): RunPresetResponse = runPresetMapper.toResponse(runSetupService.preset())

    @GetMapping("/recommendations")
    fun recommendations(@RequestParam datasetId: String): List<RecommendationResponse> =
        recommendationResponseMapper.toResponses(runSetupService.recommendations(datasetId))
}
