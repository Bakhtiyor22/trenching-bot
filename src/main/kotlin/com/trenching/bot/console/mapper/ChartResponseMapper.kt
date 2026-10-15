package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ChartResponse
import com.trenching.bot.console.domain.ChartView
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ChartResponseMapper {
    fun toResponse(view: ChartView): ChartResponse
}
