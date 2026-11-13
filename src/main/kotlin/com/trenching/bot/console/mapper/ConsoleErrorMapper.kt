package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ConsoleErrorMapper {
    @Mapping(target = "retryAfterSeconds", ignore = true)
    @Mapping(target = "errorId", ignore = true)
    fun toResponse(code: String, message: String, fields: Map<String, String>): ConsoleErrorResponse

    @Mapping(target = "errorId", ignore = true)
    fun toResponse(code: String, message: String, fields: Map<String, String>, retryAfterSeconds: Long?): ConsoleErrorResponse

    @Mapping(target = "retryAfterSeconds", ignore = true)
    fun toInternalError(code: String, message: String, fields: Map<String, String>, errorId: String): ConsoleErrorResponse
}
