package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ConsoleErrorMapper {
    fun toResponse(code: String, message: String, fields: Map<String, String>): ConsoleErrorResponse
}
