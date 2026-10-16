package com.trenching.bot.console.mapper

import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.console.api.dto.UsageResponse
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface UsageResponseMapper {
    fun toResponse(report: ApiUsageReport): UsageResponse
}
