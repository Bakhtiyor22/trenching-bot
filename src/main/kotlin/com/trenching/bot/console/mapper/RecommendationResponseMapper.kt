package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.RecommendationResponse
import com.trenching.bot.console.domain.Recommendation
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface RecommendationResponseMapper {
    fun toResponse(recommendation: Recommendation): RecommendationResponse

    fun toResponses(recommendations: List<Recommendation>): List<RecommendationResponse>
}
